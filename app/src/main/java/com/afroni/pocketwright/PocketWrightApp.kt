package com.afroni.pocketwright

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.webkit.WebView
import com.afroni.pocketwright.api.Commands
import com.afroni.pocketwright.cdp.TargetLocator
import com.afroni.pocketwright.engine.PlaywrightEngine
import com.afroni.pocketwright.fingerprint.FingerprintAgent
import com.afroni.pocketwright.messaging.Messaging
import com.afroni.pocketwright.service.PocketWrightService
import com.afroni.pocketwright.session.SessionManager
import com.afroni.pocketwright.update.ChromiumEngine
import com.afroni.pocketwright.update.PlaywrightUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

sealed interface ServerStatus {
    data object Starting : ServerStatus
    data class Running(val port: Int) : ServerStatus
    data class Failed(val message: String) : ServerStatus
}

/** Ce que partagent le service, le serveur et l'écran : un seul processus, une seule liste d'onglets. */
class PocketWrightApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var sessions: SessionManager
        private set
    lateinit var engine: PlaywrightEngine
        private set
    lateinit var browser: BrowserInfo
        private set
    lateinit var commands: Commands
        private set
    lateinit var locator: TargetLocator
        private set
    lateinit var chromium: ChromiumEngine
        private set
    lateinit var playwright: PlaywrightUpdater
        private set
    lateinit var messaging: Messaging
        private set
    val serverStatus = MutableStateFlow<ServerStatus>(ServerStatus.Starting)

    /** Pour ce qui doit survivre à l'écran qui l'a lancé (un téléchargement de mise à jour). */
    val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Les processus de rendu du moteur Chromium passent aussi par ici : ils n'ont besoin de rien de tout ça.
        if (!isMainProcess()) return
        instance = this
        chromium = ChromiumEngine(this)
        playwright = PlaywrightUpdater(this)
        settings = Settings(this)
        messaging = Messaging(this)
        sessions = SessionManager(this)
        // Charger Chromium bloque le fil principal plusieurs secondes : pas ici, mais après la première image.
        sessions.engineLoader = {
            chromium.loadAtStartup()
            // Ouvre la socket DevTools des WebViews, par laquelle Playwright les pilote. Elle échoue si la
            // WebView est en cours de mise à jour : l'app doit démarrer quand même.
            runCatching { WebView.setWebContentsDebuggingEnabled(true) }.onFailure { Log.e("PocketWright", "WebView indisponible", it) }
        }
        browser = BrowserInfo(this) { sessions.engineReady.value }
        engine = PlaywrightEngine(this, API_PORT, settings.internalKey, playwright)
        commands = Commands(sessions, engine)
        locator = TargetLocator(sessions)
        sessions.fingerprints = FingerprintAgent(sessions, locator, browser, background)
        sessions.onClosed {
            engine.release(it)
            sessions.fingerprints?.forget(it)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::sessions.isInitialized) runCatching { sessions.trimMemory(level) }
    }

    /** Relance l'app : Node et le moteur Chromium ne se changent qu'au démarrage du processus. */
    fun restart() {
        Handler(Looper.getMainLooper()).postDelayed({
            // Android refuse d'ouvrir un écran depuis l'arrière-plan : sans l'alarme, plus rien ne relancerait l'API.
            PocketWrightService.scheduleRestart(this)
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(it)
            }
            Process.killProcess(Process.myPid())
        }, 300)
    }

    private fun isMainProcess(): Boolean = runCatching {
        File("/proc/self/cmdline").readBytes().takeWhile { it != 0.toByte() }.toByteArray().decodeToString() == packageName
    }.getOrDefault(true)

    companion object {
        const val API_PORT = BuildConfig.API_PORT

        lateinit var instance: PocketWrightApp
            private set
    }
}
