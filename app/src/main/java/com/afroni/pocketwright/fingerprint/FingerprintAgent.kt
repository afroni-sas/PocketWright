package com.afroni.pocketwright.fingerprint

import android.util.Log
import com.afroni.pocketwright.BrowserInfo
import com.afroni.pocketwright.cdp.TargetLocator
import com.afroni.pocketwright.session.ApiException
import com.afroni.pocketwright.session.BrowserSession
import com.afroni.pocketwright.session.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * Applique l'identité d'une session au moteur. Ces réglages sont natifs (donc justes dans la page, ses cadres et
 * les en-têtes HTTP), mais ne valent que tant qu'une connexion DevTools reste attachée à la page : l'agent garde
 * donc la sienne, ouverte pour toute la vie de l'onglet.
 *
 * Chaque session a son propre contexte de navigation dans Chromium, donc ses propres processus de rendu : même le
 * fuseau horaire, global à un processus, ne déborde pas d'une session à l'autre.
 */
class FingerprintAgent(
    private val manager: SessionManager,
    private val locator: TargetLocator,
    private val browser: BrowserInfo,
    private val scope: CoroutineScope,
) {
    private class Attachment(val sessionId: String, val target: String, val child: String)

    private val bySession = ConcurrentHashMap<String, Attachment>()
    private val byChild = ConcurrentHashMap<String, Attachment>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    init {
        locator.onEvent = ::onEvent
        locator.onDisconnect = {
            bySession.clear()
            byChild.clear()
            // La connexion revient d'elle-même au prochain appel : les onglets ouverts doivent retrouver leur identité.
            scope.launch {
                delay(RECONNECT_DELAY_MS)
                manager.list().filter { it.live && it.fingerprint?.identity == true }.forEach { session ->
                    runCatching { apply(session) }.onFailure { Log.w(TAG, "Empreinte de ${session.id} non rétablie : ${it.message}") }
                }
            }
        }
    }

    /** À appeler après chaque création de WebView, et quand l'empreinte change. */
    suspend fun apply(session: BrowserSession) {
        locks.getOrPut(session.id) { Mutex() }.withLock {
            val fingerprint = session.fingerprint?.takeIf { it.identity } ?: return
            val target = locator.locate(session)
            val attachment = bySession[session.id]?.takeIf { it.target == target } ?: attach(session.id, target)
            override(attachment.child, fingerprint)
        }
    }

    /** Un service worker déjà lancé garderait l'ancienne empreinte : il repart au prochain usage, avec la nouvelle. */
    suspend fun restartServiceWorkers(session: BrowserSession) {
        val child = bySession[session.id]?.child ?: return
        locator.call("ServiceWorker.enable", JsonObject(emptyMap()), child)
        locator.call("ServiceWorker.stopAllWorkers", JsonObject(emptyMap()), child)
        locator.call("ServiceWorker.disable", JsonObject(emptyMap()), child)
    }

    fun forget(sessionId: String) {
        locks.remove(sessionId)
        bySession.remove(sessionId)?.let { byChild.remove(it.child) }
    }

    private suspend fun attach(sessionId: String, target: String): Attachment {
        bySession.remove(sessionId)?.let { byChild.remove(it.child) }
        val child = (locator.call("Target.attachToTarget", buildJsonObject { put("targetId", target); put("flatten", true) })["sessionId"] as? JsonPrimitive)?.contentOrNull
            ?: throw ApiException(502, "DevTools n'a pas ouvert la cible")
        val attachment = Attachment(sessionId, target, child)
        bySession[sessionId] = attachment
        byChild[child] = attachment
        locator.call("Target.setAutoAttach", autoAttach(), child)
        return attachment
    }

    private fun autoAttach() = buildJsonObject {
        put("autoAttach", true)
        put("waitForDebuggerOnStart", true)
        put("flatten", true)
    }

    private suspend fun override(child: String, fingerprint: Fingerprint) {
        val version = browser.version
        val major = browser.major
        locator.call("Emulation.setUserAgentOverride", buildJsonObject {
            put("userAgent", fingerprint.userAgent(major))
            // Sans poids « ;q= » : Chromium les calcule, et navigator.languages reprend cette liste telle quelle.
            put("acceptLanguage", fingerprint.languages.joinToString(","))
            put("platform", FingerprintScript.PLATFORM)
            put("userAgentMetadata", buildJsonObject {
                put("brands", brands(FingerprintFactory.brands(major, major.toString())))
                put("fullVersionList", brands(FingerprintFactory.brands(major, version).map { (brand, value) -> brand to if (brand.startsWith("Not")) "$value.0.0.0" else value }))
                put("fullVersion", version)
                put("platform", "Android")
                put("platformVersion", fingerprint.platformVersion)
                put("architecture", "")
                put("model", fingerprint.model)
                put("mobile", true)
                put("bitness", "")
                put("wow64", false)
            })
        }, child)
        locator.call("Emulation.setTimezoneOverride", buildJsonObject { put("timezoneId", fingerprint.timezone.orEmpty()) }, child)
        locator.call("Emulation.setLocaleOverride", buildJsonObject { put("locale", fingerprint.locale) }, child)
        locator.call("Emulation.setHardwareConcurrencyOverride", buildJsonObject { put("hardwareConcurrency", fingerprint.cores) }, child)
        // Largeur et hauteur à 0 : la page garde la taille réelle de sa vue, seul l'écran annoncé change.
        locator.call("Emulation.setDeviceMetricsOverride", buildJsonObject {
            put("width", 0)
            put("height", 0)
            put("deviceScaleFactor", 0)
            put("mobile", true)
            put("screenWidth", fingerprint.screenWidth)
            put("screenHeight", fingerprint.screenHeight)
        }, child)
        // WebView annonce sinon le nom de l'app à chaque requête. L'en-tête ne peut pas être retiré, seulement vidé.
        locator.call("Network.enable", JsonObject(emptyMap()), child)
        locator.call("Network.setExtraHTTPHeaders", buildJsonObject {
            put("headers", buildJsonObject { put("X-Requested-With", "") })
        }, child)
    }

    private fun workerScript(fingerprint: Fingerprint) =
        buildJsonObject { put("expression", FingerprintScript.build(fingerprint, browser.version)) }

    private fun brands(list: List<Pair<String, String>>) = JsonArray(
        list.map { (brand, value) ->
            buildJsonObject {
                put("brand", brand)
                put("version", value)
            }
        },
    )

    private fun onEvent(message: JsonObject) {
        when ((message["method"] as? JsonPrimitive)?.contentOrNull) {
            "Target.attachedToTarget" -> scope.launch { onAttached(message) }
            "Target.detachedFromTarget" -> {
                val child = ((message["params"] as? JsonObject)?.get("sessionId") as? JsonPrimitive)?.contentOrNull ?: return
                byChild.remove(child)?.let { if (it.child == child) bySession.remove(it.sessionId, it) }
            }
        }
    }

    /** Un cadre ou un worker démarre, en pause : il reçoit l'empreinte avant d'exécuter quoi que ce soit. */
    private suspend fun onAttached(message: JsonObject) {
        val parent = (message["sessionId"] as? JsonPrimitive)?.contentOrNull ?: return
        val owner = byChild[parent] ?: return
        val params = message["params"] as? JsonObject ?: return
        val child = (params["sessionId"] as? JsonPrimitive)?.contentOrNull ?: return
        val type = ((params["targetInfo"] as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
        val fingerprint = manager.get(owner.sessionId)?.fingerprint
        try {
            if (fingerprint != null && fingerprint.identity) {
                when (type) {
                    // Un service worker ne répond qu'une fois repris : on envoie le script sans attendre sa réponse.
                    "service_worker" -> scope.launch {
                        runCatching { locator.call("Runtime.evaluate", workerScript(fingerprint), child) }
                    }
                    "worker", "shared_worker" -> locator.call("Runtime.evaluate", workerScript(fingerprint), child)
                    "iframe" -> {
                        byChild[child] = owner
                        override(child, fingerprint)
                        locator.call("Target.setAutoAttach", autoAttach(), child)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Empreinte non posée sur un $type : ${e.message}")
        } finally {
            runCatching { locator.call("Runtime.runIfWaitingForDebugger", JsonObject(emptyMap()), child) }
        }
    }

    private companion object {
        const val TAG = "PocketWright"
        const val RECONNECT_DELAY_MS = 1_000L
    }
}
