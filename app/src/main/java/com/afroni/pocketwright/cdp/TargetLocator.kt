package com.afroni.pocketwright.cdp

import android.util.Log
import com.afroni.pocketwright.session.ApiException
import com.afroni.pocketwright.session.BrowserSession
import com.afroni.pocketwright.session.SessionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Une connexion DevTools à usage interne, à part de celles des clients : elle trouve la page d'une session
 * et lui envoie les commandes que la passerelle fait à la place du client.
 *
 * Le DevTools ne dit pas à quelle WebView correspond une cible. On le découvre par une marque temporaire :
 * l'app la pose dans le titre de la page par la WebView, puis on cherche la cible qui porte ce titre. La liste
 * des cibles n'interroge aucune page, ce qui compte : celles des onglets en pause, gelées, ne répondraient pas.
 */
class TargetLocator(private val manager: SessionManager) {

    private val connecting = Mutex()
    private val locating = Mutex()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val ids = AtomicLong()
    @Volatile private var socket: DevToolsSocket? = null

    /** Les événements DevTools de cette connexion (hors réseau, trop nombreux et sans intérêt ici). */
    @Volatile var onEvent: ((JsonObject) -> Unit)? = null
    @Volatile var onDisconnect: (() -> Unit)? = null

    /** La cible DevTools de cette session. */
    suspend fun locate(session: BrowserSession): String = locating.withLock {
        session.cdpTarget?.let { (cached, generation) ->
            if (generation == session.generation && pageTargets().any { it.first == cached }) return@withLock cached
        }
        val token = TITLE_PREFIX + UUID.randomUUID().toString().replace("-", "").take(16)
        var found: String? = null
        // L'onglet cherché doit être éveillé ; les autres, gelés par Android s'ils sont en pause, ne sont pas interrogés.
        session.whileActive {
            try {
                for (attempt in 0 until LOCATE_ATTEMPTS) {
                    withContext(Dispatchers.Main) { session.stamp(token) }
                    delay(LOCATE_RETRY_MS)
                    found = pageTargets().firstOrNull { it.second == token }?.first
                    if (found != null) break
                }
            } finally {
                withContext(Dispatchers.Main) { runCatching { session.unstamp() } }
            }
        }
        val target = found ?: throw ApiException(503, "Page DevTools de la session ${session.id} introuvable")
        session.cdpTarget = target to session.generation
        target
    }

    /** Exécute [block] avec une session CDP attachée à la cible. */
    suspend fun <T> onPage(target: String, block: suspend (String) -> T): T {
        val child = call("Target.attachToTarget", buildJsonObject { put("targetId", target); put("flatten", true) }).str("sessionId")
            ?: throw ApiException(502, "DevTools n'a pas ouvert la cible")
        return try {
            block(child)
        } finally {
            runCatching { call("Target.detachFromTarget", buildJsonObject { put("sessionId", child) }) }
        }
    }

    suspend fun call(method: String, params: JsonObject, sessionId: String? = null, timeoutMs: Long = 10_000): JsonObject {
        val connection = connection()
        val id = ids.incrementAndGet()
        val answer = CompletableDeferred<JsonObject>()
        pending[id] = answer
        try {
            connection.send(buildJsonObject {
                put("id", id)
                put("method", method)
                put("params", params)
                sessionId?.let { put("sessionId", it) }
            }.toString())
            val reply = withTimeoutOrNull(timeoutMs) { answer.await() } ?: throw ApiException(504, "DevTools ne répond pas à $method")
            (reply["error"] as? JsonObject)?.let { throw ApiException(502, "DevTools : ${it.str("message")}") }
            return reply["result"] as? JsonObject ?: JsonObject(emptyMap())
        } catch (e: IOException) {
            throw ApiException(502, "DevTools injoignable : ${e.message}")
        } finally {
            pending.remove(id)
        }
    }

    /** Les pages connues du DevTools : identifiant et titre. */
    private suspend fun pageTargets(): List<Pair<String, String>> {
        val infos = call("Target.getTargets", JsonObject(emptyMap()))["targetInfos"]?.jsonArray ?: return emptyList()
        return infos.mapNotNull { info ->
            val o = info as? JsonObject ?: return@mapNotNull null
            if (o.str("type") == "page") o.str("targetId")?.let { it to o.str("title").orEmpty() } else null
        }
    }

    private suspend fun connection(): DevToolsSocket {
        socket?.takeIf { it.isOpen }?.let { return it }
        return connecting.withLock {
            socket?.takeIf { it.isOpen }?.let { return@withLock it }
            val opened = DevToolsSocket.open()
            opened.start(
                onMessage = { text ->
                    if (!text.contains(NETWORK_EVENT)) {
                        val msg = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                        val id = msg?.get("id")?.jsonPrimitive?.longOrNull
                        if (msg != null && id != null) pending.remove(id)?.complete(msg)
                        else if (msg != null) onEvent?.invoke(msg)
                    }
                },
                onClose = { error ->
                    if (error != null) Log.w(TAG, "Connexion DevTools interne perdue", error)
                    pending.values.forEach { it.completeExceptionally(ApiException(502, "Connexion DevTools perdue")) }
                    pending.clear()
                    onDisconnect?.invoke()
                },
            )
            socket = opened
            opened
        }
    }

    private companion object {
        const val TAG = "PocketWright"
        const val LOCATE_ATTEMPTS = 80
        const val TITLE_PREFIX = BrowserSession.TITLE_MARK
        const val LOCATE_RETRY_MS = 120L
        const val NETWORK_EVENT = "\"method\":\"Network."

        fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
