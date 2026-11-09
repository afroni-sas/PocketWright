package com.afroni.pocketwright.ui

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.print.PrintManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.afroni.pocketwright.PocketWrightApp
import com.afroni.pocketwright.session.ApiException
import com.afroni.pocketwright.session.BrowserSession
import com.afroni.pocketwright.session.FullscreenRequest
import com.afroni.pocketwright.session.PageDialog
import com.afroni.pocketwright.session.PagePermission
import com.afroni.pocketwright.session.PressTarget
import com.afroni.pocketwright.session.SessionInfo
import com.afroni.pocketwright.session.VisitedPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs

private val CompactDockHeight = 36.dp

/**
 * Le navigateur. La page occupe l'écran ; tout se commande depuis le bas, à portée de pouce. La barre se
 * réduit quand on descend dans la page, sans changer la taille de la page : rien à recalculer, donc pas de saccade.
 * Les commandes gardent toujours la même place (grisées si elles ne servent pas), et l'adresse reste lisible.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowserScreen(
    tab: SessionInfo?,
    tabs: List<SessionInfo>,
    engineReady: Boolean,
    onShowTabs: () -> Unit,
    onNewTab: (url: String?, incognito: Boolean, background: Boolean) -> Unit,
    onSelectTab: (String) -> Unit,
    onShowSettings: () -> Unit,
    onShowLibrary: () -> Unit,
) {
    val app = PocketWrightApp.instance
    val context = LocalContext.current
    val session = tab?.let { app.sessions.get(it.id) }
    val searchEngine by app.settings.searchEngine.collectAsStateWithLifecycle()
    var editing by remember(tab?.id) { mutableStateOf(false) }
    var typed by remember(tab?.id) { mutableStateOf("") }
    var finding by remember(tab?.id) { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var automationOpen by remember { mutableStateOf(false) }
    val collapsedFlow = remember(session) { session?.barsCollapsed ?: MutableStateFlow(false) }
    val scrolledDown by collapsedFlow.collectAsStateWithLifecycle()
    val onStartPage = tab == null || (tab.blank && !tab.loading && tab.lastError == null)
    val collapsed = scrolledDown && !editing && !finding && !onStartPage

    fun open(input: String) {
        editing = false
        val address = try {
            addressFor(input, searchEngine)
        } catch (e: ApiException) {
            Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
            return
        }
        if (session != null) session.loadFromUser(address) else onNewTab(address, false, false)
    }

    /** Glisser sur la barre d'adresse passe à l'onglet voisin. */
    fun switchTab(direction: Int) {
        val index = tabs.indexOfFirst { it.id == tab?.id }
        if (index < 0) return
        tabs.getOrNull(index + direction)?.let { onSelectTab(it.id) }
    }

    // Sans page précédente, le retour sort de l'app (les onglets continuent de tourner), comme un navigateur.
    BackHandler(enabled = editing || finding || tab?.canGoBack == true) {
        when {
            editing -> editing = false
            finding -> {
                finding = false
                session?.clearFind()
            }
            else -> session?.goBackFromUser()
        }
    }

    val pageColor = tab?.takeUnless { onStartPage }?.tint?.let { Color(it) }
    StatusBarContrast(pageColor)

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(pageColor ?: MaterialTheme.colorScheme.background))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (session != null && tab != null && engineReady) key(tab.id) { WebHost(session, tab) }
                    when {
                        onStartPage -> StartPage(tab?.incognito == true, onOpen = ::open)
                        tab?.lastError != null && !tab.loading -> ErrorPage(tab, onRetry = { session?.reloadFromUser() })
                    }
                }
                Spacer(Modifier.navigationBarsPadding().height(CompactDockHeight))
            }
            if (editing) {
                Suggestions(typed, onPick = ::open)
            }
            Dock(
                tab = tab,
                session = session,
                tabCount = tabs.size,
                collapsed = collapsed,
                editing = editing,
                finding = finding,
                onEditing = { editing = it },
                onSubmit = ::open,
                onTyped = { typed = it },
                onCloseFind = {
                    finding = false
                    session?.clearFind()
                },
                onSwitchTab = ::switchTab,
                onAutomation = { automationOpen = true },
                onNewTab = { onNewTab(null, false, false) },
                onShowTabs = onShowTabs,
                onMenu = { menuOpen = true },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (session != null) TabEffects(session, onOpenTab = { url, incognito, background -> onNewTab(url, incognito, background) })
    if (menuOpen) {
        BrowserMenu(
            tab = tab,
            session = session,
            onDismiss = { menuOpen = false },
            onNewTab = { incognito -> onNewTab(null, incognito, false) },
            onFind = { finding = true },
            onAutomation = { automationOpen = true },
            onSettings = onShowSettings,
            onLibrary = onShowLibrary,
        )
    }
    if (automationOpen && tab != null && session != null) {
        AutomationSheet(tab, session, onDismiss = { automationOpen = false })
    }
}

/** Les icônes de la barre d'état restent lisibles sur la couleur de la page. */
@Composable
private fun StatusBarContrast(pageColor: Color?) {
    val view = LocalView.current
    val dark = isSystemInDarkTheme()
    DisposableEffect(pageColor, dark) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = pageColor?.let { it.luminance() > 0.5f } ?: !dark
        onDispose { controller?.isAppearanceLightStatusBars = !dark }
    }
}

// ------------------------------------------------------------------- la page

@Composable
private fun WebHost(session: BrowserSession, tab: SessionInfo) {
    val activity = LocalContext.current
    var holder by remember { mutableStateOf<FrameLayout?>(null) }
    DisposableEffect(session) {
        onDispose { holder?.let(session::detachFrom) }
    }
    AndroidView(
        factory = { FrameLayout(it).also { container -> holder = container } },
        // Relu à chaque génération : une WebView recréée ou réveillée revient ici.
        update = { container -> if (tab.generation >= 0) session.attachTo(container, activity) },
        modifier = Modifier.fillMaxSize(),
    )
}

/** Ce qu'une page demande à l'utilisateur : boîtes de dialogue, fichiers, plein écran. */
@Composable
private fun TabEffects(session: BrowserSession, onOpenTab: (String, Boolean, Boolean) -> Unit) {
    val dialog by session.dialog.collectAsStateWithLifecycle()
    dialog?.let { PageDialogView(it, session) }

    val permission by session.permission.collectAsStateWithLifecycle()
    permission?.let { PermissionDialog(it, session) }

    val press by session.pressTarget.collectAsStateWithLifecycle()
    press?.let { PressMenu(it, session, onOpenTab) }

    val request by session.fileRequest.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        session.fileRequest.value?.finish(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        session.clearFileRequest()
    }
    LaunchedEffect(request) {
        val pending = request ?: return@LaunchedEffect
        try {
            picker.launch(pending.params.createIntent())
        } catch (e: ActivityNotFoundException) {
            pending.finish(null)
            session.clearFileRequest()
        }
    }

    val fullscreen by session.fullscreen.collectAsStateWithLifecycle()
    fullscreen?.let { FullscreenView(it, onExit = session::exitFullscreen) }
}

/** La question posée quand un site demande un accès, puis l'autorisation d'Android si elle manque. */
@Composable
private fun PermissionDialog(request: PagePermission, session: BrowserSession) {
    val needed = request.kinds.flatMap { androidPermissionsFor(it) }.distinct().toTypedArray()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        session.answerPermission(request, results.values.all { it })
    }
    AlertDialog(
        onDismissRequest = { session.answerPermission(request, false) },
        title = { Text(request.origin.removePrefix("https://").removeSuffix("/")) },
        text = { Text("Ce site souhaite accéder à : ${request.kinds.joinToString(", ", transform = ::permissionLabel)}.") },
        confirmButton = {
            TextButton(onClick = { if (needed.isEmpty()) session.answerPermission(request, true) else ask.launch(needed) }) { Text("Autoriser") }
        },
        dismissButton = { TextButton(onClick = { session.answerPermission(request, false) }) { Text("Refuser") } },
    )
}

private fun androidPermissionsFor(kind: String): List<String> = when (kind) {
    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> listOf(Manifest.permission.CAMERA)
    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> listOf(Manifest.permission.RECORD_AUDIO)
    PagePermission.GEOLOCATION -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    else -> emptyList()
}

private fun permissionLabel(kind: String): String = when (kind) {
    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "la caméra"
    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "le micro"
    PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> "la lecture de contenu protégé"
    PermissionRequest.RESOURCE_MIDI_SYSEX -> "les appareils MIDI"
    PagePermission.GEOLOCATION -> "votre position"
    else -> kind
}

/** Le menu d'un appui long sur un lien ou une image. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PressMenu(target: PressTarget, session: BrowserSession, onOpenTab: (String, Boolean, Boolean) -> Unit) {
    val context = LocalContext.current
    val app = PocketWrightApp.instance
    val dismiss = session::clearPressTarget
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.padding(bottom = 16.dp)) {
            (target.link ?: target.image)?.let {
                Mono(it, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            target.link?.let { link ->
                MenuRow(PwIcons.Add, "Ouvrir dans un nouvel onglet") {
                    dismiss()
                    onOpenTab(link, session.incognito, false)
                }
                MenuRow(PwIcons.Forward, "Ouvrir en arrière-plan") {
                    dismiss()
                    onOpenTab(link, session.incognito, true)
                }
                if (app.sessions.isolation) {
                    MenuRow(PwIcons.Incognito, "Ouvrir en navigation privée") {
                        dismiss()
                        onOpenTab(link, true, false)
                    }
                }
                MenuRow(PwIcons.Copy, "Copier le lien") {
                    dismiss()
                    copyText(context, "Lien", link)
                }
                MenuRow(PwIcons.Share, "Partager le lien") {
                    dismiss()
                    shareText(context, link)
                }
            }
            target.image?.let { image ->
                MenuRow(PwIcons.Globe, "Ouvrir l'image") {
                    dismiss()
                    onOpenTab(image, session.incognito, false)
                }
                MenuRow(PwIcons.Download, "Enregistrer l'image") {
                    dismiss()
                    app.sessions.download(image, null, null, null, null)
                }
                MenuRow(PwIcons.Copy, "Copier l'adresse de l'image") {
                    dismiss()
                    copyText(context, "Adresse", image)
                }
            }
        }
    }
}

private fun shareText(context: Context, text: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
}

@Composable
private fun PageDialogView(dialog: PageDialog, session: BrowserSession) {
    var text by remember(dialog) { mutableStateOf(dialog.defaultValue) }
    AlertDialog(
        onDismissRequest = { session.answerDialog(dialog, false, null) },
        title = { Text("Cette page indique", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(dialog.message)
                if (dialog.kind == "prompt") OutlinedTextField(text, { text = it }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { session.answerDialog(dialog, true, text) }) { Text("OK") } },
        dismissButton = {
            if (dialog.kind != "alert") TextButton(onClick = { session.answerDialog(dialog, false, null) }) { Text("Annuler") }
        },
    )
}

@Composable
private fun FullscreenView(request: FullscreenRequest, onExit: () -> Unit) {
    val activity = LocalContext.current as? Activity
    BackHandler(onBack = onExit)
    DisposableEffect(request) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onExit,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        AndroidView(
            factory = { context ->
                FrameLayout(context).apply {
                    setBackgroundColor(AndroidColor.BLACK)
                    (request.view.parent as? FrameLayout)?.removeView(request.view)
                    addView(request.view, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Dock(
    tab: SessionInfo?,
    session: BrowserSession?,
    tabCount: Int,
    collapsed: Boolean,
    editing: Boolean,
    finding: Boolean,
    onEditing: (Boolean) -> Unit,
    onSubmit: (String) -> Unit,
    onTyped: (String) -> Unit,
    onCloseFind: () -> Unit,
    onSwitchTab: (Int) -> Unit,
    onAutomation: () -> Unit,
    onNewTab: () -> Unit,
    onShowTabs: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
            AnimatedVisibility(visible = collapsed, enter = expandVertically(tween(170)), exit = shrinkVertically(tween(170))) {
                CompactAddress(tab, onClick = { session?.expandBars() })
            }
            AnimatedVisibility(visible = !collapsed, enter = expandVertically(tween(170)), exit = shrinkVertically(tween(170))) {
                Column {
                    if (finding && session != null) {
                        FindBar(session, onCloseFind)
                    } else {
                        AddressPill(tab, session, editing, onEditing, onSubmit, onTyped, onSwitchTab, onAutomation)
                        if (!editing || !WindowInsets.isImeVisible) ToolRow(tab, session, tabCount, onNewTab, onShowTabs, onMenu)
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactAddress(tab: SessionInfo?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(CompactDockHeight).clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tab?.secure == true) {
            Icon(PwIcons.Lock, contentDescription = "Connexion sécurisée", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(tab?.host.orEmpty(), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AddressPill(
    tab: SessionInfo?,
    session: BrowserSession?,
    editing: Boolean,
    onEditing: (Boolean) -> Unit,
    onSubmit: (String) -> Unit,
    onTyped: (String) -> Unit,
    onSwitchTab: (Int) -> Unit,
    onAutomation: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .pointerInput(tab?.id) {
                    var drag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { drag = 0f },
                        onDragEnd = { if (abs(drag) > 80f) onSwitchTab(if (drag < 0) 1 else -1) },
                    ) { _, amount -> drag += amount }
                },
        ) {
            Box {
                if (tab?.loading == true) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(tab.progress / 100f).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)))
                }
                if (editing) {
                    AddressField(initial = tab?.url?.takeUnless { tab.blank }.orEmpty(), onSubmit, onTyped, onCancel = { onEditing(false) })
                } else {
                    AddressDisplay(
                        tab,
                        onClick = { onEditing(true) },
                        onReload = { if (tab?.loading == true) session?.stopLoading() else session?.reloadFromUser() },
                        onAutomation = onAutomation,
                    )
                }
            }
        }
        if (editing) TextButton(onClick = { onEditing(false) }) { Text("Annuler") }
    }
}

@Composable
private fun AddressDisplay(tab: SessionInfo?, onClick: () -> Unit, onReload: () -> Unit, onAutomation: () -> Unit) {
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).fillMaxSize().clickable(onClick = onClick).padding(start = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                tab == null || tab.blank -> AddressIcon(PwIcons.Search, "Recherche")
                tab.secure -> AddressIcon(PwIcons.Lock, "Connexion sécurisée")
                else -> AddressIcon(PwIcons.Warning, "Connexion non sécurisée")
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (tab == null || tab.blank) "Rechercher ou saisir une adresse" else tab.host.ifBlank { tab.url },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = if (tab == null || tab.blank) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (tab?.incognito == true) {
            Icon(PwIcons.Incognito, contentDescription = "Navigation privée", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        if (tab?.automated == true) AutomationChip(active = tab.clients > 0, onClick = onAutomation)
        if (tab != null && !tab.blank) {
            PwIconButton(
                icon = if (tab.loading) PwIcons.Close else PwIcons.Reload,
                label = if (tab.loading) "Arrêter le chargement" else "Recharger",
                size = 20.dp,
                onClick = onReload,
            )
        }
    }
}

@Composable
private fun AddressIcon(icon: ImageVector, label: String) {
    Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
}

/** L'onglet est piloté par un programme : un accès direct au panneau d'automatisation. */
@Composable
private fun AutomationChip(active: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.padding(end = 2.dp).semantics { contentDescription = "Automatisation de cet onglet" },
    ) {
        Icon(
            PwIcons.Robot,
            contentDescription = null,
            tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(7.dp).size(16.dp),
        )
    }
}

@Composable
private fun AddressField(initial: String, onSubmit: (String) -> Unit, onTyped: (String) -> Unit, onCancel: () -> Unit) {
    val focus = remember { FocusRequester() }
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.fillMaxSize().padding(start = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = value,
            onValueChange = {
                // Le texte déjà là, tout sélectionné à l'ouverture, ne filtre pas : seule une vraie saisie le fait.
                if (it.text != value.text) onTyped(it.text)
                value = it
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (value.text.isNotBlank()) onSubmit(value.text) else onCancel() }),
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
        if (value.text.isNotEmpty()) {
            PwIconButton(PwIcons.Close, "Effacer", size = 18.dp) {
                value = TextFieldValue("")
                onTyped("")
            }
        }
    }
}

@Composable
private fun FindBar(session: BrowserSession, onClose: () -> Unit) {
    val find by session.find.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.weight(1f).height(48.dp)) {
            Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        session.startFind(it)
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardActions = KeyboardActions(onSearch = { session.findNext(true) }),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
                if (query.isNotEmpty()) {
                    Text("${find?.active ?: 0}/${find?.total ?: 0}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        PwIconButton(PwIcons.Up, "Résultat précédent") { session.findNext(false) }
        PwIconButton(PwIcons.Down, "Résultat suivant") { session.findNext(true) }
        PwIconButton(PwIcons.Close, "Fermer la recherche", onClick = onClose)
    }
}

/** Cinq commandes, toujours à la même place : grisées plutôt que retirées quand elles ne servent pas. */
@Composable
private fun ToolRow(
    tab: SessionInfo?,
    session: BrowserSession?,
    tabCount: Int,
    onNewTab: () -> Unit,
    onShowTabs: () -> Unit,
    onMenu: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PwIconButton(PwIcons.Back, "Page précédente", enabled = tab?.canGoBack == true) { session?.goBackFromUser() }
        PwIconButton(PwIcons.Forward, "Page suivante", enabled = tab?.canGoForward == true) { session?.goForwardFromUser() }
        PwIconButton(PwIcons.Add, "Nouvel onglet", onClick = onNewTab)
        TabCounter(tabCount, onShowTabs)
        PwIconButton(PwIcons.More, "Menu", onClick = onMenu)
    }
}

@Composable
fun TabCounter(count: Int, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clickable(onClick = onClick).semantics { contentDescription = "Onglets : $count" },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(28.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    if (count > 99) "∞" else count.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

// ------------------------------------------------------------------- page d'accueil

@Composable
private fun StartPage(incognito: Boolean, onOpen: (String) -> Unit) {
    val manager = PocketWrightApp.instance.sessions
    val history by manager.history.collectAsStateWithLifecycle()
    val bookmarks by manager.bookmarks.collectAsStateWithLifecycle()
    val shortcuts = remember(history, bookmarks) { (bookmarks + history).distinctBy(::hostOf).take(8) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 56.dp, bottom = 96.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { StartHeader(incognito) }
            if (!incognito) items(shortcuts, key = { it.url }) { page -> Shortcut(page, onClick = { onOpen(page.url) }) }
        }
    }
}

@Composable
private fun StartHeader(incognito: Boolean) {
    Column(Modifier.fillMaxWidth().padding(bottom = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = if (incognito) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(76.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (incognito) PwIcons.Incognito else PwIcons.Globe,
                    contentDescription = null,
                    tint = if (incognito) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(42.dp),
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(if (incognito) "Navigation privée" else "PocketWright", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        if (incognito) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Ni l'historique, ni les cookies, ni les données de ces onglets ne sont gardés quand vous les fermez.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

private fun hostOf(page: VisitedPage): String = page.url.substringAfter("://").substringBefore('/').removePrefix("www.")

/** Une teinte stable pour chaque site, tirée de son nom. */
@Composable
private fun siteColor(host: String): Pair<Color, Color> {
    val palette = listOf(
        Color(0xFF3B82F6) to Color.White,
        Color(0xFF0E9F6E) to Color.White,
        Color(0xFFF59E0B) to Color(0xFF2B1B00),
        Color(0xFFEF4444) to Color.White,
        Color(0xFF8B5CF6) to Color.White,
        Color(0xFF06B6D4) to Color(0xFF00252C),
        Color(0xFFEC4899) to Color.White,
        Color(0xFF64748B) to Color.White,
    )
    return palette[(host.hashCode() and Int.MAX_VALUE) % palette.size]
}

@Composable
private fun Shortcut(page: VisitedPage, onClick: () -> Unit) {
    val host = hostOf(page)
    val (background, foreground) = siteColor(host)
    Column(
        Modifier.clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = RoundedCornerShape(18.dp), color = background, modifier = Modifier.size(58.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(host.take(1).uppercase(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = foreground)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            host.substringBefore('.').replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun VisitedRow(page: VisitedPage, onClick: () -> Unit) {
    val host = hostOf(page)
    val (background, foreground) = siteColor(host)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = RoundedCornerShape(12.dp), color = background, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(host.take(1).uppercase(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = foreground)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(page.title.ifBlank { host }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(host, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ErrorPage(tab: SessionInfo, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(PwIcons.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text("Impossible d'ouvrir cette page", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(tab.host.ifBlank { tab.url }, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Mono(tab.lastError.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) { Text("Réessayer") }
    }
}

/** Pendant la saisie, les favoris et pages visitées qui correspondent, au plus près du pouce. */
@Composable
private fun Suggestions(typed: String, onPick: (String) -> Unit) {
    val manager = PocketWrightApp.instance.sessions
    val history by manager.history.collectAsStateWithLifecycle()
    val bookmarks by manager.bookmarks.collectAsStateWithLifecycle()
    val matches = remember(typed, history, bookmarks) {
        (bookmarks + history).distinctBy { it.url }.filter {
            typed.isBlank() || it.url.contains(typed, ignoreCase = true) || it.title.contains(typed, ignoreCase = true)
        }.take(20)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding(),
        reverseLayout = true,
        contentPadding = PaddingValues(bottom = 400.dp),
    ) {
        items(matches, key = { it.url }) { page -> VisitedRow(page, onClick = { onPick(page.url) }) }
    }
}

// ------------------------------------------------------------------- menu

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserMenu(
    tab: SessionInfo?,
    session: BrowserSession?,
    onDismiss: () -> Unit,
    onNewTab: (Boolean) -> Unit,
    onFind: () -> Unit,
    onAutomation: () -> Unit,
    onSettings: () -> Unit,
    onLibrary: () -> Unit,
) {
    val context = LocalContext.current
    val app = PocketWrightApp.instance
    val hasPage = tab != null && !tab.blank
    val bookmarks by app.sessions.bookmarks.collectAsStateWithLifecycle()
    val starred = hasPage && bookmarks.any { it.url == tab!!.url }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 16.dp)) {
            if (hasPage) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    session?.favicon?.let {
                        key(tab!!.faviconVersion) { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(24.dp)) }
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(tab!!.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        Text(tab.url, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            // Les actions les plus fréquentes, en rangée de boutons ronds.
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                QuickAction(PwIcons.Add, "Onglet") {
                    onDismiss()
                    onNewTab(false)
                }
                if (app.sessions.isolation) {
                    QuickAction(PwIcons.Incognito, "Privé") {
                        onDismiss()
                        onNewTab(true)
                    }
                }
                QuickAction(if (starred) PwIcons.StarFilled else PwIcons.Star, "Favori", enabled = hasPage) {
                    app.sessions.toggleBookmark(tab!!.url, tab.label)
                }
                QuickAction(PwIcons.Share, "Partager", enabled = hasPage) {
                    onDismiss()
                    shareText(context, tab!!.url)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            MenuRow(PwIcons.History, "Favoris et historique") {
                onDismiss()
                onLibrary()
            }
            MenuRow(PwIcons.Download, "Téléchargements") {
                onDismiss()
                runCatching { context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            MenuRow(PwIcons.Message, "Messages") {
                onDismiss()
                context.startActivity(Intent(context, MessagesActivity::class.java))
            }
            if (hasPage) {
                MenuRow(PwIcons.Search, "Rechercher dans la page") {
                    onDismiss()
                    onFind()
                }
                MenuRow(PwIcons.Copy, "Copier le lien") {
                    onDismiss()
                    copyText(context, "Lien", tab!!.url)
                }
                MenuRow(PwIcons.Print, "Imprimer ou enregistrer en PDF") {
                    onDismiss()
                    session?.printAdapter(tab!!.label)?.let { adapter ->
                        context.getSystemService(PrintManager::class.java).print(tab.label, adapter, null)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clickable { session?.setDesktopMode(!tab!!.desktop) }.height(52.dp).padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(PwIcons.Desktop, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(18.dp))
                    Text("Version pour ordinateur", Modifier.weight(1f))
                    Switch(checked = tab!!.desktop, onCheckedChange = { session?.setDesktopMode(it) })
                }
            }
            if (tab != null) {
                MenuRow(PwIcons.Robot, "Automatisation et console") {
                    onDismiss()
                    onAutomation()
                }
            }
            MenuRow(PwIcons.Settings, "Paramètres") {
                onDismiss()
                onSettings()
            }
        }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(54.dp).semantics { contentDescription = label },
        ) {
            Box(contentAlignment = Alignment.Center) {
                val tint = MaterialTheme.colorScheme.onSecondaryContainer
                Icon(icon, contentDescription = null, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).height(52.dp).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Text(label)
    }
}
