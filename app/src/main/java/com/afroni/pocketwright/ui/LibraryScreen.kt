package com.afroni.pocketwright.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.afroni.pocketwright.PocketWrightApp
import com.afroni.pocketwright.session.VisitedPage

/** Les favoris et l'historique. */
@Composable
fun LibraryScreen(onOpen: (String) -> Unit, onBack: () -> Unit) {
    val manager = PocketWrightApp.instance.sessions
    val bookmarks by manager.bookmarks.collectAsStateWithLifecycle()
    val history by manager.history.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(onBack = onBack)
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(60.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PwIconButton(PwIcons.Back, "Retour", onClick = onBack)
                Spacer(Modifier.width(4.dp))
                Text("Favoris et historique", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            PrimaryTabRow(selectedTabIndex = section, containerColor = MaterialTheme.colorScheme.background) {
                Tab(selected = section == 0, onClick = { section = 0 }, text = { Text("Favoris") })
                Tab(selected = section == 1, onClick = { section = 1 }, text = { Text("Historique") })
            }
            val pages = if (section == 0) bookmarks else history
            if (pages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (section == 0) "Aucun favori" else "Historique vide", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.navigationBarsPadding()) {
                    items(pages, key = { it.url }) { page ->
                        PageRow(
                            page,
                            onClick = { onOpen(page.url) },
                            onRemove = { if (section == 0) manager.toggleBookmark(page.url, page.title) else manager.removeFromHistory(page.url) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PageRow(page: VisitedPage, onClick: () -> Unit, onRemove: () -> Unit) {
    val host = page.url.substringAfter("://").substringBefore('/').removePrefix("www.")
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(36.dp)) {
            Box(contentAlignment = Alignment.Center) { Text(host.take(1).uppercase(), style = MaterialTheme.typography.titleSmall) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(page.title.ifBlank { host }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(host, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PwIconButton(PwIcons.Close, "Retirer ${page.title.ifBlank { host }}", size = 18.dp, onClick = onRemove)
    }
}
