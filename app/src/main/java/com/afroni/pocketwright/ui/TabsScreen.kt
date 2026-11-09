package com.afroni.pocketwright.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.afroni.pocketwright.PocketWrightApp
import com.afroni.pocketwright.session.SessionInfo
import com.afroni.pocketwright.session.TabOrigin
import kotlinx.coroutines.delay

private enum class TabGroup(val label: String) {
    Normal("Onglets"),
    Private("Privés"),
    Api("API");

    companion object {
        fun of(tab: SessionInfo) = when {
            tab.incognito -> Private
            tab.origin == TabOrigin.API -> Api
            else -> Normal
        }
    }
}

/** La grille des onglets, rangés en trois familles : les vôtres, les privés, et ceux que pilote l'API. */
@Composable
fun TabsScreen(
    tabs: List<SessionInfo>,
    currentId: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNewTab: () -> Unit,
    onNewIncognitoTab: () -> Unit,
    onShowSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val app = PocketWrightApp.instance
    var confirming by remember { mutableStateOf<SessionInfo?>(null) }
    val initial = tabs.firstOrNull { it.id == currentId }?.let(TabGroup::of)
        ?: tabs.firstOrNull()?.let(TabGroup::of) ?: TabGroup.Normal
    var group by rememberSaveable { mutableStateOf(initial) }
    val counts = tabs.groupingBy(TabGroup::of).eachCount()
    val shown = tabs.filter { TabGroup.of(it) == group }
    BackHandler(onBack = onBack)

    // Les aperçus manquants, l'un après l'autre pour garder l'écran fluide.
    LaunchedEffect(Unit) {
        tabs.filter { !it.shown }.mapNotNull { app.sessions.get(it.id) }.filter { it.needsThumbnail }.take(6).forEach {
            delay(120)
            it.captureThumbnail()
        }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TabGroup.entries.filter { it == TabGroup.Normal || counts[it] != null }.forEach { entry ->
                            GroupChip(entry.label, counts[entry] ?: 0, selected = entry == group, onClick = { group = entry })
                        }
                    }
                    PwIconButton(PwIcons.Settings, "Paramètres", onClick = onShowSettings)
                }
                if (shown.isEmpty()) {
                    EmptyTabs(Modifier.weight(1f))
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 156.dp),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 120.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        items(shown, key = { it.id }) { tab ->
                            TabCard(
                                tab = tab,
                                selected = tab.id == currentId,
                                onClick = { onSelect(tab.id) },
                                onClose = { if (tab.automated) confirming = tab else onClose(tab.id) },
                            )
                        }
                    }
                }
            }
            Row(
                Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 20.dp, bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (app.sessions.isolation) {
                    FloatingActionButton(
                        onClick = onNewIncognitoTab,
                        shape = RoundedCornerShape(18.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(56.dp),
                    ) { Icon(PwIcons.Incognito, contentDescription = "Nouvel onglet privé") }
                }
                FloatingActionButton(
                    onClick = onNewTab,
                    shape = RoundedCornerShape(22.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(68.dp),
                ) { Icon(PwIcons.Add, contentDescription = "Nouvel onglet", modifier = Modifier.size(30.dp)) }
            }
        }
    }

    confirming?.let { tab ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Fermer cet onglet ?") },
            text = {
                Text(
                    if (tab.clients > 0) "« ${tab.label} » est piloté par ${tab.clients} programme(s) en ce moment. Ils perdront cet onglet."
                    else "« ${tab.label} » a été ouvert par l'API. Sa session et ses données seront effacées.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    onClose(tab.id)
                }) { Text("Fermer") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun GroupChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.background,
        modifier = Modifier.height(40.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
            if (count > 0) {
                Spacer(Modifier.width(6.dp))
                Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TabCard(tab: SessionInfo, selected: Boolean, onClick: () -> Unit, onClose: () -> Unit) {
    val session = PocketWrightApp.instance.sessions.get(tab.id)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = if (selected) BorderStroke(2.5.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(0.86f).padding(5.dp)
                    .clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainer),
            ) {
                val thumbnail = session?.thumbnail
                if (thumbnail != null && !tab.blank) {
                    key(tab.thumbnailVersion) {
                        Image(
                            thumbnail.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.TopCenter,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    Icon(PwIcons.Globe, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.align(Alignment.Center).size(36.dp))
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(32.dp),
                ) {
                    IconButton(onClick = onClose) {
                        Icon(PwIcons.Close, contentDescription = "Fermer ${tab.label}", modifier = Modifier.size(16.dp))
                    }
                }
                if (tab.clients > 0 || !tab.live) {
                    Row(Modifier.align(Alignment.BottomStart).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (tab.clients > 0) Badge("Piloté", active = true)
                        if (!tab.live) Badge("En veille", active = false)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val favicon = session?.favicon
                if (favicon != null) {
                    key(tab.faviconVersion) { Image(favicon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(16.dp)) }
                    Spacer(Modifier.width(8.dp))
                }
                Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun Badge(text: String, active: Boolean) {
    Surface(shape = CircleShape, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun EmptyTabs(modifier: Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(PwIcons.Globe, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text("Aucun onglet ici", style = MaterialTheme.typography.titleMedium)
    }
}
