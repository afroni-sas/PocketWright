package com.afroni.pocketwright.cdp

import android.util.Base64
import android.util.Log
import com.afroni.pocketwright.session.ApiException
import com.afroni.pocketwright.session.BrowserSession
import com.afroni.pocketwright.session.SessionManager
import com.afroni.pocketwright.session.SessionOptions
import com.afroni.pocketwright.session.TabOrigin
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Une connexion CDP (Chrome DevTools Protocol) limitée à un groupe d'onglets.
 *
 * Le DevTools d'Android voit toutes les WebViews de l'app d'un coup. La passerelle ne montre au client que
 * les pages de sa session (et les popups qu'elle ouvre), et traduit ce que la WebView ne sait pas faire
 * au niveau du navigateur :
 *  - `Storage.*Cookies` passe par les commandes `Network.*` de la page, qui agissent sur son profil ;
 *  - `Target.createTarget` (`context.newPage()`) ouvre un nouvel onglet dans le groupe ;
 *  - `Target.createBrowserContext` (`browser.newContext()`) réserve un nouveau groupe, donc un nouveau profil ;
 *  - `Page.captureScreenshot` hors de l'écran est servi par la capture native de l'app.
 */
class CdpGateway(
    private val session: BrowserSession,
    private val manager: SessionManager,
    private val locator: TargetLocator,
) {
    /** Une page du client, et son onglet quand on le connaît déjà (les popups se découvrent à l'usage). */
    private class Tracked(val session: BrowserSession?)

    private class HeldAttach(val text: String, val msg: JsonObject, val child: String)

    private val upstreamMessages = Channel<String>(Channel.UNLIMITED)
    private val internal = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val internalIds = AtomicLong(INTERNAL_BASE)
    private val targets = ConcurrentHashMap<String, Tracked>()
    /** Les sessions CDP du client : celles des pages, et celles qui en dépendent (workers, iframes). */
    private val allowed = ConcurrentHashMap.newKeySet<String>()
    /** Session CDP → cible, pour les pages seulement. */
    private val pageSessions = ConcurrentHashMap<String, String>()
    private val getTargetsCalls = ConcurrentHashMap.newKeySet<Long>()
    /** Les `browserContextId` donnés au client → le groupe d'onglets correspondant. */
    private val contexts = ConcurrentHashMap<String, String>()
    private val targetContext = ConcurrentHashMap<String, String>()
    /** Une page vierge ouverte pour écrire des cookies avant la première page d'un contexte. */
    private val spare = ConcurrentHashMap<String, Pair<BrowserSession, String>>()
    /** Les pages inconnues attachées pendant qu'on crée un onglet : l'une d'elles est sans doute la nôtre. */
    private val held = ConcurrentHashMap<String, HeldAttach>()
    private val creating = AtomicInteger(0)

    private lateinit var upstream: DevToolsSocket
    private lateinit var ws: DefaultWebSocketSession
    private lateinit var scope: CoroutineScope
    @Volatile private var ready = false
    @Volatile private var autoAttach = false
    @Volatile private var discover = false

    /** Relaie les messages entre le client [ws] et le DevTools, jusqu'à ce que l'un des deux s'en aille. */
    suspend fun serve(ws: DefaultWebSocketSession) {
        this.ws = ws
        upstream = DevToolsSocket.open()
        upstream.start(onMessage = { upstreamMessages.trySend(it) }, onClose = { upstreamMessages.close() })
        try {
            coroutineScope {
                scope = this
                val pump = launch {
                    for (text in upstreamMessages) {
                        try {
                            fromUpstream(text)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Message DevTools ignoré", e)
                        }
                    }
                    // Le DevTools est parti (l'app se ferme, le rendu redémarre) : le client doit le savoir.
                    closeClient("DevTools déconnecté")
                }
                // Les onglets normaux partagent un profil sans être liés : un client n'en voit qu'un.
                val shared = session.group == SessionManager.BROWSER_GROUP || session.group == SessionManager.INCOGNITO_GROUP
                manager.list().filter { it === session || (!shared && it.group == session.group && it.live) }.forEach {
                    targets[locator.locate(it)] = Tracked(it)
                }
                ready = true
                val watcher = launch {
                    session.closedSignal.await()
                    delay(CLOSE_DELAY_MS)
                    if (pageSessions.isEmpty()) closeClient("Session fermée")
                }
                try {
                    for (frame in ws.incoming) {
                        if (frame is Frame.Text) fromClient(frame.readText())
                    }
                } finally {
                    watcher.cancel()
                    upstream.close()
                    pump.cancel()
                    upstreamMessages.close()
                    scope.coroutineContext.cancelChildren()
                }
            }
        } finally {
            upstream.close()
        }
    }

    // ------------------------------------------------------------ client → DevTools

    private suspend fun fromClient(text: String) {
        // Les gros messages (fichiers envoyés, scripts) ne s'analysent pas : seule leur session compte.
        if (text.length > FAST_PATH_CHARS) {
            val child = trailingSession(text)
            if (child != null && child in allowed) {
                upstream.send(text)
                return
            }
        }
        val msg = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val id = msg["id"]?.jsonPrimitive?.longOrNull
        val method = msg.str("method")
        val sessionId = msg.str("sessionId")
        val params = msg["params"] as? JsonObject ?: JsonObject(emptyMap())
        val call = ClientCall(id, sessionId)

        if (sessionId != null) {
            when {
                sessionId !in allowed -> call.fail("Session with given id not found.", -32001)
                method == "Page.captureScreenshot" && pageSessions.containsKey(sessionId) -> spawn(call) {
                    call.reply(buildJsonObject { put("data", screenshot(params, sessionId)) })
                }
                else -> upstream.send(text)
            }
            return
        }
        when (method) {
            // Le client se déconnecte ; ses onglets, eux, restent ouverts.
            "Browser.close" -> {
                call.reply()
                closeClient("Déconnecté, la session reste ouverte")
            }
            "Browser.setDownloadBehavior" -> call.reply()
            "Browser.crash", "Browser.crashGpuProcess" -> call.fail("Refusé par PocketWright")
            "Browser.grantPermissions", "Browser.resetPermissions" -> {
                val granted = method == "Browser.grantPermissions" &&
                    (params["permissions"] as? JsonArray).orEmpty().any { it.jsonPrimitive.contentOrNull == "geolocation" }
                manager.list().filter { it.group in groups() }.forEach { it.geolocationGranted = granted }
                call.reply()
            }
            "Storage.getCookies" -> spawn(call) {
                val cookies = onContextPage(params.str("browserContextId")) { child -> locator.call("Network.getAllCookies", EMPTY, child) }
                call.reply(buildJsonObject { put("cookies", cookies["cookies"] ?: JsonArray(emptyList())) })
            }
            "Storage.setCookies" -> spawn(call) {
                val cookies = params["cookies"] ?: JsonArray(emptyList())
                onContextPage(params.str("browserContextId")) { child ->
                    locator.call("Network.setCookies", buildJsonObject { put("cookies", cookies) }, child)
                }
                call.reply()
            }
            "Storage.clearCookies" -> spawn(call) {
                onContextPage(params.str("browserContextId")) { child -> locator.call("Network.clearBrowserCookies", EMPTY, child) }
                call.reply()
            }
            "Target.setAutoAttach" -> {
                autoAttach = params.str("autoAttach") == "true"
                upstream.send(text)
            }
            "Target.setDiscoverTargets" -> {
                discover = params.str("discover") == "true"
                upstream.send(text)
            }
            "Target.createBrowserContext" -> {
                val contextId = UUID.randomUUID().toString().replace("-", "").uppercase()
                contexts[contextId] = "ctx" + contextId.take(10).lowercase()
                call.reply(buildJsonObject { put("browserContextId", contextId) })
            }
            "Target.disposeBrowserContext" -> spawn(call) {
                val group = contexts.remove(params.str("browserContextId").orEmpty())
                if (group != null) manager.list().filter { it.group == group }.forEach { manager.close(it.id) }
                call.reply()
            }
            "Target.createTarget" -> spawn(call) { createTarget(params, call) }
            // page.close() : la commande de fermeture de l'onglet.
            "Target.closeTarget" -> spawn(call) {
                val target = params.str("targetId").orEmpty()
                if (!targets.containsKey(target)) return@spawn call.fail("No target with given id found")
                val owner = sessionFor(target)
                call.reply(buildJsonObject { put("success", true) })
                targets.remove(target)
                if (owner != null) manager.close(owner.id) else fireAndForget("Target.closeTarget", params)
            }
            "Target.attachToTarget", "Target.activateTarget", "Target.exposeDevToolsProtocol", "Target.getTargetInfo" -> {
                val target = params.str("targetId")
                if (target != null && !targets.containsKey(target)) call.fail("No target with given id found") else upstream.send(text)
            }
            "Target.detachFromTarget", "Target.sendMessageToTarget" -> {
                val target = params.str("targetId")
                val child = params.str("sessionId")
                if ((child != null && child !in allowed) || (target != null && !targets.containsKey(target))) {
                    call.fail("Session with given id not found.", -32001)
                } else {
                    upstream.send(text)
                }
            }
            "Target.getTargets" -> {
                id?.let { getTargetsCalls += it }
                upstream.send(text)
            }
            else -> upstream.send(text)
        }
    }

    /** `context.newPage()` : un nouvel onglet, livré au client comme une cible attachée, puis son identifiant. */
    private suspend fun createTarget(params: JsonObject, call: ClientCall) {
        val contextId = params.str("browserContextId")?.takeIf { contexts.containsKey(it) }
        val group = contextId?.let { contexts[it] } ?: session.group
        creating.incrementAndGet()
        try {
            val reused = contextId?.let { spare.remove(it) }
            val child = reused?.first ?: manager.create(
                SessionOptions(url = params.str("url")?.takeIf { it != "about:blank" }, group = group, origin = TabOrigin.API),
            )
            val target = reused?.second ?: locator.locate(child)
            targets[target] = Tracked(child)
            contextId?.let { targetContext[target] = it }
            if (autoAttach) {
                val attached = if (reused == null) awaitHeld(target) else null
                if (attached != null) deliver(attached, target)
                else relay("Target.attachToTarget", buildJsonObject { put("targetId", target); put("flatten", true) })
            } else if (discover) {
                val info = locator.call("Target.getTargetInfo", buildJsonObject { put("targetId", target) })
                sendToClient(buildJsonObject {
                    put("method", "Target.targetCreated")
                    put("params", buildJsonObject { put("targetInfo", asClientSees(info["targetInfo"] as? JsonObject ?: EMPTY, target)) })
                }.toString())
            }
            call.reply(buildJsonObject { put("targetId", target) })
        } finally {
            if (creating.decrementAndGet() == 0) releaseHeld()
        }
    }

    private suspend fun awaitHeld(target: String): HeldAttach? =
        withTimeoutOrNull(HELD_WAIT_MS) {
            var found = held.remove(target)
            while (found == null) {
                delay(30)
                found = held.remove(target)
            }
            found
        }

    private suspend fun deliver(attach: HeldAttach, target: String) {
        allowed += attach.child
        pageSessions[attach.child] = target
        sendToClient(rewriteAttach(attach.msg, target, attach.text))
    }

    private fun releaseHeld() {
        held.keys.toList().forEach { key ->
            held.remove(key)?.let { fireAndForget("Target.detachFromTarget", buildJsonObject { put("sessionId", it.child) }) }
        }
    }

    /** Une page du contexte, pour y faire passer une commande qui vise tout le profil. */
    private suspend fun <T> onContextPage(contextId: String?, block: suspend (String) -> T): T {
        val fake = contextId?.takeIf { contexts.containsKey(it) }
        // Seulement une page encore attachée : une page fermée reste connue, mais ne répond plus.
        val attached = pageSessions.values.toSet()
        val target = if (fake == null) {
            attached.firstOrNull { !targetContext.containsKey(it) } ?: locator.locate(session)
        } else {
            attached.firstOrNull { targetContext[it] == fake } ?: spareFor(fake)
        }
        return locator.onPage(target, block)
    }

    /** Un contexte sans page encore (`newContext({ storageState })`) : une page vierge, que `newPage` reprendra. */
    private suspend fun spareFor(contextId: String): String {
        spare[contextId]?.let { return it.second }
        val blank = manager.create(SessionOptions(group = contexts[contextId], origin = TabOrigin.API))
        val target = locator.locate(blank)
        spare[contextId] = blank to target
        return target
    }

    /**
     * `Page.captureScreenshot` attend une image du compositeur, qu'une WebView hors de l'écran ne produit
     * pas : la commande n'aboutirait jamais. La passerelle répond donc par la capture native de l'onglet
     * (mêmes conventions : `clip` en pixels CSS du document, `scale`, `captureBeyondViewport`). Quand
     * l'onglet est affiché, on essaie d'abord celle de Chromium.
     */
    private suspend fun screenshot(params: JsonObject, sessionId: String): String {
        val target = pageSessions[sessionId] ?: throw ApiException(404, "Page inconnue")
        val owner = sessionFor(target) ?: throw ApiException(404, "Onglet introuvable")
        if (owner.shown) {
            val real = owner.withCanvasCopies { runCatching { relay("Page.captureScreenshot", params, sessionId, timeoutMs = 4_000) }.getOrNull() }
            real?.str("data")?.let { return it }
        }
        val clip = params["clip"] as? JsonObject
        val bytes = owner.capture(
            clip = clip?.let { BrowserSession.Clip(it.num("x"), it.num("y"), it.num("width"), it.num("height")) },
            outScale = clip?.str("scale")?.toDoubleOrNull(),
            beyondViewport = params.str("captureBeyondViewport") == "true",
            format = params.str("format") ?: "png",
            quality = params.str("quality")?.toIntOrNull() ?: 90,
        )
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    // ------------------------------------------------------------ DevTools → client

    private suspend fun fromUpstream(text: String) {
        if (text.length > FAST_PATH_CHARS) {
            val id = leadingId(text)
            val child = trailingSession(text)
            val relayable = child == null || child in allowed
            if (id != null && id < INTERNAL_BASE && id !in getTargetsCalls && relayable) return sendToClient(text)
            if (id == null && child != null && child in allowed && !text.startsWith(TARGET_EVENT)) return sendToClient(text)
        }
        val msg = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val id = msg["id"]?.jsonPrimitive?.longOrNull
        if (id != null && id >= INTERNAL_BASE) {
            internal.remove(id)?.complete(msg)
            return
        }
        if (!ready) return
        val sessionId = msg.str("sessionId")
        if (id != null) {
            if (sessionId == null && getTargetsCalls.remove(id)) {
                val result = msg["result"] as? JsonObject
                val infos = result?.get("targetInfos") as? JsonArray
                if (infos != null) {
                    val mine = JsonArray(infos.filter { targets.containsKey((it as? JsonObject)?.str("targetId").orEmpty()) }.map { element ->
                        val info = element as JsonObject
                        asClientSees(info, info.str("targetId").orEmpty())
                    })
                    sendToClient(JsonObject(msg + ("result" to JsonObject(result + ("targetInfos" to mine)))).toString())
                    return
                }
            }
            sendToClient(text)
            return
        }

        val params = msg["params"] as? JsonObject ?: EMPTY
        when (msg.str("method")) {
            "Target.attachedToTarget" -> onAttached(text, msg, params, sessionId)
            "Target.detachedFromTarget" -> {
                val child = params.str("sessionId") ?: return
                if (!allowed.remove(child)) return
                sendToClient(text)
                if (pageSessions.remove(child) != null && pageSessions.isEmpty() && creating.get() == 0) {
                    scope.launch {
                        delay(CLOSE_DELAY_MS)
                        if (pageSessions.isEmpty() && creating.get() == 0) closeClient("Plus aucune page")
                    }
                }
            }
            "Target.receivedMessageFromTarget" -> if (params.str("sessionId") in allowed) sendToClient(text)
            "Target.targetCreated", "Target.targetInfoChanged" -> {
                val info = params["targetInfo"] as? JsonObject ?: return
                val target = info.str("targetId").orEmpty()
                if (isOurs(info)) {
                    sendToClient(JsonObject(msg + ("params" to JsonObject(params + ("targetInfo" to asClientSees(info, target))))).toString())
                }
            }
            "Target.targetDestroyed", "Target.targetCrashed" -> {
                val target = params.str("targetId").orEmpty()
                if (targets.containsKey(target)) {
                    sendToClient(text)
                    if (msg.str("method") == "Target.targetDestroyed") targets.remove(target)
                }
            }
            // Un client a répondu à la boîte de dialogue : l'onglet n'a plus à attendre.
            "Page.javascriptDialogClosed" -> {
                if (sessionId != null && sessionId in allowed) {
                    pageSessions[sessionId]?.let { target -> targets[target]?.session?.dialogClosedRemotely() }
                    sendToClient(text)
                }
            }
            else -> if (sessionId == null || sessionId in allowed) sendToClient(text)
        }
    }

    private suspend fun onAttached(text: String, msg: JsonObject, params: JsonObject, sessionId: String?) {
        val child = params.str("sessionId") ?: return
        val info = params["targetInfo"] as? JsonObject
        val target = info?.str("targetId")
        if (sessionId != null) {
            if (sessionId in allowed) {
                allowed += child
                sendToClient(text)
            }
            return
        }
        when {
            info != null && target != null && isOurs(info) -> {
                val opener = info.str("openerId")
                if (!targets.containsKey(target) && opener != null) {
                    targets[target] = Tracked(null)
                    targetContext[opener]?.let { targetContext[target] = it }
                }
                allowed += child
                if (info.str("type") == "page") pageSessions[child] = target
                sendToClient(rewriteAttach(msg, target, text))
            }
            // Une nouvelle page pendant la création d'un onglet : on la garde de côté le temps de savoir.
            creating.get() > 0 && target != null && info?.str("type") == "page" -> {
                if (params.str("waitingForDebugger") == "true") fireAndForget("Runtime.runIfWaitingForDebugger", EMPTY, child)
                held[target] = HeldAttach(text, msg, child)
            }
            else -> {
                // La page d'un autre onglet : on la relâche, sans la laisser en pause.
                if (params.str("waitingForDebugger") == "true") fireAndForget("Runtime.runIfWaitingForDebugger", EMPTY, child)
                fireAndForget("Target.detachFromTarget", buildJsonObject { put("sessionId", child) })
            }
        }
    }

    private fun isOurs(info: JsonObject): Boolean {
        val target = info.str("targetId") ?: return false
        return targets.containsKey(target) || info.str("openerId")?.let { targets.containsKey(it) } == true
    }

    /** Le `browserContextId` que le client a reçu, à la place de celui de la WebView. */
    private fun asClientSees(info: JsonObject, target: String): JsonObject {
        val contextId = targetContext[target] ?: return info
        return JsonObject(info + ("browserContextId" to JsonPrimitive(contextId)))
    }

    private fun rewriteAttach(msg: JsonObject, target: String, original: String): String {
        if (!targetContext.containsKey(target)) return original
        val params = msg["params"] as? JsonObject ?: return original
        val info = params["targetInfo"] as? JsonObject ?: return original
        return JsonObject(msg + ("params" to JsonObject(params + ("targetInfo" to asClientSees(info, target))))).toString()
    }

    // ------------------------------------------------------------ mécanique

    private fun groups(): Set<String> = contexts.values.toSet() + session.group

    private suspend fun sessionFor(target: String): BrowserSession? {
        targets[target]?.session?.let { return it }
        val groups = groups()
        for (member in manager.list()) {
            if (member.group !in groups || !member.live) continue
            if (runCatching { locator.locate(member) }.getOrNull() == target) {
                targets[target] = Tracked(member)
                return member
            }
        }
        return null
    }

    private inner class ClientCall(val id: Long?, val sessionId: String?) {
        suspend fun reply(result: JsonObject = EMPTY) = sendToClient(buildJsonObject {
            id?.let { put("id", it) }
            put("result", result)
            sessionId?.let { put("sessionId", it) }
        }.toString())

        suspend fun fail(message: String, code: Int = -32000) = sendToClient(buildJsonObject {
            id?.let { put("id", it) }
            put("error", buildJsonObject { put("code", code); put("message", message) })
            sessionId?.let { put("sessionId", it) }
        }.toString())
    }

    /** Une commande longue, à part du fil de lecture ; une erreur devient une réponse, jamais un plantage. */
    private fun spawn(call: ClientCall, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Commande CDP échouée", e)
                call.fail(e.message ?: e.toString())
            }
        }
    }

    /** Envoie au client, dans l'ordre : le protocole CDP ne supporte pas qu'une réponse double un événement. */
    private suspend fun sendToClient(text: String) {
        runCatching { ws.send(Frame.Text(text)) }
    }

    private fun closeClient(reason: String) {
        scope.launch { runCatching { ws.close(CloseReason(CloseReason.Codes.NORMAL, reason.take(MAX_REASON))) } }
    }

    /** Une commande envoyée sur la connexion du client, dont la réponse reste à la passerelle. */
    private suspend fun relay(method: String, params: JsonObject, sessionId: String? = null, timeoutMs: Long = 10_000): JsonObject {
        val id = internalIds.incrementAndGet()
        val answer = CompletableDeferred<JsonObject>()
        internal[id] = answer
        upstream.send(message(id, method, params, sessionId))
        val reply = withTimeoutOrNull(timeoutMs) { answer.await() }
        internal.remove(id)
        reply ?: throw ApiException(504, "DevTools ne répond pas à $method")
        (reply["error"] as? JsonObject)?.let { throw ApiException(502, "DevTools : ${it.str("message")}") }
        return reply["result"] as? JsonObject ?: EMPTY
    }

    private fun fireAndForget(method: String, params: JsonObject, sessionId: String? = null) {
        runCatching { upstream.send(message(internalIds.incrementAndGet(), method, params, sessionId)) }
    }

    private fun message(id: Long, method: String, params: JsonObject, sessionId: String?) = buildJsonObject {
        put("id", id)
        put("method", method)
        put("params", params)
        sessionId?.let { put("sessionId", it) }
    }.toString()

    companion object {
        private const val TAG = "PocketWright"
        /** Les identifiants des messages de la passerelle, loin de ceux des clients (qui partent de 1). */
        private const val INTERNAL_BASE = 1_000_000_000L
        private const val CLOSE_DELAY_MS = 300L
        private const val HELD_WAIT_MS = 3_000L
        private const val MAX_REASON = 100
        private const val FAST_PATH_CHARS = 64 * 1024
        private const val TARGET_EVENT = "{\"method\":\"Target."
        private val EMPTY = JsonObject(emptyMap())
        private val TRAILING_SESSION = Regex("\"sessionId\":\"([0-9A-Za-z-]+)\"\\}$")
        private val LEADING_ID = Regex("^\\{\"id\":(\\d+),")

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.num(key: String): Double = str(key)?.toDoubleOrNull() ?: 0.0

        private fun trailingSession(text: String): String? =
            TRAILING_SESSION.find(text.takeLast(96))?.groupValues?.get(1)

        private fun leadingId(text: String): Long? =
            LEADING_ID.find(text.take(32))?.groupValues?.get(1)?.toLongOrNull()
    }
}
