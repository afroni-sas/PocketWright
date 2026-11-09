package com.afroni.pocketwright.engine

import android.content.Context
import android.system.Os
import android.util.Log
import com.afroni.pocketwright.session.ApiException
import com.afroni.pocketwright.update.PlaywrightUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.security.SecureRandom

/** Le lancement de Node.js, par la bibliothèque native `libpocketwright.so`. */
internal object NodeRuntime {
    val loaded: Throwable? = runCatching {
        System.loadLibrary("node")
        System.loadLibrary("pocketwright")
    }.exceptionOrNull()

    /** Bloque jusqu'à la fin de Node, qui ne peut tourner qu'une fois par processus. */
    external fun start(arguments: Array<String>): Int
}

sealed interface EngineStatus {
    data object Starting : EngineStatus
    data class Ready(val playwright: String, val node: String) : EngineStatus
    data class Failed(val message: String) : EngineStatus
}

/**
 * Le vrai Playwright, dans l'app : Node.js (nodejs-mobile) fait tourner `playwright-core`, qui pilote chaque
 * session par la passerelle CDP. On lui parle en HTTP sur 127.0.0.1, avec un jeton.
 */
class PlaywrightEngine(
    private val app: Context,
    private val apiPort: Int,
    private val gatewayKey: String,
    private val updates: PlaywrightUpdater,
) {


    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    @Volatile private var port = 0
    @Volatile private var started = false

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Starting)
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    val ready: Boolean get() = _status.value is EngineStatus.Ready

    @Synchronized
    fun start() {
        if (started) return
        started = true
        NodeRuntime.loaded?.let {
            Log.e(TAG, "Node.js absent de cette version de l'app", it)
            _status.value = EngineStatus.Failed("Node.js indisponible sur cet appareil : ${it.message}")
            return
        }
        scope.launch {
            try {
                val project = installProject()
                port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
                val cache = File(app.cacheDir, "node").apply { mkdirs() }
                mapOf(
                    "TMPDIR" to cache.path,
                    "HOME" to app.filesDir.path,
                    "NODE_COMPILE_CACHE" to File(cache, "compile").path,
                    "NODE_COMPILE_CACHE_PORTABLE" to "1",
                    "PLAYWRIGHT_BROWSERS_PATH" to File(app.filesDir, "ms-playwright").path,
                    "PW_ENGINE_PORT" to port.toString(),
                    "PW_ENGINE_TOKEN" to token,
                    "PW_GATEWAY" to "ws://127.0.0.1:$apiPort/cdp",
                    "PW_API" to "http://127.0.0.1:$apiPort",
                    "PW_GATEWAY_KEY" to gatewayKey,
                    // V8 dimensionne son tas sur la mémoire du téléphone : trop, et Android tue toute l'app.
                    "NODE_OPTIONS" to "--max-old-space-size-percentage=20",
                ).forEach { (k, v) -> Os.setenv(k, v, true) }
                // Une version téléchargée de Playwright, à la place de celle livrée avec l'app.
                updates.corePath()?.let { Os.setenv("PW_CORE_PATH", it.path, true) }
                // V8 a besoin d'une pile plus grande que celle d'un fil Java ordinaire.
                Thread(null, {
                    val code = runCatching { NodeRuntime.start(arrayOf("node", File(project, "main.js").path)) }
                    val message = code.exceptionOrNull()?.message ?: "Node s'est arrêté (code ${code.getOrNull()})"
                    Log.e(TAG, message)
                    _status.value = EngineStatus.Failed(message)
                }, "node", 16L * 1024 * 1024).apply { isDaemon = true }.start()
                waitUntilReady()
            } catch (e: Exception) {
                Log.e(TAG, "Moteur Playwright non démarré", e)
                _status.value = EngineStatus.Failed(e.message ?: e.toString())
            }
        }
    }

    /** Node répond-il encore ? Un script en boucle infinie le fige sans le faire tomber. */
    suspend fun healthy(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val c = (URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection).apply {
                connectTimeout = 2_000
                readTimeout = 8_000
                setRequestProperty("x-engine-token", token)
            }
            try {
                c.responseCode == 200
            } finally {
                c.disconnect()
            }
        }.getOrDefault(false)
    }

    /** Une commande Playwright sur une session. */
    suspend fun command(session: String, action: String, params: JsonObject, timeoutMs: Long): JsonElement =
        post("/command", buildJsonObject {
            put("session", session)
            put("action", action)
            put("params", params)
        }, timeoutMs)

    /** Les actions que le moteur connaît. */
    suspend fun actions(): List<String> =
        post("/actions", JsonObject(emptyMap()), 5_000).jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }

    /** Le moteur lâche la session (elle se ferme, ou on la recrée). */
    fun release(session: String) {
        if (!ready) return
        scope.launch { runCatching { post("/release", buildJsonObject { put("session", session) }, 10_000) } }
    }

    private suspend fun post(path: String, body: JsonObject, timeoutMs: Long): JsonElement {
        val state = _status.value
        if (state !is EngineStatus.Ready) {
            throw ApiException(503, when (state) {
                is EngineStatus.Failed -> "Moteur Playwright arrêté : ${state.message}"
                else -> "Moteur Playwright en cours de démarrage, réessayez dans un instant"
            })
        }
        return withContext(Dispatchers.IO) {
            val connection = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 5_000
                // La réponse attend la fin de la commande, qui a son propre délai : on laisse une marge.
                readTimeout = (timeoutMs + 30_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                setRequestProperty("content-type", "application/json")
                setRequestProperty("x-engine-token", token)
            }
            try {
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = connection.responseCode
                val text = (if (code < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
                val reply = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                    ?: throw ApiException(502, "Réponse illisible du moteur Playwright ($code)")
                if (reply["ok"]?.jsonPrimitive?.contentOrNull == "true") return@withContext reply["result"] ?: JsonNull
                throw ApiException(code, (reply["error"] as? JsonPrimitive)?.contentOrNull ?: "Erreur du moteur Playwright")
            } catch (e: SocketTimeoutException) {
                throw ApiException(408, "Le moteur Playwright n'a pas répondu à temps")
            } catch (e: IOException) {
                throw ApiException(503, "Moteur Playwright injoignable : ${e.message}")
            } finally {
                connection.disconnect()
            }
        }
    }

    private suspend fun waitUntilReady() {
        repeat(240) {
            if (_status.value is EngineStatus.Failed) return
            val health = runCatching {
                val c = (URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 1_000
                    readTimeout = 2_000
                    setRequestProperty("x-engine-token", token)
                }
                try {
                    Json.parseToJsonElement(c.inputStream.use { it.readBytes().decodeToString() }).jsonObject
                } finally {
                    c.disconnect()
                }
            }.getOrNull()
            if (health != null) {
                _status.value = EngineStatus.Ready(
                    playwright = health["playwright"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    node = health["node"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
                Log.i(TAG, "Moteur Playwright prêt : ${_status.value}")
                updates.confirmHealthy()
                return
            }
            delay(500)
        }
        _status.value = EngineStatus.Failed("Le moteur Playwright ne répond pas")
    }

    /** Recopie le projet Node des ressources de l'APK vers le stockage de l'app, quand il a changé. */
    private fun installProject(): File {
        val target = File(app.filesDir, "nodejs-project")
        val bundled = app.assets.open("$ASSETS/stamp.txt").use { it.readBytes().decodeToString().trim() }
        val installed = File(target, "stamp.txt").takeIf { it.exists() }?.readText()?.trim()
        if (installed == bundled) return target
        target.deleteRecursively()
        copyAssets(ASSETS, target)
        return target
    }

    private fun copyAssets(path: String, to: File) {
        val children = app.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            to.parentFile?.mkdirs()
            // Un dossier vide se liste comme un fichier, mais ne s'ouvre pas.
            runCatching { app.assets.open(path) }.getOrNull()?.use { input -> to.outputStream().use { input.copyTo(it) } }
                ?: to.mkdirs()
            return
        }
        to.mkdirs()
        children.forEach { copyAssets("$path/$it", File(to, it)) }
    }

    private companion object {
        const val TAG = "PocketWright"
        const val ASSETS = "nodejs-project"
    }
}
