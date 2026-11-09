package com.afroni.pocketwright.session

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.ComponentCallbacks2
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Process
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.URLUtil
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import com.afroni.pocketwright.PocketWrightApp
import com.afroni.pocketwright.fingerprint.Fingerprint
import com.afroni.pocketwright.fingerprint.FingerprintAgent
import com.afroni.pocketwright.fingerprint.FingerprintFactory
import com.afroni.pocketwright.fingerprint.FingerprintRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.math.roundToInt

/** Une page visitée à la main, pour la page d'accueil. */
data class VisitedPage(val url: String, val title: String)

/**
 * Tous les onglets de l'app, qui sont les sessions de l'API. Ils survivent à la fin des connexions et au
 * redémarrage de l'app : leur liste est gardée dans `sessions.json`, et chacun garde son profil WebView
 * (cookies, stockage). Seule une commande de fermeture les termine.
 */
class SessionManager(private val app: Context) {

    private val sessions = ConcurrentHashMap<String, BrowserSession>()
    private val store = File(app.filesDir, "sessions.json")
    private val historyFile = File(app.filesDir, "history.json")
    private val bookmarksFile = File(app.filesDir, "bookmarks.json")
    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val main = Handler(Looper.getMainLooper())
    private val closeListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val cdpByGroup = ConcurrentHashMap<String, AtomicInteger>()
    private val externalByGroup = ConcurrentHashMap<String, AtomicInteger>()
    private var restored = false
    private var pagesStarted = false

    private val _infos = MutableStateFlow<List<SessionInfo>>(emptyList())
    val infos: StateFlow<List<SessionInfo>> = _infos.asStateFlow()

    private val _focus = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Un onglet qui doit passer au premier plan (lien ouvert dans un nouvel onglet). */
    val focusRequests: SharedFlow<String> = _focus.asSharedFlow()

    /** Posé au démarrage de l'app, une fois le localisateur DevTools créé. */
    var fingerprints: FingerprintAgent? = null

    private val _history = MutableStateFlow<List<VisitedPage>>(emptyList())
    val history: StateFlow<List<VisitedPage>> = _history.asStateFlow()

    private val _bookmarks = MutableStateFlow<List<VisitedPage>>(emptyList())
    val bookmarks: StateFlow<List<VisitedPage>> = _bookmarks.asStateFlow()

    /** L'écran de l'app est visible : l'utilisateur peut répondre à une page. */
    @Volatile var uiVisible = false
        private set

    /** Charge le moteur Chromium ; fourni par l'application. */
    internal var engineLoader: (() -> Unit)? = null

    private val _engineReady = MutableStateFlow(false)
    /** Le moteur est chargé : on peut toucher aux WebViews. */
    val engineReady: StateFlow<Boolean> = _engineReady.asStateFlow()
    private var engineLoading = false
    private var isolationKnown: Boolean? = null

    /** Les profils WebView séparent les sessions ; une WebView trop ancienne n'en a pas. */
    val isolation: Boolean
        get() {
            if (!_engineReady.value) return false
            return isolationKnown ?: runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }
                .getOrDefault(false).also { isolationKnown = it }
        }

    private val totalMemory: Long by lazy {
        val info = ActivityManager.MemoryInfo()
        app.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        info.totalMem
    }

    /** Combien d'onglets peuvent garder leur page en mémoire : peu sur un petit téléphone. */
    private val maxLive: Int get() = (totalMemory * 3 / (2 * GIGABYTE)).toInt().coerceIn(3, 16)

    private val maxCachedRenderers: Int get() = if (totalMemory < 5 * GIGABYTE) 1 else 3

    private val publishing = AtomicBoolean(false)
    private val publishTask = Runnable {
        publishing.set(false)
        publish()
    }
    private val sweep = object : Runnable {
        override fun run() {
            list().forEach { runCatching { it.updateActivity() } }
            runCatching { reapRenderers() }
            main.postDelayed(this, SWEEP_MS)
        }
    }

    /**
     * Chromium garde les processus de rendu inutilisés pour les réutiliser, un par site visité : sur un petit
     * téléphone, ils finissent par prendre toute la mémoire. Au-delà d'un petit quota, les plus anciens en cache
     * sont terminés, comme un navigateur le fait sous contrainte. Un onglet caché qui en dépendait s'endort,
     * et se recharge quand on y revient (voir `BrowserSession.onRenderProcessGone`).
     */
    private fun reapRenderers() {
        val prefix = app.packageName + ":sandboxed_process"
        val cached = app.getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty()
            .filter { it.processName.startsWith(prefix) && it.importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED }
        val excess = cached.size - maxCachedRenderers
        if (excess <= 0) return
        Log.i(TAG, "${cached.size} processus de rendu en cache, $excess terminé(s)")
        cached.sortedByDescending { it.lru }.take(excess).forEach { Process.killProcess(it.pid) }
    }

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        list().forEach { runCatching { it.updateActivity() } }
    }

    /** Charge le moteur Chromium, une seule fois, avant la première WebView. */
    @MainThread
    fun ensureEngine() {
        if (_engineReady.value || engineLoading) return
        engineLoading = true
        try {
            engineLoader?.invoke()
        } finally {
            engineLoading = false
            _engineReady.value = true
        }
    }

    fun list(): List<BrowserSession> = sessions.values.sortedBy { it.createdAt }

    fun get(id: String): BrowserSession? = sessions[id]

    fun isOpen(id: String): Boolean = sessions.containsKey(id)

    fun require(id: String): BrowserSession = get(id) ?: throw ApiException(404, "Session inconnue : $id")

    fun onClosed(listener: (String) -> Unit) {
        closeListeners += listener
    }

    fun cdpClients(group: String): Int = cdpByGroup[group]?.get() ?: 0

    fun externalCdp(group: String): Int = externalByGroup[group]?.get() ?: 0

    internal fun cdpConnected(group: String, external: Boolean) {
        cdpByGroup.getOrPut(group) { AtomicInteger() }.incrementAndGet()
        if (external) externalByGroup.getOrPut(group) { AtomicInteger() }.incrementAndGet()
    }

    internal fun cdpDisconnected(group: String, external: Boolean) {
        cdpByGroup[group]?.decrementAndGet()
        if (external) externalByGroup[group]?.decrementAndGet()
    }

    /** Reprend la liste des onglets gardée sur le disque, sans toucher aux WebViews : c'est immédiat. */
    @MainThread
    fun restore() {
        if (restored) return
        restored = true
        _history.value = loadPages(historyFile)
        _bookmarks.value = loadPages(bookmarksFile)
        load().forEach { sessions[it.id] = BrowserSession(it, this, app) }
        publish()
    }

    /**
     * Lance le moteur et rouvre les pages. Seuls les onglets les plus récents reviennent tout de suite, l'un
     * après l'autre ; les autres dorment jusqu'à leur usage. Un téléphone modeste démarre ainsi sans s'étouffer.
     */
    @MainThread
    fun startPages() {
        if (pagesStarted) return
        pagesStarted = true
        restore()
        ensureEngine()
        main.postDelayed(sweep, SWEEP_MS)
        val records = list().map { it.toRecord() }
        if (isolation) {
            // Les profils des sessions fermées, dont l'effacement n'a pas pu se faire. Celui de navigation
            // privée n'est jamais dans la liste : il disparaît à chaque démarrage.
            val keep = records.mapNotNull { it.profile }.toSet() + (PROFILE_PREFIX + BROWSER_GROUP)
            runCatching {
                ProfileStore.getInstance().allProfileNames
                    .filter { it.startsWith(PROFILE_PREFIX) && it !in keep }
                    .forEach { name -> runCatching { ProfileStore.getInstance().deleteProfile(name) } }
            }
        }
        val eager = if (totalMemory < 5 * GIGABYTE) 1 else 3
        records.sortedByDescending { it.lastActivityAt }.take(eager).forEachIndexed { index, record ->
            main.postDelayed({
                runCatching { sessions[record.id]?.wake() }.onFailure { Log.e(TAG, "Session ${record.id} non restaurée", it) }
            }, RESTORE_STAGGER_MS * index)
        }
    }

    suspend fun create(options: SessionOptions): BrowserSession = withContext(Dispatchers.Main) {
        restore()
        ensureEngine()
        val id = newId()
        val group = options.group ?: id
        val metrics = app.resources.displayMetrics
        val fingerprint = fingerprintFor(options, group)
        val identity = fingerprint.takeIf { it.identity }
        val record = SessionRecord(
            id = id,
            name = options.name ?: "Onglet ${sessions.size + 1}",
            profile = if (isolation) PROFILE_PREFIX + group else null,
            url = options.url?.let(BrowserSession::normalizeUrl) ?: "about:blank",
            userAgent = options.userAgent,
            viewportWidth = options.viewportWidth ?: identity?.screenWidth ?: (metrics.widthPixels / metrics.density).roundToInt(),
            viewportHeight = options.viewportHeight ?: identity?.viewportHeight ?: (metrics.heightPixels / metrics.density).roundToInt(),
            createdAt = System.currentTimeMillis(),
            group = group,
            origin = options.origin,
            incognito = options.incognito,
            fingerprint = fingerprint,
        )
        val session = BrowserSession(record, this@SessionManager, app)
        // Enregistrée après l'ouverture : une WebView absente (mise à jour en cours) ne laisse pas d'onglet fantôme.
        session.open()
        sessions[id] = session
        makeRoom(except = session)
        persist()
        publish()
        session.awaitFingerprint()
        session
    }

    /**
     * Les onglets d'un même groupe partagent cookies et processus : ils partagent aussi leur empreinte. Chaque
     * session de l'API a la sienne ; les onglets ouverts à la main gardent l'appareil réel, avec un bruit propre.
     */
    private fun fingerprintFor(options: SessionOptions, group: String): Fingerprint {
        sessions.values.firstOrNull { it.group == group }?.fingerprint?.let { return it }
        val request = options.fingerprint
        val manual = options.origin != TabOrigin.API
        if (manual && !request.explicit) return Fingerprint.noiseOnly(if (options.incognito) Random.nextLong() else PocketWrightApp.instance.settings.browsingNoiseSeed)
        if (request.disabled || (options.userAgent != null && !request.explicit)) return Fingerprint.noiseOnly()
        return FingerprintFactory.create(request, takenFingerprints())
    }

    private fun takenFingerprints(except: String? = null): Set<String> =
        sessions.values.filter { it.group != except }.mapNotNull { it.fingerprint?.takeIf(Fingerprint::identity)?.signature }.toSet()

    val engineVersion: String get() = PocketWrightApp.instance.browser.version
    val engineMajor: Int get() = PocketWrightApp.instance.browser.major

    /** Applique l'empreinte au moteur, puis appelle [then] sur le fil principal, même en cas d'échec. */
    internal fun applyFingerprint(session: BrowserSession, then: () -> Unit) {
        PocketWrightApp.instance.background.launch {
            try {
                withTimeout(FINGERPRINT_TIMEOUT_MS) { fingerprints?.apply(session) }
            } catch (e: CancellationException) {
                if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                Log.w(TAG, "Empreinte de ${session.id} : délai dépassé")
            } catch (e: Exception) {
                Log.w(TAG, "Empreinte de ${session.id} non appliquée : ${e.message}")
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { then() }
            }
        }
    }

    /** Change l'empreinte à la volée, pour tous les onglets de la session. */
    suspend fun setFingerprint(session: BrowserSession, request: FingerprintRequest, reload: Boolean): Fingerprint = withContext(Dispatchers.Main) {
        val next = FingerprintFactory.create(request, takenFingerprints(except = session.group))
        val previous = session.fingerprint
        val tabs = sessions.values.filter { it.group == session.group }
        tabs.forEach { it.replaceFingerprint(next) }
        for (tab in tabs.filter { it.live }) {
            fingerprints?.apply(tab)
            runCatching { fingerprints?.restartServiceWorkers(tab) }
            // Une hauteur choisie par le client ne bouge pas ; celle qui venait de l'ancien appareil, si.
            if (previous?.identity == true && tab.viewportHeight == previous.viewportHeight) runCatching { tab.setViewport(tab.viewportWidth, next.viewportHeight) }
            if (reload) runCatching { tab.reload(RELOAD_TIMEOUT_MS, true) }
        }
        tabs.forEach { changed(it) }
        persist()
        next
    }


    /** Une page ouvre une fenêtre : un nouvel onglet dans son groupe, branché sur la demande de la page. */
    @MainThread
    fun openPopup(opener: BrowserSession, transport: Message, focus: Boolean): Boolean = runCatching {
        val id = newId()
        val record = SessionRecord(
            id = id,
            name = "Onglet ${sessions.size + 1}",
            profile = opener.profile,
            url = "about:blank",
            userAgent = opener.toRecord().userAgent,
            viewportWidth = opener.viewportWidth,
            viewportHeight = opener.viewportHeight,
            createdAt = System.currentTimeMillis(),
            group = opener.group,
            fingerprint = opener.fingerprint,
            // Une popup d'une session pilotée reste pilotée : elle n'a rien à faire dans l'historique de l'utilisateur.
            origin = if (opener.origin == TabOrigin.API) TabOrigin.API else TabOrigin.POPUP,
            incognito = opener.incognito,
        )
        val session = BrowserSession(record, this, app)
        sessions[id] = session
        session.open(transport)
        makeRoom(except = session)
        persist()
        publish()
        if (focus) _focus.tryEmit(id)
        true
    }.getOrElse {
        Log.e(TAG, "Popup impossible", it)
        false
    }

    suspend fun rename(session: BrowserSession, name: String) {
        if (name.isBlank()) throw ApiException(400, "Nom vide")
        session.name = name.trim()
        changed(session, persist = true)
    }

    /** Ferme l'onglet pour de bon : sa WebView, sa place dans la liste, et son profil s'il était le dernier. */
    suspend fun close(id: String): Boolean = withContext(Dispatchers.Main) {
        val session = sessions.remove(id) ?: return@withContext false
        session.destroy()
        persist()
        publish()
        closeListeners.forEach { runCatching { it(id) } }
        // Ses processus de rendu sont désormais en trop : on n'attend pas le prochain passage.
        main.postDelayed({ runCatching { reapRenderers() } }, 800)
        // Le profil des onglets normaux reste : il porte les connexions de l'utilisateur, comme dans un navigateur.
        session.profile?.takeIf { profile -> profile != PROFILE_PREFIX + BROWSER_GROUP && sessions.values.none { it.profile == profile } }?.let { profile ->
            // Le profil se libère un peu après la destruction de la WebView ; sinon, au prochain démarrage.
            main.postDelayed({
                runCatching { if (sessions.values.none { it.profile == profile }) ProfileStore.getInstance().deleteProfile(profile) }
            }, 1_500)
        }
        true
    }

    /** Ferme un onglet depuis un rappel de la WebView (`window.close()`), sans la détruire pendant l'appel. */
    fun closeLater(id: String) {
        main.post { io.launch { runCatching { close(id) } } }
    }

    suspend fun closeAll(): Int = list().count { close(it.id) }

    /** La mémoire manque : les onglets sans témoin s'endorment, du moins récent au plus récent. */
    @MainThread
    fun trimMemory(level: Int) {
        val keep = when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> 0
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> 1
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> 3
            else -> return
        }
        val idle = list().filter(::discardable).sortedByDescending { it.lastActivityAt }.drop(keep)
        if (idle.isNotEmpty()) Log.i(TAG, "Mémoire basse (niveau $level) : ${idle.size} onglet(s) endormi(s)")
        idle.forEach { runCatching { it.discard() } }
    }

    @MainThread
    private fun makeRoom(except: BrowserSession) {
        val live = list().filter { it.live }
        val excess = live.size - maxLive
        if (excess <= 0) return
        list().filter { it !== except && discardable(it) }.sortedBy { it.lastActivityAt }.take(excess)
            .forEach { runCatching { it.discard() } }
    }

    private fun discardable(s: BrowserSession) = s.live && !s.shown && s.clients.get() == 0 && externalCdp(s.group) == 0

    internal fun changed(session: BrowserSession, persist: Boolean = false) {
        if (!sessions.containsKey(session.id)) return
        // Le chargement d'une page envoie des dizaines de signaux : l'écran n'a pas à se redessiner pour chacun.
        if (publishing.compareAndSet(false, true)) main.postDelayed(publishTask, PUBLISH_MS)
        if (persist) persist()
    }

    /** Retient les pages visitées à la main ; les pages pilotées par l'API restent hors de l'historique. */
    internal fun pageVisited(session: BrowserSession, url: String, title: String) {
        if (session.origin == TabOrigin.API || session.incognito || !url.startsWith("http")) return
        val updated = (listOf(VisitedPage(url, title)) + _history.value.filter { it.url != url }).take(MAX_HISTORY)
        _history.value = updated
        savePages(historyFile, updated)
    }

    fun removeFromHistory(url: String) {
        val updated = _history.value.filter { it.url != url }
        _history.value = updated
        savePages(historyFile, updated)
    }

    fun isBookmarked(url: String): Boolean = _bookmarks.value.any { it.url == url }

    fun toggleBookmark(url: String, title: String) {
        val updated = if (isBookmarked(url)) _bookmarks.value.filter { it.url != url } else listOf(VisitedPage(url, title)) + _bookmarks.value
        _bookmarks.value = updated
        savePages(bookmarksFile, updated)
    }

    private fun savePages(file: File, pages: List<VisitedPage>) {
        io.launch {
            runCatching {
                file.writeText(JsonArray(pages.map { page ->
                    buildJsonObject { put("url", page.url); put("title", page.title) }
                }).toString())
            }
        }
    }

    fun clearHistory() {
        _history.value = emptyList()
        io.launch { runCatching { historyFile.delete() } }
    }

    private fun loadPages(file: File): List<VisitedPage> = runCatching {
        if (!file.exists()) return@runCatching emptyList()
        Json.parseToJsonElement(file.readText()).jsonArray.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            VisitedPage(o.string("url") ?: return@mapNotNull null, o.string("title").orEmpty())
        }
    }.getOrDefault(emptyList())

    /** Un fichier venu de la page elle-même (`data:` ou `blob:`), rangé dans les Téléchargements. */
    fun saveDataUrl(dataUrl: String, name: String) {
        io.launch {
            runCatching {
                val comma = dataUrl.indexOf(',')
                val meta = dataUrl.substring(5, comma)
                val body = dataUrl.substring(comma + 1)
                val bytes = if (meta.endsWith(";base64")) Base64.decode(body, Base64.DEFAULT) else Uri.decode(body).toByteArray()
                val mime = meta.substringBefore(';').ifBlank { "application/octet-stream" }
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                    }
                    val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Écriture refusée")
                    app.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                } else {
                    @Suppress("DEPRECATION")
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name).writeBytes(bytes)
                }
                name
            }.onSuccess { main.post { Toast.makeText(app, "Téléchargé : $it", Toast.LENGTH_SHORT).show() } }
                .onFailure {
                    Log.w(TAG, "Fichier non enregistré", it)
                    main.post { Toast.makeText(app, "Téléchargement impossible", Toast.LENGTH_SHORT).show() }
                }
        }
    }

    /** Un téléchargement lancé par l'utilisateur, confié au gestionnaire d'Android. */
    fun download(url: String, userAgent: String?, disposition: String?, mimeType: String?, cookie: String?) {
        runCatching {
            if (!url.startsWith("http")) throw IllegalArgumentException("Adresse non téléchargeable")
            val name = URLUtil.guessFileName(url, disposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setMimeType(mimeType)
                .addRequestHeader("User-Agent", userAgent.orEmpty())
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            cookie?.let { request.addRequestHeader("Cookie", it) }
            app.getSystemService(DownloadManager::class.java).enqueue(request)
            Toast.makeText(app, "Téléchargement : $name", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Log.w(TAG, "Téléchargement impossible", it)
            Toast.makeText(app, "Téléchargement impossible", Toast.LENGTH_SHORT).show()
        }
    }

    /** Un lien `tel:`, `mailto:`, `intent:`… : confié à une autre app, sans lui laisser choisir un composant privé. */
    fun openExternal(url: String) {
        runCatching {
            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
        }.onFailure { Toast.makeText(app, "Aucune app pour ouvrir ce lien", Toast.LENGTH_SHORT).show() }
    }

    private fun newId(): String {
        var id: String
        do id = UUID.randomUUID().toString().substring(0, 8) while (sessions.containsKey(id))
        return id
    }

    private fun publish() {
        _infos.value = list().map { it.info() }
    }

    private fun persist() {
        val snapshot = JsonArray(list().filter { !it.incognito }.map { it.toRecord().toJson() })
        io.launch {
            runCatching {
                val tmp = File(store.parentFile, store.name + ".tmp")
                tmp.writeText(snapshot.toString())
                if (!tmp.renameTo(store)) store.writeText(snapshot.toString())
            }.onFailure { Log.e(TAG, "Sessions non enregistrées", it) }
        }
    }

    private fun load(): List<SessionRecord> = runCatching {
        if (!store.exists()) return emptyList()
        Json.parseToJsonElement(store.readText()).jsonArray.mapNotNull { (it as? JsonObject)?.let(SessionRecord::fromJson) }
    }.getOrElse {
        Log.e(TAG, "sessions.json illisible", it)
        emptyList()
    }

    companion object {
        private const val TAG = "PocketWright"
        private const val FINGERPRINT_TIMEOUT_MS = 8_000L
        private const val RELOAD_TIMEOUT_MS = 30_000L
        const val PROFILE_PREFIX = "pw-"
        /** Le groupe des onglets ouverts à la main : un seul profil, les connexions valent pour tous. */
        const val BROWSER_GROUP = "browser"
        const val INCOGNITO_GROUP = "incognito"
        private const val RESTORE_STAGGER_MS = 900L
        private const val PUBLISH_MS = 80L
        private const val SWEEP_MS = 10_000L
        private const val MAX_HISTORY = 300
        private const val GIGABYTE = 1024L * 1024 * 1024
    }
}
