package com.afroni.pocketwright.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.afroni.pocketwright.SearchEngine
import com.afroni.pocketwright.session.BrowserSession

/** Une icône seule, touchable sur 48 dp, avec son nom pour les lecteurs d'écran. */
@Composable
fun PwIconButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(icon, contentDescription = label, tint = if (enabled) tint else tint.copy(alpha = 0.32f), modifier = Modifier.size(size))
    }
}

@Composable
fun StatusLine(color: Color, text: String, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun Mono(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = color, modifier = modifier)
}

fun copyText(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "$label copié", Toast.LENGTH_SHORT).show()
}

private val LOOKS_LIKE_HOST = Regex("^(localhost|\\d{1,3}(\\.\\d{1,3}){3}|[\\p{L}\\d-]+(\\.[\\p{L}\\d-]+)+)(:\\d+)?([/?#].*)?$")
private val LOCAL_ADDRESS = Regex("^(localhost|127\\.|10\\.|192\\.168\\.)")

/** Ce qu'on tape dans la barre d'adresse : une adresse, ou une recherche. */
fun addressFor(input: String, search: SearchEngine): String {
    val text = input.trim()
    return when {
        text.contains("://") || text.startsWith("about:") || text.startsWith("data:") -> BrowserSession.normalizeUrl(text)
        LOOKS_LIKE_HOST.matches(text) -> (if (LOCAL_ADDRESS.containsMatchIn(text)) "http://" else "https://") + text
        else -> search.urlFor(text)
    }
}
