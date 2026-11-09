package com.afroni.pocketwright.messaging

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.afroni.pocketwright.R
import com.afroni.pocketwright.ui.MessagesActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** La messagerie : le rôle de messagerie par défaut, la réception, l'envoi et les notifications. */
class Messaging(private val context: Context) {
    val store = MessageStore(context)

    /** Ouvert à la première utilisation, jamais sur le fil principal au démarrage. */
    val journal: MessageJournal by lazy { MessageJournal(context) }

    /** Les MMS en cours de téléchargement. */
    val downloading = MutableStateFlow<Set<Long>>(emptySet())

    private val pendingSends = ConcurrentHashMap<Uri, CompletableDeferred<Boolean>>()

    fun isDefault(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS)
        } else {
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        }

    /** La demande officielle d'Android : l'utilisateur décide, et peut revenir en arrière dans les réglages. */
    fun roleRequestIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_SMS)
        } else {
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
        }

    // ---------------------------------------------------------------- réception

    fun onSms(intent: Intent) {
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        if (parts.isEmpty()) return
        val id = try {
            store.saveIncomingSms(parts, subscription(intent))
        } catch (e: Exception) {
            // Android ne le renverra pas : que l'utilisateur le voie au moins une fois.
            Log.e(TAG, "SMS non enregistré", e)
            notifyIncoming(unsaved(parts))
            return
        }
        store.sms(id)?.let(::announce)
    }

    /** Dans cet ordre : un message n'est diffusé qu'une fois enregistré. */
    private fun announce(message: Message) {
        journal.append(message)
        notifyIncoming(message)
    }

    private fun unsaved(parts: List<SmsMessage>) = Message(
        kind = MessageStore.KIND_SMS,
        id = NOT_SAVED,
        threadId = NOT_SAVED,
        address = parts.first().displayOriginatingAddress.orEmpty(),
        body = parts.joinToString("") { it.displayMessageBody.orEmpty() },
        subject = null,
        date = System.currentTimeMillis(),
        dateSent = parts.first().timestampMillis,
        box = MessageBox.INCOMING,
        subId = NO_SUBSCRIPTION,
    )

    fun onWapPush(intent: Intent) {
        val pdu = intent.getByteArrayExtra("data") ?: return
        val notice = MmsPdu.parseNotification(pdu)
        if (notice == null) {
            Log.w(TAG, "Avis de MMS illisible")
            return
        }
        // Les opérateurs renvoient parfois le même avis.
        if (store.hasMmsTransaction(notice.transactionId)) return
        downloadMms(store.saveMmsNotification(notice, subscription(intent)))
    }

    fun downloadMms(id: Long) {
        val notice = store.mms(id) ?: return
        val location = store.mmsLocation(id) ?: return
        downloading.update { it + id }
        try {
            val file = File(mmsDirectory(), "$id.pdu")
            val done = PendingIntent.getBroadcast(
                context,
                id.toInt(),
                Intent(context, MmsDownloadedReceiver::class.java).putExtra(EXTRA_MMS_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            smsManager(notice.subId).downloadMultimediaMessage(context, location, fileUri(file), null, done)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Téléchargement du MMS impossible", e)
            onMmsDownloaded(id, ok = false)
        }
    }

    fun onMmsDownloaded(id: Long, ok: Boolean) {
        downloading.update { it - id }
        val file = File(mmsDirectory(), "$id.pdu")
        val retrieved = if (ok) runCatching { MmsPdu.parseRetrieved(file.readBytes()) }.getOrNull() else null
        file.delete()
        val notice = store.mms(id) ?: return
        if (retrieved == null) {
            // L'avis reste dans la conversation, avec un bouton pour réessayer ; rien n'est perdu.
            announce(notice)
            return
        }
        store.mms(store.saveRetrievedMms(retrieved, notice))?.let(::announce)
        retrieved.transactionId?.let { acknowledge(it, retrieved.version, notice.subId) }
    }

    private fun acknowledge(transactionId: String, version: Int?, subId: Int) {
        runCatching {
            val file = File(mmsDirectory(), "ack-${System.nanoTime()}.pdu")
            file.writeBytes(MmsPdu.notifyResponse(transactionId, version))
            smsManager(subId).sendMultimediaMessage(context, fileUri(file), null, null, null)
        }.onFailure { Log.w(TAG, "Accusé de réception du MMS non envoyé", it) }
    }

    /** Une pièce jointe de MMS, avec son type. */
    fun attachment(partId: Long): Pair<ByteArray, String>? {
        val owner = store.partOwner(partId) ?: return null
        val attachment = store.mms(owner)?.attachments?.firstOrNull { it.partId == partId } ?: return null
        return (store.partBytes(partId) ?: return null) to attachment.contentType
    }

    // ---------------------------------------------------------------- envoi

    /** [done] reçoit le résultat de l'envoi : vrai quand toutes les parties sont parties. */
    fun send(address: String, text: String, subId: Int = NO_SUBSCRIPTION, done: CompletableDeferred<Boolean>? = null): Uri {
        val uri = store.saveOutgoingSms(address, text, subId)
        done?.let { pendingSends[uri] = it }
        try {
            val manager = smsManager(subId)
            val parts = manager.divideMessage(text)
            val results = ArrayList(parts.indices.map { index ->
                PendingIntent.getBroadcast(
                    context,
                    index,
                    Intent(context, SmsSentReceiver::class.java).setData(uri).putExtra(EXTRA_LAST_PART, index == parts.lastIndex),
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
                )
            })
            manager.sendMultipartTextMessage(address, null, parts, results, null)
        } catch (e: RuntimeException) {
            Log.w(TAG, "SMS non envoyé", e)
            onSmsResult(uri, ok = false, lastPart = true)
        }
        return uri
    }

    fun onSmsResult(uri: Uri, ok: Boolean, lastPart: Boolean) {
        store.markSmsResult(uri, ok, lastPart)
        if (!ok || lastPart) pendingSends.remove(uri)?.complete(ok)
    }

    fun retry(message: Message) {
        store.deleteSms(message.id)
        send(message.address, message.body, message.subId)
    }

    fun markRead(threadId: Long) {
        store.markRead(threadId)
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(threadId))
    }

    // ---------------------------------------------------------------- outils

    private fun notifyIncoming(message: Message) {
        if (visibleThread == message.threadId) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            context,
            message.threadId.toInt(),
            MessagesActivity.threadIntent(context, message.threadId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(store.displayName(message.address))
            .setContentText(message.preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.preview))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Le contenu (souvent un code de connexion) reste caché sur l'écran verrouillé.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setWhen(message.date)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        manager.notify(notificationId(message.threadId), notification)
    }

    private fun notificationId(threadId: Long) = NOTIFICATION_BASE + threadId.toInt()

    private fun subscription(intent: Intent): Int =
        intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, intent.getIntExtra("subscription", NO_SUBSCRIPTION))

    @Suppress("DEPRECATION")
    private fun smsManager(subId: Int): SmsManager {
        val chosen = subId.takeIf { it > 0 }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(SmsManager::class.java)
            if (chosen != null) manager.createForSubscriptionId(chosen) else manager
        } else {
            if (chosen != null) SmsManager.getSmsManagerForSubscriptionId(chosen) else SmsManager.getDefault()
        }
    }

    private fun mmsDirectory(): File {
        val directory = File(context.cacheDir, "mms").apply { mkdirs() }
        val old = System.currentTimeMillis() - STALE_FILE_MS
        directory.listFiles()?.filter { it.lastModified() < old }?.forEach { it.delete() }
        return directory
    }

    private fun fileUri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.mms", file)

    companion object {
        private const val TAG = "PocketWright"
        private const val CHANNEL = "messages"
        private const val NOTIFICATION_BASE = 10_000
        private const val STALE_FILE_MS = 60L * 60_000
        private const val NO_SUBSCRIPTION = -1
        private const val NOT_SAVED = -1L
        const val EXTRA_MMS_ID = "mms_id"
        const val EXTRA_LAST_PART = "last_part"

        /** La conversation à l'écran : inutile d'y notifier ce qu'on est en train de lire. */
        @Volatile var visibleThread: Long? = null
    }
}
