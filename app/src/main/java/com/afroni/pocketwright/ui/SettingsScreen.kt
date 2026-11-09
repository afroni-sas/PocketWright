package com.afroni.pocketwright.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.afroni.pocketwright.PocketWrightApp
import com.afroni.pocketwright.SearchEngine
import com.afroni.pocketwright.ServerStatus
import com.afroni.pocketwright.engine.EngineStatus
import com.afroni.pocketwright.update.UpdateState
import kotlinx.coroutines.launch
import android.provider.Settings as AndroidSettings

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().padding(0.dp)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(60.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PwIconButton(PwIcons.Back, "Retour", onClick = onBack)
                    Spacer(Modifier.width(4.dp))
                    Text("Paramètres", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.navigationBarsPadding(),
                ) {
                    item { ApiSection() }
                    item { ConnectSection() }
                    item { MessagingSection() }
                    item { EngineSection() }
                    item { UpdatesSection() }
                    item { BrowsingSection() }
                    item { AlwaysOnSection() }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp))
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Composable
private fun ApiSection() {
    val app = PocketWrightApp.instance
    val context = LocalContext.current
    val status by app.serverStatus.collectAsStateWithLifecycle()
    val key by app.settings.apiKey.collectAsStateWithLifecycle()
    val required by app.settings.requireKey.collectAsStateWithLifecycle()
    var revealed by remember { mutableStateOf(false) }
    Section("API locale") {
        val (color, label) = when (val s = status) {
            is ServerStatus.Running -> Running to "En marche sur 127.0.0.1:${s.port}"
            is ServerStatus.Failed -> Failure to "Arrêtée : ${s.message}"
            ServerStatus.Starting -> Warning to "Démarrage…"
        }
        StatusLine(color, label)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Clé API", style = MaterialTheme.typography.labelLarge)
                Mono(if (revealed) key else "•".repeat(16))
            }
            TextButton(onClick = { revealed = !revealed }) { Text(if (revealed) "Masquer" else "Révéler") }
            PwIconButton(PwIcons.Copy, "Copier la clé", size = 20.dp) { copyText(context, "Clé API", key) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Exiger la clé")
                Text(
                    "Sans elle, toute app du téléphone peut piloter vos onglets.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = required, onCheckedChange = app.settings::setRequireKey)
        }
        OutlinedButton(onClick = app.settings::regenerateKey) { Text("Générer une nouvelle clé") }
    }
}

@Composable
private fun ConnectSection() {
    val context = LocalContext.current
    val port = PocketWrightApp.API_PORT
    Section("Se connecter") {
        Endpoint("REST", "http://127.0.0.1:$port/sessions", context)
        Endpoint("WebSocket (une connexion, un onglet)", "ws://127.0.0.1:$port/ws", context)
        Endpoint("Playwright (connectOverCDP)", "ws://127.0.0.1:$port/cdp", context)
    }
}

@Composable
private fun MessagingSection() {
    val app = PocketWrightApp.instance
    val context = LocalContext.current
    var isDefault by remember { mutableStateOf(app.messaging.isDefault()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { isDefault = app.messaging.isDefault() }
    val requestRole = rememberSmsRoleRequest { isDefault = app.messaging.isDefault() }
    val streaming by app.settings.messagesApi.collectAsStateWithLifecycle()
    Section("Messagerie") {
        if (isDefault) {
            StatusLine(Running, "PocketWright gère vos SMS")
        } else {
            StatusLine(Warning, "Une autre application gère vos SMS")
            Button(onClick = requestRole) { Text("Définir comme messagerie") }
        }
        FilledTonalButton(onClick = { context.startActivity(Intent(context, MessagesActivity::class.java)) }) { Text("Ouvrir les messages") }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Donner à l'API l'accès aux messages")
                Text(
                    "Tout programme qui a la clé pourra lire, envoyer, modifier et supprimer vos SMS, codes de connexion compris. Les envois passent par votre forfait.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = streaming, enabled = isDefault, onCheckedChange = app.settings::setMessagesApi)
        }
        if (isDefault && streaming) Endpoint("Messages (flux et commandes)", "ws://127.0.0.1:${PocketWrightApp.API_PORT}/messages/stream", context)
    }
}

@Composable
private fun Endpoint(label: String, address: String, context: Context) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Mono(address)
        }
        PwIconButton(PwIcons.Copy, "Copier $label", size = 18.dp) { copyText(context, "Adresse", address) }
    }
}

@Composable
private fun EngineSection() {
    val app = PocketWrightApp.instance
    val context = LocalContext.current
    val engine by app.engine.status.collectAsStateWithLifecycle()
    val browser = app.browser
    Section("Moteurs") {
        val (color, label) = when (val s = engine) {
            is EngineStatus.Ready -> Running to "Playwright ${s.playwright} · Node ${s.node}"
            is EngineStatus.Failed -> Failure to "Playwright arrêté : ${s.message}"
            EngineStatus.Starting -> Warning to "Démarrage de Playwright…"
        }
        StatusLine(color, label)
        StatusLine(if (browser.outdated) Warning else Running, "Chromium ${browser.version} · ${browser.channel}")
        if (browser.outdated) {
            Text(
                "Ce moteur a plus d'un an de retard : des sites peuvent mal s'afficher et il manque des correctifs de sécurité. Installez le moteur de l'app ci-dessous.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            if (app.sessions.isolation) "Chaque session a ses propres cookies et son stockage."
            else "Cette version de WebView ne sépare pas les profils : les sessions partagent leurs cookies. Une mise à jour corrige cela.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Les mises à jour se font ici, sans Google Play : directement depuis les publications officielles. */
@Composable
private fun UpdatesSection() {
    val app = PocketWrightApp.instance
    val chromiumState by app.chromium.state.collectAsStateWithLifecycle()
    val chromiumAvailable by app.chromium.available.collectAsStateWithLifecycle()
    val playwrightState by app.playwright.state.collectAsStateWithLifecycle()
    val playwrightAvailable by app.playwright.available.collectAsStateWithLifecycle()
    val engine by app.engine.status.collectAsStateWithLifecycle()
    val ready = engine as? EngineStatus.Ready
    val nodeMajor = ready?.node?.removePrefix("v")?.substringBefore('.')?.toIntOrNull()
    LaunchedEffect(ready != null) {
        runCatching { app.chromium.check() }
        if (ready != null) runCatching { app.playwright.check(ready.playwright, nodeMajor) }
    }
    Section("Mises à jour") {
        Component(
            name = "Chromium",
            current = app.browser.version + if (app.chromium.loadedVersion != null) " (moteur de l'app)" else " (moteur du système)",
            state = chromiumState,
            available = chromiumAvailable?.let { "${it.version} · ${it.size / 1_000_000} Mo" },
            failure = app.chromium.lastFailure,
            onInstall = { chromiumAvailable?.let { target -> app.background.launch { runCatching { app.chromium.install(target) } } } },
            onRetry = { app.background.launch { runCatching { app.chromium.check() } } },
            revertLabel = "Revenir au moteur du système".takeIf { app.chromium.loadedVersion != null },
            onRevert = app.chromium::useSystemEngine,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Component(
            name = "Playwright",
            current = ready?.playwright ?: "…",
            state = playwrightState,
            available = playwrightAvailable?.version,
            failure = app.playwright.lastFailure,
            onInstall = { playwrightAvailable?.let { target -> app.background.launch { runCatching { app.playwright.install(target) } } } },
            onRetry = { if (ready != null) app.background.launch { runCatching { app.playwright.check(ready.playwright, nodeMajor) } } },
            revertLabel = "Revenir à la version intégrée".takeIf { app.playwright.installedVersion != null },
            onRevert = app.playwright::useBundled,
        )
        if (chromiumState is UpdateState.RestartNeeded || playwrightState is UpdateState.RestartNeeded) {
            Button(onClick = app::restart, modifier = Modifier.fillMaxWidth()) { Text("Redémarrer l'app pour appliquer") }
        }
    }
}

@Composable
private fun Component(
    name: String,
    current: String,
    state: UpdateState,
    available: String?,
    failure: String?,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    revertLabel: String?,
    onRevert: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(name, style = MaterialTheme.typography.labelLarge)
        Text(current, style = MaterialTheme.typography.bodyMedium)
        failure?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        when (state) {
            is UpdateState.Downloading -> {
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                Text("Téléchargement : ${(state.progress * 100).toInt()} %", style = MaterialTheme.typography.bodySmall)
            }
            UpdateState.Installing -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Installation…", style = MaterialTheme.typography.bodySmall)
            }
            UpdateState.Checking -> Text("Recherche d'une mise à jour…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is UpdateState.RestartNeeded -> StatusLine(Running, "Prêt : ${state.version}, au prochain démarrage")
            is UpdateState.Failed -> {
                Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) { Text("Réessayer") }
            }
            UpdateState.Idle -> if (available == null) StatusLine(Running, "À jour")
        }
        if (available != null && (state is UpdateState.Idle || state is UpdateState.Failed)) {
            FilledTonalButton(onClick = onInstall) { Text("Installer $available") }
        }
        if (revertLabel != null && state !is UpdateState.Downloading && state !is UpdateState.Installing) {
            TextButton(onClick = onRevert, contentPadding = PaddingValues(0.dp)) { Text(revertLabel) }
        }
    }
}

@Composable
private fun BrowsingSection() {
    val app = PocketWrightApp.instance
    val search by app.settings.searchEngine.collectAsStateWithLifecycle()
    val history by app.sessions.history.collectAsStateWithLifecycle()
    Section("Navigation") {
        Text("Moteur de recherche", style = MaterialTheme.typography.labelLarge)
        SearchEngine.entries.forEach { engine ->
            Row(
                Modifier.fillMaxWidth().clickable { app.settings.setSearchEngine(engine) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = engine == search, onClick = { app.settings.setSearchEngine(engine) })
                Text(engine.label)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Historique : ${history.size} page(s)", Modifier.weight(1f))
            TextButton(onClick = app.sessions::clearHistory, enabled = history.isNotEmpty()) { Text("Effacer") }
        }
    }
}

@Composable
private fun AlwaysOnSection() {
    val context = LocalContext.current
    var unrestricted by remember { mutableStateOf(batteryUnrestricted(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { unrestricted = batteryUnrestricted(context) }
    Section("Toujours actif") {
        if (unrestricted) {
            StatusLine(Running, "Batterie sans restriction")
        } else {
            StatusLine(Warning, "Android peut mettre l'app en veille")
            FilledTonalButton(onClick = { requestUnrestrictedBattery(context) }) { Text("Autoriser en permanence") }
        }
        Text(
            "Certains constructeurs ajoutent leur propre réglage de démarrage automatique : autorisez-le aussi dans les informations de l'app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = {
            runCatching {
                context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }
        }) { Text("Informations de l'app") }
        Spacer(Modifier.size(0.dp))
    }
}

private fun batteryUnrestricted(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

/** Sans restriction de batterie, Android ne met pas l'app en veille : le serveur répond toujours. */
@SuppressLint("BatteryLife")
private fun requestUnrestrictedBattery(context: Context) {
    runCatching {
        context.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
    }
}
