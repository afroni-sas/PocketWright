package com.afroni.pocketwright.api

import android.util.Log
import com.afroni.pocketwright.BrowserInfo
import com.afroni.pocketwright.BuildConfig
import com.afroni.pocketwright.PocketWrightApp
import com.afroni.pocketwright.Settings
import com.afroni.pocketwright.api.MessageCommands.Companion.toJson
import com.afroni.pocketwright.cdp.CdpGateway
import com.afroni.pocketwright.cdp.TargetLocator
import com.afroni.pocketwright.engine.EngineStatus
import com.afroni.pocketwright.engine.PlaywrightEngine
import com.afroni.pocketwright.fingerprint.DeviceCatalog
import com.afroni.pocketwright.messaging.Message
import com.afroni.pocketwright.session.ApiException
import com.afroni.pocketwright.session.BrowserSession
import com.afroni.pocketwright.session.SessionManager
import com.afroni.pocketwright.session.SessionOptions
import com.afroni.pocketwright.session.string
import com.afroni.pocketwright.update.UpdateState
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * L'API, sur 127.0.0.1 seulement (le localhost du téléphone) :
 *  - REST : `/sessions` et `/sessions/{id}/{action}` ;
 *  - WebSocket `/ws` : une connexion ouvre sa propre session (ou reprend `?session=`), qui reste ouverte après
 *    la déconnexion ; la commande `close` la ferme ;
 *  - CDP `/cdp` et `/cdp/{id}` : pour Playwright, Puppeteer… (`chromium.connectOverCDP`), une session par connexion.
 */
class ApiServer(
    private val port: Int,
    private val manager: SessionManager,
    private val settings: Settings,
    private val engine: PlaywrightEngine,
    private val browser: BrowserInfo,
    private val commands: Commands,
    private val locator: TargetLocator,
    private val app: PocketWrightApp,
) {
    private var server: EmbeddedServer<*, *>? = null
    private val messageCommands = MessageCommands(app.messaging)

    fun start() {
        server = embeddedServer(CIO, port = port, host = "127.0.0.1") { module() }.also { it.start(wait = false) }
    }

    fun stop() {
        val running = server ?: return
        server = null
        // Hors du fil principal : l'arrêt attend la fin des requêtes en cours.
        thread(name = "api-stop", isDaemon = true) { runCatching { running.stop(500, 2_000) } }
    }

    private fun Application.module() {
        install(WebSockets) {
            pingPeriod = 20.seconds
            timeout = 60.seconds
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }
        routing {
            get("/") { call.api { serverInfo() } }

            get("/sessions") { call.api { JsonArray(manager.list().map { it.info().toJson() }) } }
            post("/sessions") { call.api { manager.create(SessionOptions.from(call.body())).info().toJson() } }
            delete("/sessions") { call.api { buildJsonObject { put("closed", manager.closeAll()) } } }

            get("/sessions/{id}") { call.api { session(call).info().toJson() } }
            delete("/sessions/{id}") { call.api { buildJsonObject { put("closed", manager.close(session(call).id)) } } }

            // L'image elle-même, à afficher ou enregistrer ; `?format=base64` la rend en JSON.
            get("/sessions/{id}/screenshot") {
                call.guard {
                    val params = call.query()
                    if (call.request.queryParameters["format"] == "base64") {
                        call.respondJson(ok(commands.run(session(call), "screenshot", params)))
                    } else {
                        val (bytes, type) = commands.screenshotBytes(session(call), params)
                        call.respondBytes(bytes, if (type == "jpeg") ContentType.Image.JPEG else ContentType.Image.PNG)
                    }
                }
            }
            post("/sessions/{id}/{action}") {
                call.api { commands.run(session(call), call.parameters["action"]!!, call.body()) }
            }
            get("/sessions/{id}/{action}") {
                call.api {
                    val action = call.parameters["action"]!!
                    if (action !in Commands.READ_ONLY) throw ApiException(405, "L'action « $action » se lance en POST")
                    commands.run(session(call), action, call.query())
                }
            }
            // Pour le moteur embarqué seulement (clé interne).
            get("/internal/sessions/{id}") {
                call.guard {
                    if (!call.isInternal()) throw ApiException(403, "Réservé au moteur de l'app")
                    val s = session(call)
                    call.respondJson(ok(buildJsonObject {
                        s.cdpTarget?.let { put("targetId", it.first) }
                        put("dialogs", buildJsonObject {
                            put("responder", s.dialogResponder)
                            put("mode", s.dialogMode)
                            put("promptText", s.promptText?.let(::JsonPrimitive) ?: JsonNull)
                        })
                    }))
                }
            }

            // Les mises à jour de Chromium et de Playwright.
            get("/updates") { call.api { updatesInfo() } }
            post("/updates/check") {
                call.api {
                    runCatching { app.chromium.check() }
                    runCatching { app.playwright.check(playwrightVersion(), nodeMajor()) }
                    updatesInfo()
                }
            }
            post("/updates/chromium") {
                call.api {
                    if (app.chromium.state.value.installing) throw ApiException(409, "Une installation de Chromium est déjà en cours")
                    val target = app.chromium.available.value ?: app.chromium.check()
                        ?: throw ApiException(409, "Le moteur Chromium est déjà à jour")
                    app.background.launch { runCatching { app.chromium.install(target) } }
                    buildJsonObject { put("installing", target.version); put("size", target.size) }
                }
            }
            post("/updates/playwright") {
                call.api {
                    if (app.playwright.state.value.installing) throw ApiException(409, "Une installation de Playwright est déjà en cours")
                    val wanted = call.body().string("version")
                    val target = (if (wanted != null) null else app.playwright.available.value)
                        ?: app.playwright.check(playwrightVersion(), nodeMajor(), wanted)
                        ?: throw ApiException(409, "Playwright est déjà à jour")
                    app.background.launch { runCatching { app.playwright.install(target) } }
                    buildJsonObject { put("installing", target.version) }
                }
            }
            post("/updates/chromium/system") { call.api { app.chromium.useSystemEngine(); updatesInfo() } }
            post("/updates/playwright/bundled") { call.api { app.playwright.useBundled(); updatesInfo() } }
            post("/updates/restart") {
                call.api {
                    app.restart()
                    buildJsonObject { put("restarting", true) }
                }
            }

            // Les messages entrants : à part, avec leur propre barrière (voir hasApiKey).
            get("/messages/status") {
                if (!call.hasApiKey()) return@get call.respondJson(failure(KEY_REQUIRED), 401)
                val lastSeq = withContext(Dispatchers.IO) { app.messaging.journal.latest.value }
                call.respondJson(ok(buildJsonObject {
                    put("defaultApp", app.messaging.isDefault())
                    put("streaming", settings.messagesApi.value)
                    put("lastSeq", lastSeq)
                }))
            }
            get("/messages/stream") {
                if (!call.hasApiKey()) return@get call.respondJson(failure(KEY_REQUIRED), 401)
                messagesRefusal()?.let { (status, reason) -> return@get call.respondJson(failure(reason), status) }
                val since = call.request.queryParameters["since"]?.toLongOrNull() ?: call.request.headers["Last-Event-ID"]?.toLongOrNull()
                call.response.header(HttpHeaders.CacheControl, "no-cache")
                call.respondTextWriter(ContentType.Text.EventStream) {
                    streamMessages(
                        since,
                        heartbeat = {
                            write(": ping\n\n")
                            flush()
                        },
                        emit = { seq, event ->
                            if (seq != null) write("id: $seq\n")
                            write("data: $event\n\n")
                            flush()
                        },
                    )
                }
            }
            webSocket("/messages/stream") { messagesSocket() }
            get("/messages/parts/{id}") {
                if (!call.hasApiKey()) return@get call.respondJson(failure(KEY_REQUIRED), 401)
                messagesRefusal()?.let { (status, reason) -> return@get call.respondJson(failure(reason), status) }
                val id = call.parameters["id"]?.toLongOrNull() ?: return@get call.respondJson(failure("Identifiant invalide"), 400)
                val part = withContext(Dispatchers.IO) { app.messaging.attachment(id) }
                    ?: return@get call.respondJson(failure("Pièce jointe introuvable"), 404)
                call.respondBytes(part.first, runCatching { ContentType.parse(part.second) }.getOrDefault(ContentType.Application.OctetStream))
            }

            get("/fingerprints/devices") { call.api { DeviceCatalog.toJson() } }

            webSocket("/ws") { commandSocket() }

            webSocket("/cdp") { cdpSocket(null) }
            webSocket("/cdp/{id}") { cdpSocket(call.parameters["id"]) }
            // Pour `connectOverCDP("http://127.0.0.1:8765/cdp/<id>")` : `new` ouvre une nouvelle session.
            get("/cdp/{id}/json/version") { cdpVersion(call) }
            get("/cdp/{id}/json/version/") { cdpVersion(call) }
        }
    }

    // ---------------------------------------------------------------- messages entrants

    /** Une connexion : le flux des messages entrants, et le CRUD des messages. */
    private suspend fun DefaultWebSocketServerSession.messagesSocket() {
        if (!call.hasApiKey()) return close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, KEY_REQUIRED.take(MAX_REASON)))
        messagesRefusal()?.let { (_, reason) -> return close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, reason.take(MAX_REASON))) }
        val since = call.request.queryParameters["since"]?.toLongOrNull()
        val key = settings.apiKey.value
        val stream = launch {
            streamMessages(since, heartbeat = {}, emit = { _, event -> send(Frame.Text(event.toString())) })
            runCatching { close(CloseReason(CloseReason.Codes.NORMAL, "Diffusion arrêtée")) }
        }
        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val msg = runCatching { Json.parseToJsonElement(frame.readText()).jsonObject }.getOrNull()
                if (msg == null) {
                    send(Frame.Text(reply(null, error = "JSON invalide", status = 400).toString()))
                    continue
                }
                val id = msg["id"]
                val action = (msg["action"] as? JsonPrimitive)?.contentOrNull
                if (action == null) {
                    send(Frame.Text(reply(id, error = "Champ « action » manquant", status = 400).toString()))
                    continue
                }
                val params = msg["params"] as? JsonObject ?: JsonObject(msg - "id" - "action")
                // Chaque commande à part : un envoi qui attend le réseau n'empêche pas les autres.
                launch {
                    val answer = try {
                        // Vérifié à chaque commande : l'accès peut être retiré pendant la connexion.
                        val refusal = messagesRefusal() ?: if (settings.apiKey.value != key) 401 to KEY_REQUIRED else null
                        if (refusal != null) throw ApiException(refusal.first, refusal.second)
                        reply(id, result = messageCommands.run(action, params))
                    } catch (e: ApiException) {
                        reply(id, error = e.message, status = e.status)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Log.e(TAG, "Commande $action", e)
                        reply(id, error = e.message ?: e.toString(), status = 500)
                    }
                    runCatching { send(Frame.Text(answer.toString())) }
                }
            }
        } finally {
            stream.cancel()
        }
    }

    /**
     * Pousse les messages dans l'ordre d'arrivée, sans en perdre : chaque client avance dans le journal à son
     * rythme. S'arrête dès que la diffusion n'est plus autorisée, ou que la clé a changé.
     */
    private suspend fun streamMessages(since: Long?, heartbeat: suspend () -> Unit, emit: suspend (Long?, JsonObject) -> Unit) {
        val messaging = app.messaging
        val journal = withContext(Dispatchers.IO) { messaging.journal }
        val key = settings.apiKey.value
        var cursor = since ?: journal.latest.value
        emit(null, buildJsonObject {
            put("type", "ready")
            put("lastSeq", journal.latest.value)
        })
        while (settings.messagesApi.value && settings.apiKey.value == key) {
            val batch = withContext(Dispatchers.IO) {
                journal.after(cursor, STREAM_BATCH).map { entry -> entry.seq to messaging.store.load(entry.kind, entry.messageId)?.takeIf { it.date == entry.date } }
            }
            if (batch.isNotEmpty()) {
                batch.forEach { (seq, message) ->
                    if (message != null) emit(seq, message.toEvent(seq))
                    cursor = seq
                }
                continue
            }
            val woken = withTimeoutOrNull(STREAM_HEARTBEAT_MS) {
                combine(journal.latest, settings.messagesApi, settings.apiKey) { latest, enabled, current ->
                    latest > cursor || !enabled || current != key
                }.first { it }
            }
            if (woken == null) {
                // Une autre app peut avoir repris le rôle : plus aucun message n'arrivera, autant le dire.
                if (!messaging.isDefault()) return
                heartbeat()
            }
        }
    }

    private fun Message.toEvent(seq: Long) = JsonObject(mapOf("type" to JsonPrimitive("message"), "seq" to JsonPrimitive(seq)) + toJson())

    private fun messagesRefusal(): Pair<Int, String>? = when {
        !app.messaging.isDefault() -> 409 to "PocketWright n'est pas la messagerie par défaut du téléphone"
        !settings.messagesApi.value -> 403 to "Accès aux messages désactivé : activez-le dans Paramètres, section Messagerie"
        else -> null
    }

    // ---------------------------------------------------------------- WebSocket de commandes

    private suspend fun DefaultWebSocketServerSession.commandSocket() {
        if (!call.allowed()) return close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Clé API manquante ou fausse"))
        val session = try {
            call.request.queryParameters["session"]?.let(manager::require) ?: manager.create(SessionOptions.from(call.query()))
        } catch (e: ApiException) {
            return close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, (e.message ?: "Session impossible").take(MAX_REASON)))
        }
        session.clients.incrementAndGet()
        manager.changed(session)
        withContext(Dispatchers.Main) { session.wake(); session.updateActivity() }
        var closing = false
        val events = launch { runCatching { session.events.collect { send(Frame.Text(it.toString())) } } }
        val watcher = launch {
            session.closedSignal.await()
            delay(100)
            if (!closing) runCatching { close(CloseReason(CloseReason.Codes.NORMAL, "Session fermée")) }
        }
        try {
            send(Frame.Text(buildJsonObject { put("type", "session"); put("session", session.info().toJson()) }.toString()))
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val msg = runCatching { Json.parseToJsonElement(frame.readText()).jsonObject }.getOrNull()
                if (msg == null) {
                    send(Frame.Text(reply(null, error = "JSON invalide").toString()))
                    continue
                }
                val id = msg["id"]
                val action = (msg["action"] as? JsonPrimitive)?.contentOrNull
                if (action == null) {
                    send(Frame.Text(reply(id, error = "Champ « action » manquant").toString()))
                    continue
                }
                val params = msg["params"] as? JsonObject ?: JsonObject(msg - "id" - "action")
                if (action == "close") {
                    closing = true
                    send(Frame.Text(reply(id, result = buildJsonObject { put("closed", manager.close(session.id)) }).toString()))
                    close(CloseReason(CloseReason.Codes.NORMAL, "Session fermée"))
                    break
                }
                // Chaque commande à part : une attente longue n'empêche pas d'en envoyer d'autres.
                launch {
                    val answer = try {
                        reply(id, result = commands.run(session, action, params))
                    } catch (e: ApiException) {
                        reply(id, error = e.message, status = e.status)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        reply(id, error = e.message ?: e.toString(), status = 500)
                    }
                    runCatching { send(Frame.Text(answer.toString())) }
                }
            }
        } finally {
            events.cancel()
            watcher.cancel()
            session.clients.decrementAndGet()
            manager.changed(session)
        }
    }

    private fun reply(id: JsonElement?, result: JsonElement? = null, error: String? = null, status: Int? = null) = buildJsonObject {
        put("type", "result")
        put("id", id ?: JsonNull)
        put("ok", error == null)
        if (error == null) put("result", result ?: JsonNull) else put("error", error)
        status?.let { put("status", it) }
    }

    // ---------------------------------------------------------------- CDP

    private suspend fun DefaultWebSocketServerSession.cdpSocket(id: String?) {
        if (!call.allowed()) return close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Clé API manquante ou fausse"))
        val session = try {
            if (id == null || id == "new") manager.create(SessionOptions.from(call.query())) else manager.require(id)
        } catch (e: ApiException) {
            return close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, (e.message ?: "Session impossible").take(MAX_REASON)))
        }
        // Le moteur de l'app n'est pas un client : il n'apparaît pas à l'écran et ne répond pas à la place des autres.
        val external = !call.isInternal()
        if (external) session.clients.incrementAndGet()
        manager.cdpConnected(session.group, external)
        manager.changed(session)
        withContext(Dispatchers.Main) { session.wake(); session.updateActivity() }
        try {
            CdpGateway(session, manager, locator).serve(this)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "CDP ${session.id} : ${e.message}")
            runCatching { close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, (e.message ?: "Erreur CDP").take(MAX_REASON))) }
        } finally {
            if (external) session.clients.decrementAndGet()
            manager.cdpDisconnected(session.group, external)
            manager.changed(session)
        }
    }

    private suspend fun cdpVersion(call: ApplicationCall) = call.guard {
        val id = call.parameters["id"]!!
        val session = if (id == "new") manager.create(SessionOptions.from(call.query())) else manager.require(id)
        val host = call.request.headers["Host"] ?: "127.0.0.1:$port"
        call.respondJson(buildJsonObject {
            put("Browser", "Chrome/${browser.version}")
            put("Protocol-Version", "1.3")
            put("User-Agent", browser.userAgent)
            put("webSocketDebuggerUrl", "ws://$host/cdp/${session.id}")
            put("pocketwrightSession", session.id)
        })
    }

    // ---------------------------------------------------------------- outils

    private fun serverInfo(): JsonObject = buildJsonObject {
        put("app", "PocketWright")
        put("version", BuildConfig.VERSION_NAME)
        put("api", "http://127.0.0.1:$port")
        put("sessions", manager.list().size)
        put("isolation", manager.isolation)
        put("browser", browser.toJson())
        put("playwright", buildJsonObject {
            when (val s = engine.status.value) {
                is EngineStatus.Ready -> { put("status", "ready"); put("version", s.playwright); put("node", s.node) }
                is EngineStatus.Failed -> { put("status", "failed"); put("error", s.message) }
                EngineStatus.Starting -> put("status", "starting")
            }
        })
        put("endpoints", buildJsonObject {
            put("rest", "http://127.0.0.1:$port/sessions")
            put("websocket", "ws://127.0.0.1:$port/ws")
            put("cdp", "ws://127.0.0.1:$port/cdp")
            put("messages", "ws://127.0.0.1:$port/messages/stream")
        })
        put("nativeActions", JsonArray(Commands.NATIVE.map(::JsonPrimitive)))
    }

    private fun playwrightVersion(): String? = (engine.status.value as? EngineStatus.Ready)?.playwright

    private fun nodeMajor(): Int? = (engine.status.value as? EngineStatus.Ready)?.node?.removePrefix("v")?.substringBefore('.')?.toIntOrNull()

    private fun updatesInfo(): JsonObject = buildJsonObject {
        put("chromium", buildJsonObject {
            put("current", browser.version)
            put("custom", app.chromium.loadedVersion != null)
            put("state", describe(app.chromium.state.value))
            app.chromium.available.value?.let { put("available", it.version); put("size", it.size) }
            app.chromium.lastFailure?.let { put("lastFailure", it) }
        })
        put("playwright", buildJsonObject {
            playwrightVersion()?.let { put("current", it) }
            put("custom", app.playwright.installedVersion != null)
            put("state", describe(app.playwright.state.value))
            app.playwright.available.value?.let { put("available", it.version) }
            app.playwright.lastFailure?.let { put("lastFailure", it) }
        })
    }

    private fun describe(state: UpdateState): String = when (state) {
        UpdateState.Idle -> "idle"
        UpdateState.Checking -> "checking"
        is UpdateState.Downloading -> "downloading:${(state.progress * 100).toInt()}"
        UpdateState.Installing -> "installing"
        is UpdateState.RestartNeeded -> "restart-needed:${state.version}"
        is UpdateState.Failed -> "failed:${state.message}"
    }

    private fun session(call: ApplicationCall): BrowserSession = manager.require(call.parameters["id"]!!)

    private suspend fun ApplicationCall.body(): JsonObject {
        val text = receiveText()
        if (text.isBlank()) return JsonObject(emptyMap())
        return runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: throw ApiException(400, "Le corps doit être un objet JSON")
    }

    /** Les paramètres d'URL, comme un corps JSON (`?selector=h1&timeout=5000`). */
    private fun ApplicationCall.query(): JsonObject =
        JsonObject(request.queryParameters.entries().associate { (k, v) -> k to typed(k, v.lastOrNull()) })

    /** Les valeurs d'URL sont du texte ; Playwright attend de vrais nombres et booléens pour ces options. */
    private fun typed(key: String, value: String?): JsonPrimitive = when {
        value == null -> JsonNull
        key in NUMERIC_PARAMS -> value.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value)
        key in BOOLEAN_PARAMS -> value.toBooleanStrictOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value)
        else -> JsonPrimitive(value)
    }

    private suspend fun ApplicationCall.api(block: suspend () -> JsonElement) = guard { respondJson(ok(block())) }

    private suspend fun ApplicationCall.guard(block: suspend () -> Unit) {
        if (!allowed()) return respondJson(failure("Clé API manquante ou fausse (en-tête Authorization: Bearer <clé>)"), 401)
        try {
            block()
        } catch (e: ApiException) {
            respondJson(failure(e.message ?: "Erreur"), e.status)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Erreur sur ${request.local.uri}", e)
            respondJson(failure(e.message ?: e.toString()), 500)
        }
    }

    /**
     * L'appel vient bien d'un programme du téléphone, et pas d'une page web :
     *  - le Host est local (une page qui détourne son nom de domaine vers 127.0.0.1 ne passe pas) ;
     *  - pas d'`Origin` ni de `Sec-Fetch-Site` étrangers : un navigateur les envoie toujours, y compris à
     *    l'ouverture d'un WebSocket, qui échappe sinon à toute protection d'origine ;
     *  - et la clé de l'API, si elle est exigée.
     */
    private fun ApplicationCall.allowed(): Boolean {
        val host = request.headers["Host"]?.let(::hostName)
        if (host != null && host !in LOCAL_HOSTS) return false
        val origin = request.headers["Origin"]
        if (origin != null && originHost(origin) !in LOCAL_HOSTS) return false
        if (request.headers["Sec-Fetch-Site"].let { it != null && it != "same-origin" && it != "none" }) return false
        if (isInternal()) return true
        if (!settings.requireKey.value) return true
        return givenKey()?.let { same(it, settings.apiKey.value) } == true
    }

    /**
     * Les SMS portent les codes de connexion : la vraie clé est exigée ici même si elle est facultative pour le
     * reste, et la clé interne du moteur Playwright n'y donne pas accès.
     */
    private fun ApplicationCall.hasApiKey(): Boolean = allowed() && givenKey()?.let { same(it, settings.apiKey.value) } == true

    private fun ApplicationCall.isInternal(): Boolean = givenKey()?.let { same(it, settings.internalKey) } == true

    private fun ApplicationCall.givenKey(): String? =
        request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
            ?: request.headers["X-API-Key"]
            ?: request.queryParameters["key"]

    private fun hostName(value: String): String {
        val host = value.trim().lowercase()
        if (host.startsWith("[")) return host.substringBefore(']') + "]"
        return host.substringBefore(':')
    }

    private fun originHost(origin: String): String? = runCatching { hostName(URI(origin).authority) }.getOrNull()

    private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private suspend fun ApplicationCall.respondJson(body: JsonObject, status: Int = 200) =
        respondText(body.toString(), ContentType.Application.Json, HttpStatusCode.fromValue(status))

    private fun ok(result: JsonElement) = buildJsonObject { put("ok", true); put("result", result) }

    private fun failure(message: String) = buildJsonObject { put("ok", false); put("error", message) }

    private companion object {
        const val TAG = "PocketWright"
        const val MAX_REASON = 100
        const val KEY_REQUIRED = "Clé API manquante ou fausse (obligatoire pour les messages)"
        const val STREAM_BATCH = 100
        const val STREAM_HEARTBEAT_MS = 20_000L
        val LOCAL_HOSTS = setOf("127.0.0.1", "localhost", "[::1]")
        val NUMERIC_PARAMS = setOf("timeout", "limit", "ms", "quality", "width", "height", "x", "y")
        val BOOLEAN_PARAMS = setOf("fullPage", "omitBackground", "native", "wait")
    }
}
