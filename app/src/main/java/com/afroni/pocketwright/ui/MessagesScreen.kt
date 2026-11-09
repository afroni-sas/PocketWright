package com.afroni.pocketwright.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.provider.ContactsContract
import android.provider.Settings
import android.text.format.DateUtils
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.afroni.pocketwright.PocketWrightApp
import com.afroni.pocketwright.messaging.Attachment
import com.afroni.pocketwright.messaging.Conversation
import com.afroni.pocketwright.messaging.Message
import com.afroni.pocketwright.messaging.MessageBox
import com.afroni.pocketwright.messaging.Messaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface MessagesRoute {
    data object Conversations : MessagesRoute
    data class Thread(val id: Long) : MessagesRoute
    data class Compose(val to: String = "", val body: String = "") : MessagesRoute
}

private val messaging get() = PocketWrightApp.instance.messaging

/**
 * Ouvre la demande d'Android. Après deux refus, Android ne la montre plus et répond aussitôt : il ne reste alors
 * que son réglage. Un refus normal, lui, est respecté.
 */
@Composable
fun rememberSmsRoleRequest(onResult: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launchedAt = remember { longArrayOf(0L) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        onResult()
        val notShown = SystemClock.elapsedRealtime() - launchedAt[0] < DIALOG_NOT_SHOWN_MS
        if (notShown && result.resultCode != Activity.RESULT_OK && !messaging.isDefault()) openDefaultAppsSettings(context)
    }
    return {
        launchedAt[0] = SystemClock.elapsedRealtime()
        runCatching { launcher.launch(messaging.roleRequestIntent()) }.onFailure { openDefaultAppsSettings(context) }
    }
}

private const val DIALOG_NOT_SHOWN_MS = 500L

private fun openDefaultAppsSettings(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
}

/** Les données des messages, rechargées à chaque changement dans le stockage SMS. */
@Composable
private fun <T> providerState(vararg keys: Any?, load: () -> T): State<T?> = produceState<T?>(null, *keys) {
    messaging.store.changes().collect {
        val loaded = withContext(Dispatchers.IO) {
            runCatching(load).onFailure { Log.w("PocketWright", "Messages illisibles", it) }.getOrNull()
        }
        if (loaded != null) value = loaded
    }
}

@Composable
fun MessagesRoot(route: MutableStateFlow<MessagesRoute>, onClose: () -> Unit) {
    val current by route.collectAsStateWithLifecycle()
    var isDefault by remember { mutableStateOf(messaging.isDefault()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { isDefault = messaging.isDefault() }
    val requestRole = rememberSmsRoleRequest { isDefault = messaging.isDefault() }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        if (!isDefault) {
            RolePrompt(requestRole, onClose)
            return@Surface
        }
        val toList = { route.value = MessagesRoute.Conversations }
        when (val r = current) {
            MessagesRoute.Conversations -> ConversationsScreen(
                onOpen = { route.value = MessagesRoute.Thread(it) },
                onCompose = { route.value = MessagesRoute.Compose() },
                onClose = onClose,
            )
            is MessagesRoute.Thread -> ThreadScreen(r.id, onBack = toList)
            is MessagesRoute.Compose -> ComposeScreen(
                initialTo = r.to,
                initialBody = r.body,
                onBack = toList,
                onSent = { route.value = it?.let(MessagesRoute::Thread) ?: MessagesRoute.Conversations },
            )
        }
    }
}

@Composable
private fun Header(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(60.dp).padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        PwIconButton(PwIcons.Back, "Retour", onClick = onBack)
        Text(
            title,
            Modifier.weight(1f).padding(start = 4.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

@Composable
private fun RolePrompt(onRequest: () -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Header("Messages", onClose)
        Column(
            Modifier.fillMaxSize().padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(80.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(PwIcons.Message, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text("Une messagerie dans votre navigateur", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(
                "Pour recevoir et envoyer vos SMS ici, PocketWright doit remplacer votre messagerie actuelle. Android vous demandera de confirmer, et vous pourrez revenir en arrière à tout moment dans ses réglages.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = onRequest) { Text("Définir comme messagerie") }
        }
    }
}

// ---------------------------------------------------------------- conversations

@Composable
private fun ConversationsScreen(onOpen: (Long) -> Unit, onCompose: () -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val conversations by providerState { messaging.store.conversations() }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding()) {
            Header("Messages", onClose)
            val list = conversations
            when {
                list == null -> Centered { CircularProgressIndicator() }
                list.isEmpty() -> Centered {
                    Text("Aucun message", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("Les SMS que vous recevez apparaîtront ici.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 112.dp), modifier = Modifier.fillMaxSize()) {
                    items(list, key = Conversation::id) { conversation -> ConversationRow(conversation) { onOpen(conversation.id) } }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onCompose,
            icon = { Icon(PwIcons.Add, contentDescription = null) },
            text = { Text("Nouveau") },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp),
        )
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { content() }
}

@Composable
private fun Avatar(name: String, size: Dp = 46.dp) {
    val letter = name.firstOrNull(Char::isLetter)?.uppercase()
    Box(Modifier.size(size).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
        if (letter != null) {
            Text(letter, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        } else {
            Icon(PwIcons.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(size / 2))
        }
    }
}

@Composable
private fun ConversationRow(conversation: Conversation, onClick: () -> Unit) {
    val context = LocalContext.current
    val weight = if (conversation.unread) FontWeight.Bold else FontWeight.Normal
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Avatar(conversation.title)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(conversation.title, Modifier.weight(1f), fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                Text(
                    shortDate(context, conversation.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (conversation.unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    conversation.snippet,
                    Modifier.weight(1f),
                    fontWeight = weight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (conversation.unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (conversation.unread) {
                    Box(Modifier.padding(start = 8.dp).size(9.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                }
            }
        }
    }
}

private fun shortDate(context: Context, millis: Long): String =
    if (DateUtils.isToday(millis)) {
        DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_TIME)
    } else {
        DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
    }

// ---------------------------------------------------------------- conversation

@Composable
private fun ThreadScreen(id: Long, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    val messages by providerState(id) { messaging.store.messages(id) }
    var resumed by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LifecycleResumeEffect(id) {
        resumed = true
        Messaging.visibleThread = id
        onPauseOrDispose {
            resumed = false
            Messaging.visibleThread = null
        }
    }
    LaunchedEffect(messages, resumed) {
        if (resumed && messages != null) withContext(Dispatchers.IO) { runCatching { messaging.markRead(id) } }
    }
    val list = messages
    val address = list?.lastOrNull()?.address.orEmpty()
    val title by providerState(address) { if (address.isEmpty()) "" else messaging.store.displayName(address) }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Header(title.orEmpty(), onBack) {
            PwIconButton(PwIcons.Delete, "Supprimer la conversation", size = 22.dp) { confirmDelete = true }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (list == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val newestFirst = remember(list) { list.asReversed() }
            val listState = rememberLazyListState()
            val newest = newestFirst.firstOrNull()
            // Une liste garde sa position quand un élément arrive : sans ça, le nouveau message resterait caché.
            // On suit le dernier message, sauf si l'utilisateur est remonté lire l'historique.
            LaunchedEffect(newest?.kind, newest?.id) {
                if (newest != null && (newest.box != MessageBox.INCOMING || listState.firstVisibleItemIndex <= 1)) {
                    listState.animateScrollToItem(0)
                }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(newestFirst, key = { _, m -> m.kind + m.id }) { index, message ->
                    val previous = newestFirst.getOrNull(index + 1)
                    Column {
                        if (previous == null || !sameDay(previous.date, message.date)) DaySeparator(message.date)
                        Bubble(message)
                    }
                }
            }
        }
        Composer(enabled = address.isNotEmpty()) { text ->
            val subId = list?.lastOrNull()?.subId ?: -1
            scope.launch(Dispatchers.IO) { runCatching { messaging.send(address, text, subId) } }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer cette conversation ?") },
            text = { Text("Tous ses messages seront effacés de ce téléphone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { messaging.store.deleteConversation(id) } }
                        onBack()
                    }
                }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
        )
    }
}

private fun sameDay(a: Long, b: Long): Boolean {
    val calendarA = java.util.Calendar.getInstance().apply { timeInMillis = a }
    val calendarB = java.util.Calendar.getInstance().apply { timeInMillis = b }
    return calendarA[java.util.Calendar.YEAR] == calendarB[java.util.Calendar.YEAR] &&
        calendarA[java.util.Calendar.DAY_OF_YEAR] == calendarB[java.util.Calendar.DAY_OF_YEAR]
}

@Composable
private fun DaySeparator(millis: Long) {
    val context = LocalContext.current
    val label = if (DateUtils.isToday(millis)) {
        "Aujourd'hui"
    } else {
        DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY)
    }
    Text(
        label,
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Bubble(message: Message) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val incoming = message.box == MessageBox.INCOMING
    val failed = message.box == MessageBox.FAILED
    val colors = MaterialTheme.colorScheme
    val background = when {
        failed -> colors.errorContainer
        incoming -> colors.surfaceContainerHigh
        else -> colors.primary
    }
    val foreground = when {
        failed -> colors.onErrorContainer
        incoming -> colors.onSurface
        else -> colors.onPrimary
    }
    val shape = if (incoming) RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp) else RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (incoming) Alignment.Start else Alignment.End) {
        Surface(color = background, contentColor = foreground, shape = shape, modifier = Modifier.widthIn(max = 300.dp)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                message.subject?.let { Text(it, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium) }
                message.attachments.forEach { AttachmentView(it) }
                if (message.body.isNotBlank()) SelectionContainer { Text(message.body, style = MaterialTheme.typography.bodyLarge) }
                if (message.pending) PendingMms(message)
            }
        }
        val status = when (message.box) {
            MessageBox.SENDING -> "Envoi…"
            MessageBox.FAILED -> "Échec de l'envoi · Toucher pour réessayer"
            else -> DateUtils.formatDateTime(context, message.date, DateUtils.FORMAT_SHOW_TIME)
        }
        Text(
            status,
            style = MaterialTheme.typography.labelSmall,
            color = if (failed) colors.error else colors.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = 6.dp, vertical = 3.dp)
                .then(if (failed) Modifier.clickable { scope.launch(Dispatchers.IO) { runCatching { messaging.retry(message) } } } else Modifier),
        )
    }
}

@Composable
private fun PendingMms(message: Message) {
    val scope = rememberCoroutineScope()
    val downloading by messaging.downloading.collectAsStateWithLifecycle()
    Text("Message multimédia à télécharger", style = MaterialTheme.typography.bodyMedium)
    if (message.id in downloading) {
        Text("Téléchargement…", style = MaterialTheme.typography.bodySmall)
    } else {
        TextButton(onClick = { scope.launch(Dispatchers.IO) { runCatching { messaging.downloadMms(message.id) } } }, contentPadding = PaddingValues(0.dp)) {
            Text("Télécharger")
        }
    }
}

@Composable
private fun AttachmentView(attachment: Attachment) {
    if (attachment.contentType.startsWith("image/", true)) {
        val image by produceState<ImageBitmap?>(null, attachment.partId) { value = withContext(Dispatchers.IO) { loadThumbnail(attachment.partId) } }
        image?.let {
            Image(it, contentDescription = attachment.name ?: "Image", contentScale = ContentScale.Fit, modifier = Modifier.heightIn(max = 240.dp))
            return
        }
    }
    Text(attachment.name ?: "Pièce jointe (${attachment.contentType})", style = MaterialTheme.typography.bodySmall)
}

/** Réduite à l'affichage : une photo de MMS pleine taille coûterait des dizaines de Mo de mémoire. */
private fun loadThumbnail(partId: Long): ImageBitmap? = runCatching {
    val app = PocketWrightApp.instance
    val uri = messaging.store.partUri(partId)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > THUMBNAIL_MAX) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }?.asImageBitmap()
}.getOrNull()

private const val THUMBNAIL_MAX = 720

@Composable
private fun Composer(enabled: Boolean, initial: String = "", onSend: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Field(text, { text = it }, "Message", Modifier.weight(1f), maxLines = 5)
        FilledIconButton(
            enabled = enabled && text.isNotBlank(),
            onClick = {
                onSend(text.trim())
                text = ""
            },
            modifier = Modifier.size(48.dp),
        ) { Icon(PwIcons.Send, contentDescription = "Envoyer", modifier = Modifier.size(22.dp)) }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerHigh, modifier = modifier) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            maxLines = maxLines,
            keyboardOptions = keyboard,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = 18.dp, vertical = 13.dp)) {
                    if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    inner()
                }
            },
        )
    }
}

// ---------------------------------------------------------------- nouveau message

@Composable
private fun ComposeScreen(initialTo: String, initialBody: String, onBack: () -> Unit, onSent: (Long?) -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var to by remember(initialTo) { mutableStateOf(initialTo) }
    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
                if (c.moveToFirst()) to = c.getString(0).orEmpty()
            }
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Header("Nouveau message", onBack)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(to, { to = it }, "Numéro du destinataire", Modifier.weight(1f), keyboard = KeyboardOptions(keyboardType = KeyboardType.Phone))
            PwIconButton(PwIcons.Person, "Choisir un contact") {
                runCatching { pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) }
            }
        }
        Spacer(Modifier.weight(1f))
        val recipients = to.split(',', ';').map(String::trim).filter(String::isNotEmpty)
        Composer(enabled = recipients.isNotEmpty(), initial = initialBody) { text ->
            scope.launch {
                val thread = withContext(Dispatchers.IO) {
                    runCatching {
                        recipients.forEach { messaging.send(it, text) }
                        recipients.singleOrNull()?.let(messaging.store::threadFor)
                    }.getOrNull()
                }
                onSent(thread)
            }
        }
    }
}
