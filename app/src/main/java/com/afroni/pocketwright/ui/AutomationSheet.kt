package com.afroni.pocketwright.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.afroni.pocketwright.PocketWrightApp
import com.afroni.pocketwright.engine.EngineStatus
import com.afroni.pocketwright.session.BrowserSession
import com.afroni.pocketwright.session.LogEntry
import com.afroni.pocketwright.session.SessionInfo
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Le panneau d'automatisation d'un onglet : comment s'y brancher, une console pour lui lancer un script
 * Playwright depuis le téléphone, et le journal de ce qu'il a fait.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationSheet(tab: SessionInfo, session: BrowserSession, onDismiss: () -> Unit) {
    val app = PocketWrightApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine by app.engine.status.collectAsStateWithLifecycle()
    var script by remember { mutableStateOf("return await page.title();") }
    var output by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    val cdp = "ws://127.0.0.1:${PocketWrightApp.API_PORT}/cdp/${tab.id}"

    fun run() {
        running = true
        output = null
        scope.launch {
            try {
                val params = buildJsonObject { put("script", script) }
                val reply = app.commands.run(session, "run", params).jsonObject
                failed = false
                output = render(reply)
            } catch (e: Exception) {
                failed = true
                output = e.message ?: e.toString()
            } finally {
                running = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(PwIcons.Robot, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Automatisation", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.size(8.dp))
                val state = when {
                    tab.clients > 0 -> "${tab.clients} programme(s) connecté(s)"
                    tab.lastAction != null -> "Dernière commande : ${tab.lastAction}"
                    else -> "Aucun programme connecté"
                }
                StatusLine(if (tab.clients > 0) Running else MaterialTheme.colorScheme.outline, state)
            }
            item {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 4.dp)) {
                        CopyLine("Session", tab.id) { copyText(context, "Identifiant", tab.id) }
                        CopyLine("Playwright (CDP)", cdp) { copyText(context, "Adresse CDP", cdp) }
                        CopyLine("REST", "/sessions/${tab.id}") { copyText(context, "Chemin REST", "/sessions/${tab.id}") }
                    }
                }
            }
            item {
                Text("Script Playwright", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.size(2.dp))
                Text(
                    "Le corps d'une fonction async, avec page, context, browser et state.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = script,
                    onValueChange = { script = it },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                )
                Spacer(Modifier.size(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = ::run, enabled = !running && engine is EngineStatus.Ready) {
                        Icon(PwIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (running) "Exécution…" else "Exécuter")
                    }
                    if (engine !is EngineStatus.Ready) {
                        Spacer(Modifier.width(12.dp))
                        Text("Playwright n'est pas prêt", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                output?.let {
                    Spacer(Modifier.size(8.dp))
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                        SelectionContainer {
                            Mono(it, Modifier.padding(12.dp), color = if (failed) Failure else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.size(10.dp))
                Text("Journal", style = MaterialTheme.typography.titleSmall)
            }
            val entries = session.logSnapshot().asReversed().take(60)
            if (entries.isEmpty()) {
                item { Text("Rien pour l'instant.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(entries.size) { index -> LogLine(entries[index]) }
        }
    }
}

@Composable
private fun CopyLine(label: String, value: String, onCopy: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Mono(value)
        }
        PwIconButton(PwIcons.Copy, "Copier $label", size = 18.dp, onClick = onCopy)
    }
}

@Composable
private fun LogLine(entry: LogEntry) {
    Row {
        Mono(DateFormat.format("HH:mm:ss", entry.time).toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Mono(
            entry.text,
            Modifier.weight(1f),
            color = when (entry.kind) {
                "error" -> Failure
                "cmd" -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

/** Le résultat d'un script, avec son journal avant. */
private fun render(reply: JsonObject): String {
    val logs = (reply["logs"]?.jsonArray ?: emptyList()).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    val result = reply["result"]
    val value = (result as? JsonPrimitive)?.takeIf { it.isString }?.content ?: result?.toString() ?: "null"
    return (logs + value).joinToString("\n")
}
