package com.afroni.pocketwright.session

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.print.PrintDocumentAdapter
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log
import androidx.annotation.MainThread
import androidx.webkit.ProfileStore
import androidx.webkit.ScriptHandler
import com.afroni.pocketwright.fingerprint.Fingerprint
import com.afroni.pocketwright.fingerprint.FingerprintScript
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt

/** Une boîte alert/confirm/prompt ouverte par la page, en attente d'une réponse. */
class PageDialog internal constructor(
    val kind: String,
    val message: String,
    val defaultValue: String,
    private val onAnswer: (Boolean, String?) -> Unit,
) {
    private val done = AtomicBoolean(false)
    val answered: Boolean get() = done.get()

    fun answer(accept: Boolean, text: String? = null) {
        if (done.compareAndSet(false, true)) onAnswer(accept, text)
    }
}

/** Une demande de fichier de la page (`<input type=file>`), que l'écran transmet au sélecteur d'Android. */
class FileRequest internal constructor(
    val params: WebChromeClient.FileChooserParams,
    private val callback: ValueCallback<Array<Uri>>,
) {
    private val done = AtomicBoolean(false)

    fun finish(uris: Array<Uri>?) {
        if (done.compareAndSet(false, true)) callback.onReceiveValue(uris)
    }
}

/** Un site demande un accès (caméra, micro, position, contenu protégé) : l'écran pose la question. */
class PagePermission internal constructor(
    val origin: String,
    val kinds: List<String>,
    private val onResult: (Boolean) -> Unit,
) {
    private val done = AtomicBoolean(false)

    fun finish(allow: Boolean) {
        if (done.compareAndSet(false, true)) onResult(allow)
    }

    companion object {
        const val GEOLOCATION = "geolocation"
    }
}

/** Une vidéo en plein écran. */
class FullscreenRequest internal constructor(val view: View, val callback: WebChromeClient.CustomViewCallback)

data class FindState(val query: String, val active: Int = 0, val total: Int = 0)

/**
 * Un onglet du navigateur, qui est aussi une session de l'API : une WebView avec son profil, donc ses
 * propres cookies et son stockage (partagés avec les onglets de son groupe, comme les popups qu'il ouvre).
 *
 * La WebView vit hors de l'écran, à la taille du viewport de la session ; l'écran du navigateur l'emprunte
 * pour l'afficher. Un onglet peut être endormi (pas de WebView, voir [live]) pour libérer la mémoire : il se
 * réveille à sa dernière adresse dès qu'une commande ou l'écran le demande.
 */
class BrowserSession internal constructor(
    private val record: SessionRecord,
    private val manager: SessionManager,
    private val appContext: Context,
) {
    val id: String get() = record.id
    val createdAt: Long get() = record.createdAt
    val profile: String? get() = record.profile
    val group: String get() = record.group
    val origin: String get() = record.origin
    val incognito: Boolean get() = record.incognito

    @Volatile var name: String = record.name
        internal set

    private val context = MutableContextWrapper(appContext)
    private val main = Handler(Looper.getMainLooper())
    private var view: WebView? = null

    /** Le nom du pont JavaScript : propre à la session, pour ne pas signaler l'automatisation aux pages. */
    private val bridgeName = "_" + UUID.randomUUID().toString().replace("-", "").take(14)

    private val commands = Mutex()
    private val captures = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    @Volatile private var navigation: Navigation? = null
    private var crashes = emptyList<Long>()
    private var currentDialog: PageDialog? = null

    @Volatile var url: String = record.url; private set
    @Volatile var title: String = record.title; private set
    @Volatile var loading: Boolean = false; private set
    @Volatile var progress: Int = 0; private set
    @Volatile var lastError: String? = null; private set
    @Volatile var lastAction: String? = null; private set
    @Volatile var lastActivityAt: Long = record.lastActivityAt; private set
    @Volatile var viewportWidth: Int = record.viewportWidth; private set
    @Volatile var viewportHeight: Int = record.viewportHeight; private set
    @Volatile var shown: Boolean = false; private set
    @Volatile var closed: Boolean = false; private set
    @Volatile var desktop: Boolean = record.desktop; private set
    @Volatile var canGoBack: Boolean = false; private set
    @Volatile var canGoForward: Boolean = false; private set

    /** Augmente quand la WebView est recréée : l'écran et la passerelle CDP doivent la reprendre. */
    @Volatile var generation: Int = 0; private set

    /** La cible DevTools de la page, et la génération de WebView qu'elle désigne. */
    @Volatile internal var cdpTarget: Pair<String, Int>? = null

    /** L'empreinte de la session, commune à tous les onglets de son groupe. */
    @Volatile internal var fingerprint: Fingerprint? = record.fingerprint
    private var fingerprintScript: ScriptHandler? = null

    /** Terminé quand la page peut charger : l'empreinte est en place avant la première requête. */
    @Volatile private var fingerprintReady: CompletableDeferred<Unit>? = null

    @Volatile var thumbnail: Bitmap? = null; private set
    @Volatile private var thumbnailVersion = 0
    @Volatile private var thumbnailStale = true
    private var hostActivity: WeakReference<Activity>? = null

    @Volatile private var tint: Int? = null
    private var resumed = true
    private val runningCommands = AtomicInteger(0)
    @Volatile private var lastCommandAt = System.currentTimeMillis()
    private var scrollDrift = 0

    private val _barsCollapsed = MutableStateFlow(false)
    /** La page défile vers le bas : la barre se réduit pour lui laisser la place. */
    val barsCollapsed: StateFlow<Boolean> = _barsCollapsed.asStateFlow()
    @Volatile var favicon: Bitmap? = null; private set
    @Volatile private var faviconVersion = 0

    /** `accept` ou `dismiss` : la réponse par défaut aux boîtes de dialogue quand personne ne s'en charge. */
    @Volatile var dialogMode: String = "accept"
    @Volatile var promptText: String? = null

    /** La géolocalisation accordée par `context.grantPermissions`. */
    @Volatile internal var geolocationGranted = false

    /** Les clients WebSocket et CDP extérieurs attachés à la session (le moteur de l'app n'en est pas un). */
    val clients = AtomicInteger(0)
    val closedSignal = CompletableDeferred<Unit>()
    val events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val _dialog = MutableStateFlow<PageDialog?>(null)
    val dialog: StateFlow<PageDialog?> = _dialog.asStateFlow()
    private val _fileRequest = MutableStateFlow<FileRequest?>(null)
    val fileRequest: StateFlow<FileRequest?> = _fileRequest.asStateFlow()
    private val _fullscreen = MutableStateFlow<FullscreenRequest?>(null)
    val fullscreen: StateFlow<FullscreenRequest?> = _fullscreen.asStateFlow()
    private val _find = MutableStateFlow<FindState?>(null)
    val find: StateFlow<FindState?> = _find.asStateFlow()
    private val _permission = MutableStateFlow<PagePermission?>(null)
    val permission: StateFlow<PagePermission?> = _permission.asStateFlow()
    private val _pressTarget = MutableStateFlow<PressTarget?>(null)
    val pressTarget: StateFlow<PressTarget?> = _pressTarget.asStateFlow()

    /** Le jeton du téléchargement `blob:` en cours : seule cette réponse-là peut écrire un fichier. */
    @Volatile private var blobToken: String? = null

    private val log = ArrayDeque<LogEntry>()
    @Volatile private var logVersion = 0

    val live: Boolean get() = view != null

    private class Navigation(val done: CompletableDeferred<Unit> = CompletableDeferred()) {
        @Volatile var started = false
    }

    private val userPresent: Boolean get() = shown && manager.uiVisible

    /** Qui répondra à une boîte de dialogue maintenant : `user`, `external` (un client CDP) ou `auto`. */
    val dialogResponder: String
        get() = when {
            userPresent -> "user"
            manager.externalCdp(group) > 0 -> "external"
            else -> "auto"
        }

    // ---------------------------------------------------------------- cycle de vie

    /** Réveille l'onglet s'il dort. */
    @MainThread
    fun wake() {
        if (closed) throw ApiException(410, "Session fermée")
        if (view == null) open()
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    @MainThread
    internal fun open(transport: Message? = null) {
        if (view != null || closed) return
        manager.ensureEngine()
        val wv = WebView(context)
        profile?.let {
            ProfileStore.getInstance().getOrCreateProfile(it)
            WebViewCompat.setProfile(wv, it)
        }
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            builtInZoomControls = true
            displayZoomControls = false
            // Une page web n'a pas à lire les fichiers de l'app ni ceux du téléphone.
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = userAgent()
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            // Sinon, la WebView annonce le nom de l'app à chaque site visité.
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(wv.settings, emptySet())
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) {
            // Les clés d'accès (passkeys) et clés de sécurité, comme dans un navigateur.
            WebSettingsCompat.setWebAuthenticationSupport(wv.settings, WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER)
        }
        cookieManager().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        wv.setBackgroundColor(Color.WHITE)
        wv.addJavascriptInterface(Bridge(), bridgeName)
        wv.webViewClient = Client()
        wv.webChromeClient = Chrome()
        wv.setFindListener { active, total, _ ->
            _find.value = _find.value?.copy(active = if (total == 0) 0 else active + 1, total = total)
        }
        wv.setDownloadListener { address, agent, disposition, mimeType, length ->
            emit("download") { put("url", address); put("mimeType", mimeType); put("length", length) }
            addLog("download", address)
            if (userPresent) startDownload(wv, address, agent, disposition, mimeType)
        }
        wv.setOnLongClickListener { onLongPress(wv) }
        wv.setOnScrollChangeListener { _, _, y, _, oldY -> onPageScrolled(y, oldY) }
        view = wv
        resumed = true
        lastCommandAt = System.currentTimeMillis()
        layoutOffscreen()
        installFingerprintScript(wv)
        val identity = fingerprint?.identity == true
        if (transport != null) {
            (transport.obj as WebView.WebViewTransport).webView = wv
            transport.sendToTarget()
            // Une popup doit se charger tout de suite : le script et l'agent utilisateur sont déjà posés.
            if (identity) manager.applyFingerprint(this) {}
        } else if (identity) {
            val target = url.ifBlank { "about:blank" }
            val ready = CompletableDeferred<Unit>()
            fingerprintReady = ready
            wv.loadUrl("about:blank")
            manager.applyFingerprint(this) {
                if (target != "about:blank" && view === wv) wv.loadUrl(target)
                ready.complete(Unit)
            }
        } else {
            wv.loadUrl(url.ifBlank { "about:blank" })
        }
        manager.changed(this)
    }

    private fun installFingerprintScript(wv: WebView) {
        val current = fingerprint ?: return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        runCatching {
            fingerprintScript = WebViewCompat.addDocumentStartJavaScript(wv, FingerprintScript.build(current, manager.engineVersion), setOf("*"))
        }.onFailure { Log.w("PocketWright", "Script d'empreinte non posé", it) }
    }

    /** Remplace l'empreinte : elle vaut pour les prochains documents, et tout de suite pour ce que règle le moteur. */
    @MainThread
    internal fun replaceFingerprint(next: Fingerprint) {
        fingerprint = next
        val wv = view ?: return
        runCatching { fingerprintScript?.remove() }
        fingerprintScript = null
        installFingerprintScript(wv)
        wv.settings.userAgentString = userAgent()
    }

    internal suspend fun awaitFingerprint() {
        fingerprintReady?.await()
    }

    /** Endort l'onglet : sa WebView part, l'adresse et les cookies restent. */
    @MainThread
    internal fun discard() {
        val wv = view ?: return
        generation++
        releaseWebView(wv)
        failPending(ApiException(409, "Onglet endormi pour libérer la mémoire"))
        navigation?.done?.cancel()
        manager.changed(this)
    }

    @MainThread
    internal fun destroy() {
        if (closed) return
        closed = true
        val gone = ApiException(410, "Session fermée")
        pending.values.forEach { it.completeExceptionally(gone) }
        pending.clear()
        navigation?.done?.completeExceptionally(gone)
        currentDialog?.answer(false)
        _dialog.value = null
        _fileRequest.value?.finish(null)
        _fileRequest.value = null
        view?.let(::releaseWebView)
        emit("closed") {}
        closedSignal.complete(Unit)
    }

    private fun releaseWebView(wv: WebView) {
        view = null
        fingerprintScript = null
        (wv.parent as? ViewGroup)?.removeView(wv)
        _fullscreen.value = null
        context.baseContext = appContext
        shown = false
        runCatching {
            wv.stopLoading()
            wv.destroy()
        }
    }

    /**
     * Le moteur de rendu est tombé : une nouvelle WebView reprend l'onglet à sa dernière adresse. Une page
     * qui le fait tomber en boucle n'est pas rechargée.
     */
    @MainThread
    private fun recover() {
        val old = view ?: return
        val parent = old.parent as? ViewGroup
        parent?.removeView(old)
        runCatching { old.destroy() }
        view = null
        failPending(ApiException(409, "Le moteur de rendu s'est arrêté"))
        navigation?.done?.completeExceptionally(ApiException(502, "Le moteur de rendu s'est arrêté"))
        loading = false
        generation++
        val now = SystemClock.uptimeMillis()
        crashes = crashes.filter { now - it < CRASH_WINDOW_MS } + now
        val failing = url
        fingerprintScript = null
        if (crashes.size >= MAX_CRASHES) {
            url = "about:blank"
            title = ""
        }
        emit("crashed") { put("url", failing) }
        addLog("error", "Moteur de rendu arrêté sur $failing")
        if (manager.isOpen(id)) {
            open()
            if (crashes.size >= MAX_CRASHES) lastError = "Cette page fait planter le moteur de rendu : $failing"
            val wv = view
            if (parent != null && wv != null) parent.addView(wv, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        manager.changed(this)
    }

    // ---------------------------------------------------------------- affichage dans l'app

    @MainThread
    fun attachTo(container: ViewGroup, activity: Context) {
        if (closed) return
        wake()
        val wv = view ?: return
        if (wv.parent === container) return
        (wv.parent as? ViewGroup)?.removeView(wv)
        context.baseContext = activity
        container.addView(wv, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        hostActivity = (activity as? Activity)?.let { WeakReference(it) }
        shown = true
        _barsCollapsed.value = false
        lastActivityAt = System.currentTimeMillis()
        updateActivity()
        readPageTint(wv)
        manager.changed(this)
    }

    @MainThread
    fun detachFrom(container: ViewGroup) {
        val wv = view ?: return
        if (wv.parent === container) {
            captureThumbnail()
            container.removeView(wv)
        }
        if (wv.parent != null) return
        context.baseContext = appContext
        shown = false
        hostActivity = null
        layoutOffscreen()
        updateActivity()
        manager.changed(this)
    }

    /**
     * Un onglet que personne ne regarde ni ne pilote se met en pause, comme dans un navigateur : ses
     * animations et son rendu s'arrêtent. Sinon, chaque onglet ouvert continuerait de prendre du processeur.
     */
    @MainThread
    internal fun updateActivity() {
        val wv = view ?: return
        // Un onglet de l'utilisateur s'endort vite ; une session d'automatisation peut attendre quelque chose.
        val grace = if (origin == TabOrigin.API) ACTIVE_AFTER_API_COMMAND_MS else ACTIVE_AFTER_COMMAND_MS
        val active = (shown && manager.uiVisible) || clients.get() > 0 || runningCommands.get() > 0 ||
            System.currentTimeMillis() - lastCommandAt < grace
        if (active == resumed) return
        resumed = active
        if (active) wv.onResume() else wv.onPause()
    }

    /** Le temps d'une commande, l'onglet est éveillé et rendu actif. */
    suspend fun <T> whileActive(block: suspend () -> T): T {
        runningCommands.incrementAndGet()
        try {
            withContext(Dispatchers.Main) {
                wake()
                updateActivity()
            }
            return block()
        } finally {
            runningCommands.decrementAndGet()
            lastCommandAt = System.currentTimeMillis()
        }
    }

    private fun onPageScrolled(y: Int, oldY: Int) {
        if (!shown) return
        val dy = y - oldY
        if ((dy > 0) != (scrollDrift > 0)) scrollDrift = 0
        scrollDrift += dy
        when {
            y <= SCROLL_TOP_PX -> _barsCollapsed.value = false
            scrollDrift > SCROLL_DRIFT_PX -> _barsCollapsed.value = true
            scrollDrift < -SCROLL_DRIFT_PX -> _barsCollapsed.value = false
        }
    }

    @MainThread
    fun expandBars() {
        _barsCollapsed.value = false
    }

    /** La couleur annoncée par le site, sinon celle de son fond : le haut de l'écran la reprend. */
    private fun readPageTint(wv: WebView) {
        wv.evaluateJavascript(
            "(function(){var m=document.querySelector('meta[name=\"theme-color\"]');" +
                "if(m&&m.content)return m.content;var b=document.body&&getComputedStyle(document.body).backgroundColor;" +
                "return b&&b!=='rgba(0, 0, 0, 0)'?b:getComputedStyle(document.documentElement).backgroundColor;})()",
        ) { raw ->
            val parsed = parseCssColor(raw?.trim('"').orEmpty())
            if (parsed != tint) {
                tint = parsed
                manager.changed(this)
            }
        }
    }

    @MainThread
    private fun layoutOffscreen() {
        val wv = view ?: return
        if (wv.parent != null) return
        val density = appContext.resources.displayMetrics.density
        layoutAt(wv, (viewportWidth * density).roundToInt(), (viewportHeight * density).roundToInt())
    }

    @MainThread
    private fun layoutAt(wv: WebView, widthPx: Int, heightPx: Int) {
        if (wv.parent != null) return
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        wv.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        wv.layout(0, 0, w, h)
    }

    /**
     * Une petite image de la page, pour la grille des onglets. À l'écran, elle est copiée de la fenêtre, sans
     * rien redessiner ; hors de l'écran, on dessine la WebView, ce qui coûte : on ne le fait qu'à la demande.
     */
    @MainThread
    fun captureThumbnail() {
        val wv = view ?: return
        if (wv.width <= 0 || wv.height <= 0) return
        val activity = hostActivity?.get()
        val height = (THUMBNAIL_WIDTH * THUMBNAIL_RATIO).roundToInt()
        runCatching {
            if (shown && activity != null && wv.isAttachedToWindow) {
                val place = IntArray(2).also(wv::getLocationInWindow)
                // Le haut de la page seulement : le bas est sous la barre du navigateur.
                val visible = minOf(wv.height, (wv.width * THUMBNAIL_RATIO).roundToInt())
                val source = Rect(place[0], place[1], place[0] + wv.width, place[1] + visible)
                val bitmap = Bitmap.createBitmap(THUMBNAIL_WIDTH, height, Bitmap.Config.ARGB_8888)
                PixelCopy.request(activity.window, source, bitmap, { result ->
                    if (result == PixelCopy.SUCCESS) setThumbnail(bitmap)
                }, main)
            } else {
                val scale = THUMBNAIL_WIDTH / wv.width.toFloat()
                val bitmap = Bitmap.createBitmap(THUMBNAIL_WIDTH, height, Bitmap.Config.RGB_565)
                val canvas = Canvas(bitmap)
                canvas.scale(scale, scale)
                canvas.translate(-wv.scrollX.toFloat(), -wv.scrollY.toFloat())
                wv.draw(canvas)
                setThumbnail(bitmap)
            }
        }
    }

    private fun setThumbnail(bitmap: Bitmap) {
        thumbnail = bitmap
        thumbnailStale = false
        thumbnailVersion++
        manager.changed(this)
    }

    /** L'aperçu manque ou date d'avant le dernier chargement. */
    val needsThumbnail: Boolean get() = live && (thumbnail == null || thumbnailStale)

    // ---------------------------------------------------------------- commandes de navigateur (écran)

    /** Un appui long : sur un lien ou une image, le menu du navigateur ; ailleurs, la sélection de texte. */
    private fun onLongPress(wv: WebView): Boolean {
        if (!userPresent) return false
        val hit = wv.hitTestResult
        val extra = hit.extra
        when (hit.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> _pressTarget.value = PressTarget(extra, null)
            WebView.HitTestResult.IMAGE_TYPE -> _pressTarget.value = PressTarget(null, extra)
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                // L'image est donnée ; l'adresse du lien arrive à part.
                val reply = Handler(Looper.getMainLooper()) { message ->
                    _pressTarget.value = PressTarget(message.data.getString("url"), extra)
                    true
                }
                wv.requestFocusNodeHref(reply.obtainMessage())
            }
            else -> return false
        }
        return true
    }

    @MainThread
    fun clearPressTarget() {
        _pressTarget.value = null
    }

    @MainThread
    fun answerPermission(request: PagePermission, allow: Boolean) {
        request.finish(allow)
        if (_permission.value === request) _permission.value = null
    }

    private fun startDownload(wv: WebView, address: String, agent: String?, disposition: String?, mimeType: String?) {
        when {
            address.startsWith("data:") -> manager.saveDataUrl(address, URLUtil.guessFileName(address, disposition, mimeType))
            address.startsWith("blob:") -> {
                // Un blob n'existe que dans la page : elle seule peut le lire et nous le passer.
                val token = UUID.randomUUID().toString()
                blobToken = token
                val name = Js.str(URLUtil.guessFileName(address, disposition, mimeType))
                wv.evaluateJavascript(
                    """(async () => {
                      const blob = await (await fetch(${Js.str(address)})).blob();
                      const reader = new FileReader();
                      reader.onload = () => window.$bridgeName.saveBlob('$token', reader.result, $name);
                      reader.readAsDataURL(blob);
                    })();""",
                    null,
                )
            }
            else -> manager.download(address, agent, disposition, mimeType, cookieManager().getCookie(address))
        }
    }

    /** Pour imprimer la page ou l'enregistrer en PDF. */
    @MainThread
    fun printAdapter(jobName: String): PrintDocumentAdapter? = view?.createPrintDocumentAdapter(jobName)

    @MainThread
    fun stopLoading() {
        view?.stopLoading()
    }

    /** Une adresse saisie à la main : pas d'attente, l'écran suit le chargement. */
    @MainThread
    fun loadFromUser(address: String) {
        wake()
        lastError = null
        view?.loadUrl(address)
        addLog("nav", address)
    }

    @MainThread
    fun reloadFromUser() {
        lastError = null
        view?.reload()
    }

    @MainThread
    fun goBackFromUser(): Boolean {
        val wv = view ?: return false
        if (!wv.canGoBack()) return false
        lastError = null
        wv.goBack()
        return true
    }

    @MainThread
    fun goForwardFromUser() {
        lastError = null
        view?.goForward()
    }

    @MainThread
    fun setDesktopMode(enabled: Boolean) {
        if (desktop == enabled) return
        desktop = enabled
        view?.let {
            it.settings.userAgentString = userAgent()
            it.reload()
        }
        manager.changed(this, persist = true)
    }

    @MainThread
    fun startFind(query: String) {
        if (query.isEmpty()) {
            view?.clearMatches()
            _find.value = FindState("")
            return
        }
        _find.value = FindState(query)
        view?.findAllAsync(query)
    }

    @MainThread
    fun findNext(forward: Boolean) {
        view?.findNext(forward)
    }

    @MainThread
    fun clearFind() {
        view?.clearMatches()
        _find.value = null
    }

    @MainThread
    fun clearFileRequest() {
        _fileRequest.value = null
    }

    /** La réponse de l'utilisateur à une boîte de dialogue affichée. */
    @MainThread
    fun answerDialog(dialog: PageDialog, accept: Boolean, text: String?) {
        dialog.answer(accept, text)
        if (_dialog.value === dialog) _dialog.value = null
    }

    @MainThread
    fun exitFullscreen() {
        _fullscreen.value?.callback?.onCustomViewHidden()
        _fullscreen.value = null
    }

    fun logSnapshot(): List<LogEntry> = synchronized(log) { log.toList() }

    private fun addLog(kind: String, text: String) {
        synchronized(log) {
            log.addLast(LogEntry(System.currentTimeMillis(), kind, text.take(600)))
            while (log.size > MAX_LOG) log.removeFirst()
        }
        logVersion++
    }

    // ---------------------------------------------------------------- commandes de l'API

    /** Exécute [block] seul sur la session, en notant la commande pour l'écran. */
    suspend fun <T> exclusive(action: String, block: suspend () -> T): T = commands.withLock {
        note(action)
        block()
    }

    /** Note la commande en cours, pour l'écran. */
    fun note(action: String) {
        if (closed) throw ApiException(410, "Session fermée")
        lastAction = action
        lastActivityAt = System.currentTimeMillis()
        addLog("cmd", action)
        manager.changed(this)
    }

    suspend fun goto(target: String, timeoutMs: Long, wait: Boolean): JsonObject {
        val address = normalizeUrl(target)
        navigate(timeoutMs, wait, "chargement de $address") { it.loadUrl(address) }
        return pageInfo()
    }

    suspend fun reload(timeoutMs: Long, wait: Boolean): JsonObject {
        navigate(timeoutMs, wait, "rechargement") { it.reload() }
        return pageInfo()
    }

    suspend fun back(timeoutMs: Long, wait: Boolean): JsonObject {
        navigate(timeoutMs, wait, "retour") {
            if (!it.canGoBack()) throw ApiException(400, "Pas de page précédente")
            it.goBack()
        }
        return pageInfo()
    }

    suspend fun forward(timeoutMs: Long, wait: Boolean): JsonObject {
        navigate(timeoutMs, wait, "avance") {
            if (!it.canGoForward()) throw ApiException(400, "Pas de page suivante")
            it.goForward()
        }
        return pageInfo()
    }

    suspend fun setContent(html: String, baseUrl: String?, timeoutMs: Long): JsonObject {
        navigate(timeoutMs, true, "contenu") {
            it.loadDataWithBaseURL(baseUrl ?: "about:blank", html, "text/html", "utf-8", null)
        }
        return pageInfo()
    }

    /** Attend la fin du chargement en cours, s'il y en a un (par exemple après un clic sur un lien). */
    suspend fun waitForLoad(timeoutMs: Long): JsonObject {
        delay(150)
        within(timeoutMs, "fin du chargement") {
            while (loading) delay(100)
        }
        return pageInfo()
    }

    suspend fun evaluate(expression: String, arg: JsonElement?, timeoutMs: Long): JsonElement =
        eval(Js.evaluation(expression, arg?.toString()), timeoutMs)

    suspend fun waitFor(selector: String, state: String, timeoutMs: Long): JsonElement {
        if (state !in setOf("visible", "hidden", "attached", "detached")) {
            throw ApiException(400, "État inconnu : $state (visible, hidden, attached, detached)")
        }
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        val sel = Js.str(selector)
        while (true) {
            val probe = try {
                eval("const el = __pwFind($sel); return { attached: !!el, visible: __pwVisible(el) };", 5_000).jsonObject
            } catch (e: ApiException) {
                // La page change ou n'est pas prête : on réessaie. Une vraie erreur (sélecteur invalide) remonte.
                if (e.status != 409 && e.status != 408) throw e
                null
            }
            if (probe != null) {
                val attached = probe["attached"]?.jsonPrimitive?.boolean == true
                val visible = probe["visible"]?.jsonPrimitive?.boolean == true
                val ok = when (state) {
                    "attached" -> attached
                    "detached" -> !attached
                    "hidden" -> !visible
                    else -> visible
                }
                if (ok) return buildJsonObject { put("selector", selector); put("state", state) }
            }
            if (SystemClock.uptimeMillis() > deadline) {
                throw ApiException(408, "Délai dépassé ($timeoutMs ms) en attendant « $selector » ($state)")
            }
            delay(150)
        }
    }

    /**
     * Clique au centre de l'élément par un vrai toucher sur la WebView (comme un doigt), ou par `el.click()`
     * en JavaScript si [native] vaut false ou si l'élément est hors de la vue.
     */
    suspend fun click(selector: String, timeoutMs: Long, native: Boolean, waitForNavigation: Boolean): JsonObject {
        waitFor(selector, "visible", timeoutMs)
        val sel = Js.str(selector)
        val box = eval(
            """
            const el = __pwGet($sel);
            el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' });
            const r = el.getBoundingClientRect();
            const vv = window.visualViewport;
            const s = (vv ? vv.scale : 1) * window.devicePixelRatio;
            const ox = vv ? vv.offsetLeft : 0, oy = vv ? vv.offsetTop : 0;
            return { x: (r.left + r.width / 2 - ox) * s, y: (r.top + r.height / 2 - oy) * s, tag: el.tagName.toLowerCase() };
            """,
            timeoutMs,
        ).jsonObject
        val x = box["x"]!!.jsonPrimitive.float
        val y = box["y"]!!.jsonPrimitive.float
        val nav = if (waitForNavigation) armNavigation() else null
        val tapped = native && withContext(Dispatchers.Main) {
            val wv = requireView()
            val inside = x >= 0 && y >= 0 && x < wv.width && y < wv.height
            if (inside) tap(wv, x, y)
            inside
        }
        if (!tapped) eval("__pwGet($sel).click(); return true;", timeoutMs)
        if (nav != null) within(timeoutMs, "navigation après le clic") { nav.done.await() }
        return buildJsonObject {
            put("selector", selector)
            put("tag", box["tag"]!!)
            put("method", if (tapped) "touch" else "js")
            if (nav != null) put("url", url)
        }
    }

    suspend fun fill(selector: String, value: String, timeoutMs: Long): JsonObject {
        waitFor(selector, "attached", timeoutMs)
        eval(
            """
            const el = __pwGet(${Js.str(selector)});
            const value = ${Js.str(value)};
            el.scrollIntoView({ block: 'center', behavior: 'instant' });
            el.focus();
            if (el.isContentEditable) {
              el.textContent = value;
              el.dispatchEvent(new InputEvent('input', { bubbles: true }));
              return true;
            }
            const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype
              : el instanceof HTMLSelectElement ? HTMLSelectElement.prototype
              : el instanceof HTMLInputElement ? HTMLInputElement.prototype : null;
            if (!proto) throw new Error("L'élément n'est pas un champ : " + el.tagName.toLowerCase());
            Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, value);
            el.dispatchEvent(new Event('input', { bubbles: true }));
            el.dispatchEvent(new Event('change', { bubbles: true }));
            return true;
            """,
            timeoutMs,
        )
        return buildJsonObject { put("selector", selector); put("value", value) }
    }

    /** Tape [text] dans l'élément qui a le focus (ou dans [selector]), comme une saisie clavier. */
    suspend fun type(selector: String?, text: String, timeoutMs: Long): JsonObject {
        if (selector != null) waitFor(selector, "attached", timeoutMs)
        eval(
            """
            ${if (selector != null) "__pwGet(${Js.str(selector)}).focus();" else ""}
            const text = ${Js.str(text)};
            if (!document.execCommand('insertText', false, text)) {
              const el = document.activeElement;
              if (!el || !('value' in el)) throw new Error('Aucun champ ne peut recevoir le texte');
              el.value += text;
              el.dispatchEvent(new Event('input', { bubbles: true }));
            }
            return true;
            """,
            timeoutMs,
        )
        return buildJsonObject { put("text", text) }
    }

    /** Appuie sur une touche : `Enter`, `Tab`, `ArrowDown`, `a`, `Control+A`… */
    suspend fun press(key: String, selector: String?, timeoutMs: Long): JsonObject {
        if (selector != null) {
            waitFor(selector, "attached", timeoutMs)
            eval("__pwGet(${Js.str(selector)}).focus(); return true;", timeoutMs)
        }
        val parts = key.split('+').filter { it.isNotEmpty() }.ifEmpty { listOf("+") }
        var meta = 0
        parts.dropLast(1).forEach {
            meta = meta or when (it.lowercase()) {
                "shift" -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
                "control", "ctrl" -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
                "alt" -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
                "meta" -> KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
                else -> throw ApiException(400, "Modificateur inconnu : $it")
            }
        }
        val name = parts.last()
        val code = KEYS[name] ?: KEYS[name.lowercase().replaceFirstChar { it.uppercase() }]
        withContext(Dispatchers.Main) {
            val wv = requireView()
            if (code != null) {
                val t = SystemClock.uptimeMillis()
                wv.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
                wv.dispatchKeyEvent(KeyEvent(t, t + 30, KeyEvent.ACTION_UP, code, 0, meta))
            } else if (name.length == 1) {
                val keys = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(name.toCharArray())
                    ?: throw ApiException(400, "Touche impossible à taper : $name")
                keys.forEach {
                    wv.dispatchKeyEvent(KeyEvent(it.downTime, it.eventTime, it.action, it.keyCode, it.repeatCount, it.metaState or meta))
                }
            } else {
                throw ApiException(400, "Touche inconnue : $name (${KEYS.keys.joinToString()})")
            }
        }
        return buildJsonObject { put("key", key) }
    }

    suspend fun scroll(selector: String?, x: Int, y: Int, to: String?, timeoutMs: Long): JsonObject {
        val body = when {
            selector != null -> "__pwGet(${Js.str(selector)}).scrollIntoView({ block: 'center', behavior: 'instant' });"
            to == "top" -> "window.scrollTo(0, 0);"
            to == "bottom" -> "window.scrollTo(0, document.documentElement.scrollHeight);"
            else -> "window.scrollBy($x, $y);"
        }
        return eval("$body return { x: window.scrollX, y: window.scrollY };", timeoutMs).jsonObject
    }

    suspend fun content(timeoutMs: Long): JsonElement = eval(
        """
        const dt = document.doctype ? new XMLSerializer().serializeToString(document.doctype) : '';
        return dt + (document.documentElement ? document.documentElement.outerHTML : '');
        """,
        timeoutMs,
    )

    suspend fun text(selector: String?, timeoutMs: Long): JsonElement =
        eval("return (${selector?.let { "__pwGet(${Js.str(it)})" } ?: "document.body"} || {}).innerText || '';", timeoutMs)

    suspend fun html(selector: String, timeoutMs: Long): JsonElement =
        eval("return __pwGet(${Js.str(selector)}).outerHTML;", timeoutMs)

    suspend fun attribute(selector: String, attr: String, timeoutMs: Long): JsonElement =
        eval("return __pwGet(${Js.str(selector)}).getAttribute(${Js.str(attr)});", timeoutMs)

    /** Les éléments qui correspondent à un sélecteur CSS, résumés. */
    suspend fun queryAll(selector: String, limit: Int, timeoutMs: Long): JsonElement = eval(
        """
        return Array.from(document.querySelectorAll(${Js.str(selector)})).slice(0, $limit).map((el) => ({
          tag: el.tagName.toLowerCase(),
          id: el.id || null,
          class: el.className && typeof el.className === 'string' ? el.className : null,
          text: (el.innerText || el.textContent || '').trim().slice(0, 300),
          href: el.href || null,
          value: 'value' in el ? el.value : null,
          visible: __pwVisible(el),
        }));
        """,
        timeoutMs,
    )

    /** Une zone du document, en pixels CSS. */
    data class Clip(val x: Double, val y: Double, val width: Double, val height: Double)

    /** Capture native : la vue (par défaut), un élément ([selector]) ou toute la page ([fullPage]). */
    suspend fun screenshot(format: String, quality: Int, fullPage: Boolean = false, selector: String? = null, timeoutMs: Long = 30_000): ByteArray {
        val clip = selector?.let {
            waitFor(it, "visible", timeoutMs)
            val r = eval(
                """
                const el = __pwGet(${Js.str(it)});
                el.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'instant' });
                const r = el.getBoundingClientRect();
                return { x: r.left + window.scrollX, y: r.top + window.scrollY, width: r.width, height: r.height };
                """,
                timeoutMs,
            ).jsonObject
            Clip(r.num("x"), r.num("y"), r.num("width"), r.num("height"))
        }
        return capture(clip, null, beyondViewport = fullPage, format = format, quality = quality)
    }

    /**
     * La page dessinée par l'app elle-même. Hors de l'écran, la WebView n'a pas de compositeur : le
     * `Page.captureScreenshot` de DevTools n'y aboutit pas, et la passerelle CDP répond par ceci à sa place,
     * avec la même convention : [clip] en pixels CSS du document, [outScale] l'échelle de page voulue
     * (l'image fait `clip × outScale × devicePixelRatio`), [beyondViewport] pour aller au-delà de la vue.
     * Une capture à la fois : agrandir la vue ne supporte pas d'être fait deux fois en même temps.
     */
    suspend fun capture(clip: Clip?, outScale: Double?, beyondViewport: Boolean, format: String, quality: Int): ByteArray = captures.withLock {
        try {
            withCanvasCopies { captureLocked(clip, outScale, beyondViewport, format, quality) }
        } catch (e: OutOfMemoryError) {
            throw ApiException(507, "Mémoire insuffisante pour cette capture")
        }
    }

    /**
     * Une capture ne voit pas ce que le GPU a dessiné : un canvas WebGL y resterait vide. Le temps de [block], chaque
     * canvas porte en image de fond une copie de lui-même, qui reste sous les éléments qui le recouvrent dans la page.
     */
    suspend fun <T> withCanvasCopies(block: suspend () -> T): T {
        val holder = "__" + UUID.randomUUID().toString().replace("-", "")
        val painted = runCatching { eval(Js.paintCanvases(holder), CANVAS_COPIES_TIMEOUT_MS).jsonPrimitive.boolean }.getOrDefault(false)
        try {
            return block()
        } finally {
            if (painted) runCatching { eval(Js.restoreCanvases(holder), 3_000) }
        }
    }

    private suspend fun captureLocked(clip: Clip?, outScale: Double?, beyondViewport: Boolean, format: String, quality: Int): ByteArray {
        var m = metrics()
        val restore = m
        val grow = beyondViewport && !shown
        if (grow) {
            // Toute la page tient dans une vue agrandie le temps de la capture, comme le fait Chromium.
            eval("window.scrollTo(0, 0); return true;", 5_000)
            val k = m.num("scale") * m.num("dpr")
            val cssHeight = clip?.let { it.y + it.height } ?: m.num("height")
            withContext(Dispatchers.Main) {
                val wv = requireView()
                layoutAt(wv, wv.width, (cssHeight * k).roundToInt().coerceIn(wv.height, MAX_CAPTURE_PX))
            }
            delay(500)
            m = metrics()
        }
        try {
            val image = withContext(Dispatchers.Main) {
                val wv = requireView()
                if (!grow) layoutOffscreen()
                val bmp = Bitmap.createBitmap(wv.width.coerceAtLeast(1), wv.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                // La WebView dessine son contenu à sa position de défilement.
                canvas.translate(-wv.scrollX.toFloat(), -wv.scrollY.toFloat())
                wv.draw(canvas)
                bmp
            }
            return withContext(Dispatchers.Default) {
                val final = crop(image, clip ?: if (grow) Clip(0.0, 0.0, m.num("width"), m.num("height")) else null, m, outScale)
                ByteArrayOutputStream().use { out ->
                    final.compress(compressFormat(format), quality, out)
                    if (final !== image) final.recycle()
                    image.recycle()
                    out.toByteArray()
                }
            }
        } finally {
            if (grow) {
                withContext(Dispatchers.Main) { if (!closed) layoutOffscreen() }
                runCatching { eval("window.scrollTo(${restore.num("sx")}, ${restore.num("sy")}); return true;", 5_000) }
            }
        }
    }

    private fun crop(view: Bitmap, clip: Clip?, m: JsonObject, outScale: Double?): Bitmap {
        if (clip == null) return view
        val k = m.num("scale") * m.num("dpr")
        val x = ((clip.x - m.num("left")) * k).roundToInt().coerceIn(0, view.width - 1)
        val y = ((clip.y - m.num("top")) * k).roundToInt().coerceIn(0, view.height - 1)
        val w = (clip.width * k).roundToInt().coerceIn(1, view.width - x)
        val h = (clip.height * k).roundToInt().coerceIn(1, view.height - y)
        val part = Bitmap.createBitmap(view, x, y, w, h)
        val factor = (outScale ?: m.num("scale")) * m.num("dpr") / k
        if (abs(factor - 1.0) < 0.01) return part
        val scaled = Bitmap.createScaledBitmap(part, (w * factor).roundToInt().coerceAtLeast(1), (h * factor).roundToInt().coerceAtLeast(1), true)
        if (scaled !== part) part.recycle()
        return scaled
    }

    /** Défilement, échelle et taille du document, en pixels CSS. */
    private suspend fun metrics(): JsonObject = eval(
        """
        const vv = window.visualViewport, de = document.documentElement, b = document.body;
        return {
          left: vv ? vv.pageLeft : window.scrollX, top: vv ? vv.pageTop : window.scrollY,
          sx: window.scrollX, sy: window.scrollY,
          scale: vv ? vv.scale : 1, dpr: window.devicePixelRatio || 1,
          width: Math.max(de.scrollWidth, b ? b.scrollWidth : 0),
          height: Math.max(de.scrollHeight, b ? b.scrollHeight : 0),
        };
        """,
        5_000,
    ).jsonObject

    private fun compressFormat(format: String): Bitmap.CompressFormat = when (format) {
        "jpeg", "jpg" -> Bitmap.CompressFormat.JPEG
        "webp" -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        else -> Bitmap.CompressFormat.PNG
    }

    suspend fun setViewport(width: Int, height: Int): JsonObject {
        if (width !in 100..4000 || height !in 100..8000) throw ApiException(400, "Viewport hors limites : ${width}x$height")
        withContext(Dispatchers.Main) {
            requireView()
            viewportWidth = width
            viewportHeight = height
            layoutOffscreen()
        }
        manager.changed(this, persist = true)
        return buildJsonObject { put("width", width); put("height", height); put("shown", shown) }
    }

    suspend fun cookies(forUrl: String?): JsonElement = withContext(Dispatchers.Main) {
        val raw = cookieManager().getCookie(forUrl ?: url).orEmpty()
        buildJsonArray {
            raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { pair ->
                val eq = pair.indexOf('=')
                add(buildJsonObject {
                    put("name", if (eq < 0) pair else pair.substring(0, eq))
                    put("value", if (eq < 0) "" else pair.substring(eq + 1))
                })
            }
        }
    }

    suspend fun setCookie(forUrl: String, cookie: String): JsonElement {
        val ok: Boolean = within(10_000, "écriture du cookie") {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    cookieManager().apply {
                        setCookie(forUrl, cookie) { cont.resume(it) }
                        flush()
                    }
                }
            }
        }
        return buildJsonObject { put("set", ok) }
    }

    suspend fun clearCookies(): JsonElement {
        val ok: Boolean = within(10_000, "suppression des cookies") {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Boolean> { cont -> cookieManager().removeAllCookies { cont.resume(it) } }
            }
        }
        return buildJsonObject { put("cleared", ok); put("isolated", profile != null) }
    }

    suspend fun pageInfo(): JsonObject = withContext(Dispatchers.Main) {
        buildJsonObject {
            put("url", url)
            put("title", title)
            put("loading", loading)
            lastError?.let { put("error", it) }
        }
    }

    fun info() = SessionInfo(
        id = id,
        name = name,
        url = url,
        title = title,
        loading = loading,
        progress = progress,
        lastError = lastError,
        createdAt = createdAt,
        lastActivityAt = lastActivityAt,
        lastAction = lastAction,
        clients = clients.get(),
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        isolated = profile != null,
        shown = shown,
        generation = generation,
        group = group,
        origin = origin,
        live = live,
        canGoBack = canGoBack,
        canGoForward = canGoForward,
        desktop = desktop,
        thumbnailVersion = thumbnailVersion,
        faviconVersion = faviconVersion,
        logVersion = logVersion,
        incognito = incognito,
        tint = tint,
        fingerprint = fingerprint?.toJson(manager.engineMajor),
    )

    internal fun toRecord() = record.copy(
        name = name,
        url = url,
        title = title,
        desktop = desktop,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        lastActivityAt = lastActivityAt,
        fingerprint = fingerprint,
    )

    // ---------------------------------------------------------------- pour la passerelle CDP

    /** Pose une marque temporaire dans le titre de la page, pour la reconnaître parmi les cibles DevTools. */
    @MainThread
    internal fun stamp(token: String) {
        view?.evaluateJavascript("if(window.__pwTitle===undefined)window.__pwTitle=document.title;document.title='$token'", null)
    }

    @MainThread
    internal fun unstamp() {
        view?.evaluateJavascript("if(window.__pwTitle!==undefined){document.title=window.__pwTitle;delete window.__pwTitle}", null)
    }

    /** Un client CDP a répondu à la boîte de dialogue ouverte. */
    internal fun dialogClosedRemotely() {
        main.post {
            currentDialog?.answer(false)
            currentDialog = null
            _dialog.value = null
        }
    }

    // ---------------------------------------------------------------- mécanique

    private fun userAgent(): String = fingerprint?.takeIf { it.identity }?.userAgent(manager.engineMajor) ?: record.userAgent ?: run {
        val default = WebSettings.getDefaultUserAgent(appContext)
        // Le navigateur se présente comme Chrome, le moteur qu'il est : sans le « wv » des vues intégrées.
        // Le modèle et le numéro de build du téléphone n'ont rien à faire dans l'agent utilisateur.
        val mobile = default.replace("; wv)", ")").replace(Regex("; [^;)]*Build/[^;)]*"), "").replace(Regex("Version/[\\d.]+ "), "")
        if (!desktop) return@run mobile
        val chrome = Regex("Chrome/[\\d.]+").find(mobile)?.value ?: "Chrome/120.0.0.0"
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
    }

    @MainThread
    private fun cookieManager(): CookieManager =
        profile?.let { ProfileStore.getInstance().getOrCreateProfile(it).cookieManager } ?: CookieManager.getInstance()

    /** La WebView de l'onglet, réveillée au besoin. */
    @MainThread
    private fun requireView(): WebView {
        wake()
        return view ?: throw ApiException(410, "Session fermée")
    }

    private fun armNavigation(): Navigation {
        val nav = Navigation()
        navigation?.done?.cancel()
        navigation = nav
        return nav
    }

    private suspend fun navigate(timeoutMs: Long, wait: Boolean, what: String, start: (WebView) -> Unit) {
        val nav = withContext(Dispatchers.Main) {
            val wv = requireView()
            lastError = null
            armNavigation().also { start(wv) }
        }
        if (wait) within(timeoutMs, what) { nav.done.await() }
    }

    /** Exécute [body] (le corps d'une fonction async) dans la page et rend son résultat. */
    private suspend fun eval(body: String, timeoutMs: Long): JsonElement {
        val callId = UUID.randomUUID().toString().replace("-", "")
        val result = CompletableDeferred<String>()
        pending[callId] = result
        try {
            // Le rappel peut ne jamais venir (moteur de rendu bloqué) : sans délai, la session resterait figée.
            val started = within(minOf(timeoutMs, 20_000), "démarrage du script") {
                withContext(Dispatchers.Main) {
                    val wv = requireView()
                    suspendCancellableCoroutine<String> { cont ->
                        wv.evaluateJavascript(Js.wrap(bridgeName, callId, body)) { cont.resume(it ?: "null") }
                    }
                }
            }
            when (started) {
                Js.STARTED -> Unit
                Js.NO_BRIDGE -> throw ApiException(409, "La page n'est pas prête (chargement en cours)")
                else -> throw ApiException(400, "Script JavaScript invalide (erreur de syntaxe ?)")
            }
            val payload = within(timeoutMs, "exécution du script") { result.await() }
            val obj = Json.parseToJsonElement(payload).jsonObject
            val value = obj["v"] ?: JsonNull
            if (obj["ok"]?.jsonPrimitive?.boolean == true) return value
            throw ApiException(400, "Erreur JavaScript : " + (value.jsonPrimitive.contentOrNull ?: value.toString()))
        } finally {
            pending.remove(callId)
        }
    }

    private fun failPending(error: ApiException) {
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    @MainThread
    private fun tap(wv: WebView, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN to t, MotionEvent.ACTION_UP to t + 60).forEach { (action, time) ->
            val event = MotionEvent.obtain(t, time, action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            wv.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    private fun emit(type: String, fill: JsonObjectBuilder.() -> Unit) {
        events.tryEmit(buildJsonObject {
            put("type", "event")
            put("event", type)
            put("session", id)
            put("time", System.currentTimeMillis())
            fill()
        })
    }

    private fun updateHistoryState(wv: WebView) {
        canGoBack = wv.canGoBack()
        canGoForward = wv.canGoForward()
    }

    /**
     * Qui répond à une boîte de dialogue : l'utilisateur s'il regarde l'onglet ; sinon un client CDP, à qui
     * il faut laisser la main (Playwright répond par `Page.handleJavaScriptDialog`) ; sinon le mode réglé
     * par `dialogs`. Un délai évite qu'un client muet ne bloque la page pour toujours.
     */
    private fun handleDialog(kind: String, message: String, defaultValue: String, answer: (Boolean, String?) -> Unit): Boolean {
        val dialog = PageDialog(kind, message, defaultValue, answer)
        currentDialog?.answer(false)
        currentDialog = dialog
        emit("dialog") { put("kind", kind); put("message", message); put("mode", dialogMode) }
        addLog("dialog", "$kind : $message")
        when {
            userPresent -> _dialog.value = dialog
            manager.cdpClients(group) > 0 -> main.postDelayed({ if (!dialog.answered) answerByMode(dialog) }, DIALOG_FALLBACK_MS)
            else -> answerByMode(dialog)
        }
        return true
    }

    private fun answerByMode(dialog: PageDialog) {
        if (dialogMode == "accept") dialog.answer(true, promptText ?: dialog.defaultValue) else dialog.answer(false)
        if (_dialog.value === dialog) _dialog.value = null
    }

    private inner class Bridge {
        @JavascriptInterface
        fun done(callId: String, payload: String) {
            pending.remove(callId)?.complete(payload)
        }

        /** La réponse à un téléchargement `blob:` lancé par l'app ; une page ne peut pas en inventer. */
        @JavascriptInterface
        fun saveBlob(token: String, dataUrl: String, name: String) {
            if (token != blobToken) return
            blobToken = null
            main.post { manager.saveDataUrl(dataUrl, name) }
        }
    }

    private inner class Client : WebViewClient() {
        override fun onPageStarted(view: WebView, address: String, favicon: Bitmap?) {
            if (view !== this@BrowserSession.view) return
            url = address
            loading = true
            lastError = null
            this@BrowserSession.favicon = favicon
            faviconVersion++
            navigation?.started = true
            // L'ancienne page s'en va avec ses scripts en cours, comme dans Playwright.
            failPending(ApiException(409, "Contexte d'exécution détruit par une navigation"))
            updateHistoryState(view)
            manager.changed(this@BrowserSession)
        }

        override fun onPageFinished(view: WebView, address: String) {
            if (view !== this@BrowserSession.view) return
            url = address
            title = view.title.orEmpty().takeUnless { it.startsWith(TITLE_MARK) } ?: title
            loading = false
            progress = 100
            updateHistoryState(view)
            navigation?.takeIf { it.started }?.done?.complete(Unit)
            // Les cookies sur le disque tout de suite : la session les retrouve même si l'app est tuée.
            cookieManager().flush()
            emit("load") { put("url", address); put("title", title) }
            addLog("load", address)
            manager.pageVisited(this@BrowserSession, address, title)
            manager.changed(this@BrowserSession, persist = true)
            thumbnailStale = true
            if (shown) {
                readPageTint(view)
                if (manager.uiVisible) main.postDelayed({ if (!closed && shown) captureThumbnail() }, 900)
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, address: String, isReload: Boolean) {
            if (view !== this@BrowserSession.view) return
            url = address
            updateHistoryState(view)
            manager.changed(this@BrowserSession)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase()
            if (scheme in WEB_SCHEMES) return false
            // tel:, mailto:, intent:… : une autre app, seulement sur un geste de l'utilisateur.
            val address = request.url.toString()
            addLog("nav", "Lien externe : $address")
            if (userPresent && request.hasGesture()) manager.openExternal(address)
            else emit("blocked") { put("url", address) }
            return true
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (view !== this@BrowserSession.view || !request.isForMainFrame) return
            val message = "${error.description} (${error.errorCode})"
            lastError = message
            navigation?.done?.completeExceptionally(ApiException(502, "Navigation échouée : $message"))
            emit("error") { put("url", request.url.toString()); put("message", message) }
            addLog("error", message)
            manager.changed(this@BrowserSession)
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            if (view !== this@BrowserSession.view || error.url != url) return
            lastError = "Certificat de sécurité non valide (${error.primaryError})"
            navigation?.done?.completeExceptionally(ApiException(502, "Navigation échouée : $lastError"))
            manager.changed(this@BrowserSession)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Hors du rappel : la WebView ne se détruit pas pendant qu'elle nous parle.
            if (view === this@BrowserSession.view && !closed) {
                // Un onglet que personne ne regarde s'endort simplement : il rechargera quand on y reviendra.
                val idle = !shown && clients.get() == 0 && runningCommands.get() == 0 && !detail.didCrash()
                main.post { if (idle) discard() else recover() }
            }
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (view !== this@BrowserSession.view) return
            progress = newProgress
            manager.changed(this@BrowserSession)
        }

        override fun onReceivedTitle(view: WebView, newTitle: String?) {
            if (view !== this@BrowserSession.view || newTitle?.startsWith(TITLE_MARK) == true) return
            title = newTitle.orEmpty()
            manager.changed(this@BrowserSession)
        }

        override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
            if (view !== this@BrowserSession.view) return
            favicon = icon
            faviconVersion++
            manager.changed(this@BrowserSession)
        }

        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            val level = message.messageLevel().name.lowercase()
            emit("console") {
                put("level", level)
                put("message", message.message())
                put("source", message.sourceId())
                put("line", message.lineNumber())
            }
            addLog("console", "$level : ${message.message()}")
            return true
        }

        override fun onJsAlert(view: WebView, address: String, message: String, result: JsResult): Boolean =
            handleDialog("alert", message, "") { _, _ -> result.confirm() }

        override fun onJsConfirm(view: WebView, address: String, message: String, result: JsResult): Boolean =
            handleDialog("confirm", message, "") { accept, _ -> if (accept) result.confirm() else result.cancel() }

        override fun onJsPrompt(view: WebView, address: String, message: String, defaultValue: String?, result: JsPromptResult): Boolean =
            handleDialog("prompt", message, defaultValue.orEmpty()) { accept, text ->
                if (accept) result.confirm(text ?: defaultValue.orEmpty()) else result.cancel()
            }

        override fun onJsBeforeUnload(view: WebView, address: String, message: String, result: JsResult): Boolean {
            result.confirm()
            return true
        }

        /** Une popup ou un lien `_blank` : un nouvel onglet, avec le profil de celui-ci. */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            // Pas de popup sans geste de l'utilisateur, sauf sous automatisation (`window.open` dans un script).
            if (!isUserGesture && manager.cdpClients(group) == 0) {
                emit("popup-blocked") {}
                addLog("nav", "Popup bloquée")
                return false
            }
            return manager.openPopup(this@BrowserSession, resultMsg, focus = userPresent && isUserGesture)
        }

        override fun onCloseWindow(window: WebView) {
            if (window === this@BrowserSession.view) manager.closeLater(id)
        }

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            if (!userPresent) return false
            _fileRequest.value?.finish(null)
            _fileRequest.value = FileRequest(params, callback)
            return true
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            _fullscreen.value = FullscreenRequest(view, callback)
        }

        override fun onHideCustomView() {
            _fullscreen.value = null
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            when {
                geolocationGranted -> callback.invoke(origin, true, false)
                !userPresent -> callback.invoke(origin, false, false)
                else -> _permission.value = PagePermission(origin, listOf(PagePermission.GEOLOCATION)) { allow -> callback.invoke(origin, allow, false) }
            }
        }

        override fun onGeolocationPermissionsHidePrompt() {
            _permission.value = null
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            if (!userPresent) {
                request.deny()
                return
            }
            val resources = request.resources
            _permission.value = PagePermission(request.origin.toString(), resources.toList()) { allow ->
                if (allow) request.grant(resources) else request.deny()
            }
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            _permission.value = null
        }
    }

    companion object {
        private const val MAX_CAPTURE_PX = 16_384
        private const val CANVAS_COPIES_TIMEOUT_MS = 6_000L
        private const val THUMBNAIL_WIDTH = 360
        private const val THUMBNAIL_RATIO = 1.25f
        private const val ACTIVE_AFTER_COMMAND_MS = 20_000L
        private const val ACTIVE_AFTER_API_COMMAND_MS = 5L * 60_000
        internal const val TITLE_MARK = "__pw_mark_"
        private const val SCROLL_DRIFT_PX = 40
        private const val SCROLL_TOP_PX = 8
        private const val MAX_LOG = 200
        private const val MAX_CRASHES = 3
        private const val CRASH_WINDOW_MS = 60_000L
        private const val DIALOG_FALLBACK_MS = 30_000L

        private val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript")

        private val KEYS = mapOf(
            "Enter" to KeyEvent.KEYCODE_ENTER,
            "Tab" to KeyEvent.KEYCODE_TAB,
            "Backspace" to KeyEvent.KEYCODE_DEL,
            "Delete" to KeyEvent.KEYCODE_FORWARD_DEL,
            "Escape" to KeyEvent.KEYCODE_ESCAPE,
            "Space" to KeyEvent.KEYCODE_SPACE,
            "ArrowUp" to KeyEvent.KEYCODE_DPAD_UP,
            "ArrowDown" to KeyEvent.KEYCODE_DPAD_DOWN,
            "ArrowLeft" to KeyEvent.KEYCODE_DPAD_LEFT,
            "ArrowRight" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "Home" to KeyEvent.KEYCODE_MOVE_HOME,
            "End" to KeyEvent.KEYCODE_MOVE_END,
            "PageUp" to KeyEvent.KEYCODE_PAGE_UP,
            "PageDown" to KeyEvent.KEYCODE_PAGE_DOWN,
        )

        private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
        private val HOST_PORT = Regex("^[^:/]+:\\d")
        private val BLOCKED_SCHEMES = setOf("file", "content")

        /** Lit une couleur CSS (`#rrggbb`, `#rgb`, `rgb(...)`) ; null si elle est transparente ou inconnue. */
        internal fun parseCssColor(css: String): Int? {
            val text = css.trim().lowercase()
            if (text.startsWith("#")) {
                val hex = text.drop(1)
                val full = if (hex.length == 3) hex.map { "$it$it" }.joinToString("") else hex.take(6)
                return full.toIntOrNull(16)?.let { Color.rgb(it shr 16 and 255, it shr 8 and 255, it and 255) }
            }
            val parts = Regex("[\\d.]+").findAll(text.substringAfter('(', "")).map { it.value.toFloat() }.toList()
            if (!text.startsWith("rgb") || parts.size < 3) return null
            if (parts.size > 3 && parts[3] < 0.5f) return null
            return Color.rgb(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        }

        private fun JsonObject.num(key: String): Double = this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0

        /** `example.com` devient `https://example.com` ; les adresses avec un schéma restent telles quelles. */
        fun normalizeUrl(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) throw ApiException(400, "Adresse vide")
            val address = if ("://" in trimmed || (SCHEME.containsMatchIn(trimmed) && !HOST_PORT.containsMatchIn(trimmed))) trimmed else "https://$trimmed"
            if (address.substringBefore(':').lowercase() in BLOCKED_SCHEMES) {
                throw ApiException(400, "Adresse refusée : les fichiers du téléphone ne sont pas accessibles")
            }
            return address
        }

        private suspend fun <T : Any> within(timeoutMs: Long, what: String, block: suspend () -> T): T =
            withTimeoutOrNull(timeoutMs) { block() } ?: throw ApiException(408, "Délai dépassé ($timeoutMs ms) : $what")
    }
}
