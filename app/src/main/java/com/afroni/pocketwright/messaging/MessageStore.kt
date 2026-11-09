package com.afroni.pocketwright.messaging

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.telephony.SmsMessage
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

enum class MessageBox { INCOMING, SENDING, SENT, FAILED }

data class Attachment(val partId: Long, val contentType: String, val name: String?)

data class Message(
    val kind: String,
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val subject: String?,
    val date: Long,
    val dateSent: Long,
    val box: MessageBox,
    val subId: Int,
    val read: Boolean = true,
    val attachments: List<Attachment> = emptyList(),
    /** Un MMS dont le contenu est encore chez l'opérateur. */
    val pending: Boolean = false,
) {
    val preview: String
        get() = when {
            pending -> "Message multimédia à télécharger"
            body.isNotBlank() -> body
            !subject.isNullOrBlank() -> subject
            else -> "Pièce jointe"
        }
}

data class Conversation(val id: Long, val title: String, val addresses: List<String>, val snippet: String, val date: Long, val unread: Boolean)

/**
 * Lit et écrit le stockage SMS d'Android. Une fois messagerie par défaut, l'app est la seule à y écrire : un
 * message qu'elle n'enregistre pas n'existe plus nulle part.
 */
class MessageStore(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val names = ConcurrentHashMap<String, String>()

    /** Émet tout de suite, puis à chaque changement dans les messages. */
    fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        OBSERVED.forEach { resolver.registerContentObserver(it, true, observer) }
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate()

    fun clearNames() = names.clear()

    fun displayName(address: String): String = names.getOrPut(address) {
        runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
            resolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: address
    }

    fun threadFor(address: String): Long = Telephony.Threads.getOrCreateThreadId(context, address)

    // ---------------------------------------------------------------- lecture

    fun conversations(): List<Conversation> {
        val addresses = HashMap<Long, String>()
        resolver.query(CANONICAL_ADDRESSES, arrayOf("_id", "address"), null, null, null)?.use { c ->
            while (c.moveToNext()) addresses[c.getLong(0)] = c.getString(1).orEmpty()
        }
        val columns = arrayOf("_id", "date", "message_count", "recipient_ids", "snippet", "snippet_cs", "read")
        return resolver.query(CONVERSATIONS, columns, null, null, "date DESC")?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    if (c.long("message_count") == 0L) continue
                    val recipients = c.str("recipient_ids").orEmpty().split(' ').mapNotNull { it.toLongOrNull()?.let(addresses::get) }
                    val title = recipients.joinToString(", ", transform = ::displayName)
                    val snippet = decodeLegacy(c.str("snippet"), c.int("snippet_cs")).ifBlank { "Message multimédia" }
                    add(Conversation(c.long("_id"), title.ifBlank { "Inconnu" }, recipients, snippet, c.long("date"), c.int("read") == 0))
                }
            }
        }.orEmpty()
    }

    /** Les plus récents du fil, du plus ancien au plus récent ; [before] (ms) remonte dans l'historique. */
    fun messages(threadId: Long, limit: Int = HISTORY, before: Long? = null): List<Message> {
        val args = arrayOf(threadId.toString())
        val smsOlder = before?.let { " AND ${Sms.DATE} < $it" }.orEmpty()
        val mmsOlder = before?.let { " AND ${Mms.DATE} < ${it / 1000}" }.orEmpty()
        val sms = resolver.query(Sms.CONTENT_URI, SMS_COLUMNS, "${Sms.THREAD_ID} = ? AND ${Sms.TYPE} != ${Sms.MESSAGE_TYPE_DRAFT}$smsOlder", args, "${Sms.DATE} DESC LIMIT $limit")
            ?.use { c -> buildList { while (c.moveToNext()) add(smsAt(c)) } }.orEmpty()
        val mms = resolver.query(Mms.CONTENT_URI, MMS_COLUMNS, "${Mms.THREAD_ID} = ? AND ${Mms.MESSAGE_TYPE} IN ($M_SEND, $M_NOTIFICATION, $M_RETRIEVED) AND ${Mms.MESSAGE_BOX} != ${Mms.MESSAGE_BOX_DRAFTS}$mmsOlder", args, "${Mms.DATE} DESC LIMIT $limit")
            ?.use { c -> buildList { while (c.moveToNext()) add(mmsAt(c)) } }.orEmpty()
        return (sms + mms).sortedBy { it.date }.takeLast(limit)
    }

    fun load(kind: String, id: Long): Message? = if (kind == KIND_MMS) mms(id) else sms(id)

    fun sms(id: Long): Message? =
        resolver.query(ContentUris.withAppendedId(Sms.CONTENT_URI, id), SMS_COLUMNS, null, null, null)?.use { c -> if (c.moveToFirst()) smsAt(c) else null }

    fun mms(id: Long): Message? =
        resolver.query(ContentUris.withAppendedId(Mms.CONTENT_URI, id), MMS_COLUMNS, null, null, null)?.use { c -> if (c.moveToFirst()) mmsAt(c) else null }

    fun mmsLocation(id: Long): String? =
        resolver.query(ContentUris.withAppendedId(Mms.CONTENT_URI, id), arrayOf(Mms.CONTENT_LOCATION), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun hasMmsTransaction(transactionId: String): Boolean =
        resolver.query(Mms.CONTENT_URI, arrayOf(Mms._ID), "${Mms.TRANSACTION_ID} = ?", arrayOf(transactionId), null)?.use { it.moveToFirst() } == true

    /** Le MMS auquel appartient une pièce jointe. */
    fun partOwner(partId: Long): Long? =
        resolver.query(partUri(partId), arrayOf(Mms.Part.MSG_ID), null, null, null)?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    fun partBytes(partId: Long): ByteArray? =
        resolver.openInputStream(partUri(partId))?.use { it.readBytes() }

    fun partUri(partId: Long): Uri = Uri.parse("content://mms/part/$partId")

    private fun smsAt(c: Cursor) = Message(
        kind = KIND_SMS,
        id = c.long(Sms._ID),
        threadId = c.long(Sms.THREAD_ID),
        address = c.str(Sms.ADDRESS).orEmpty(),
        body = c.str(Sms.BODY).orEmpty(),
        subject = c.str(Sms.SUBJECT),
        date = c.long(Sms.DATE),
        dateSent = c.long(Sms.DATE_SENT),
        box = when (c.int(Sms.TYPE)) {
            Sms.MESSAGE_TYPE_INBOX -> MessageBox.INCOMING
            Sms.MESSAGE_TYPE_SENT -> MessageBox.SENT
            Sms.MESSAGE_TYPE_FAILED -> MessageBox.FAILED
            else -> MessageBox.SENDING
        },
        subId = c.int(Sms.SUBSCRIPTION_ID),
        read = c.int(Sms.READ) != 0,
    )

    private fun mmsAt(c: Cursor): Message {
        val id = c.long(Mms._ID)
        val box = when (c.int(Mms.MESSAGE_BOX)) {
            Mms.MESSAGE_BOX_INBOX -> MessageBox.INCOMING
            Mms.MESSAGE_BOX_SENT -> MessageBox.SENT
            Mms.MESSAGE_BOX_FAILED -> MessageBox.FAILED
            else -> MessageBox.SENDING
        }
        val text = StringBuilder()
        val attachments = mutableListOf<Attachment>()
        resolver.query(Uri.parse("content://mms/$id/part"), PART_COLUMNS, null, null, null)?.use { p ->
            while (p.moveToNext()) {
                val type = p.str(Mms.Part.CONTENT_TYPE).orEmpty()
                when {
                    type.equals(SMIL, true) -> Unit
                    type.equals(TEXT_PLAIN, true) -> text.append(p.str(Mms.Part.TEXT).orEmpty())
                    else -> attachments += Attachment(p.long(Mms.Part._ID), type, p.str(Mms.Part.NAME) ?: p.str(Mms.Part.CONTENT_LOCATION))
                }
            }
        }
        // Reçu : l'expéditeur. Envoyé : le destinataire.
        val wanted = if (box == MessageBox.INCOMING) ADDRESS_FROM else ADDRESS_TO
        val address = resolver.query(Uri.parse("content://mms/$id/addr"), arrayOf(Mms.Addr.ADDRESS), "${Mms.Addr.TYPE} = $wanted", null, null)
            ?.use { a -> if (a.moveToFirst()) a.getString(0) else null }
        return Message(
            kind = KIND_MMS,
            id = id,
            threadId = c.long(Mms.THREAD_ID),
            address = address.orEmpty(),
            body = text.toString(),
            subject = decodeLegacy(c.str(Mms.SUBJECT), c.int(Mms.SUBJECT_CHARSET)).takeIf(String::isNotBlank),
            date = c.long(Mms.DATE) * 1000,
            dateSent = c.long(Mms.DATE_SENT) * 1000,
            box = box,
            subId = c.int(Mms.SUBSCRIPTION_ID),
            read = c.int(Mms.READ) != 0,
            attachments = attachments,
            pending = c.int(Mms.MESSAGE_TYPE) == M_NOTIFICATION,
        )
    }

    // ---------------------------------------------------------------- écriture

    fun saveIncomingSms(parts: List<SmsMessage>, subId: Int): Long {
        val first = parts.first()
        val values = ContentValues().apply {
            put(Sms.ADDRESS, first.displayOriginatingAddress)
            put(Sms.BODY, parts.joinToString("") { it.displayMessageBody.orEmpty() })
            put(Sms.DATE, System.currentTimeMillis())
            put(Sms.DATE_SENT, first.timestampMillis)
            put(Sms.PROTOCOL, first.protocolIdentifier)
            put(Sms.REPLY_PATH_PRESENT, if (first.isReplyPathPresent) 1 else 0)
            put(Sms.SERVICE_CENTER, first.serviceCenterAddress)
            first.pseudoSubject?.takeIf(String::isNotEmpty)?.let { put(Sms.SUBJECT, it) }
            put(Sms.READ, 0)
            put(Sms.SEEN, 0)
            if (subId >= 0) put(Sms.SUBSCRIPTION_ID, subId)
        }
        return ContentUris.parseId(resolver.insert(Sms.Inbox.CONTENT_URI, values) ?: throw IOException("SMS non enregistré"))
    }

    fun saveOutgoingSms(address: String, body: String, subId: Int): Uri {
        val values = ContentValues().apply {
            put(Sms.ADDRESS, address)
            put(Sms.BODY, body)
            put(Sms.DATE, System.currentTimeMillis())
            put(Sms.READ, 1)
            put(Sms.SEEN, 1)
            if (subId >= 0) put(Sms.SUBSCRIPTION_ID, subId)
        }
        return resolver.insert(Sms.Outbox.CONTENT_URI, values) ?: throw IOException("SMS non enregistré")
    }

    /** Un SMS en plusieurs parties n'est envoyé que si toutes le sont : un échec n'est jamais effacé par la suite. */
    fun markSmsResult(uri: Uri, ok: Boolean, lastPart: Boolean) {
        if (!ok) {
            resolver.update(uri, ContentValues().apply { put(Sms.TYPE, Sms.MESSAGE_TYPE_FAILED) }, null, null)
        } else if (lastPart) {
            resolver.update(uri, ContentValues().apply { put(Sms.TYPE, Sms.MESSAGE_TYPE_SENT) }, "${Sms.TYPE} = ${Sms.MESSAGE_TYPE_OUTBOX}", null)
        }
    }

    fun saveMmsNotification(n: MmsNotification, subId: Int): Long {
        val from = n.from?.takeIf(String::isNotBlank) ?: UNKNOWN_SENDER
        val values = ContentValues().apply {
            put(Mms.THREAD_ID, threadFor(from))
            put(Mms.DATE, System.currentTimeMillis() / 1000)
            put(Mms.MESSAGE_BOX, Mms.MESSAGE_BOX_INBOX)
            put(Mms.READ, 0)
            put(Mms.SEEN, 0)
            put(Mms.MESSAGE_TYPE, M_NOTIFICATION)
            put(Mms.TRANSACTION_ID, n.transactionId)
            put(Mms.CONTENT_LOCATION, n.contentLocation)
            n.version?.let { put(Mms.MMS_VERSION, it) }
            n.expiry?.let { put(Mms.EXPIRY, it) }
            n.size?.let { put(Mms.MESSAGE_SIZE, it) }
            n.subject?.let {
                put(Mms.SUBJECT, encodeLegacy(it))
                put(Mms.SUBJECT_CHARSET, UTF8_MIB)
            }
            if (subId >= 0) put(Mms.SUBSCRIPTION_ID, subId)
        }
        val id = ContentUris.parseId(resolver.insert(Mms.Inbox.CONTENT_URI, values) ?: throw IOException("MMS non enregistré"))
        addAddress(id, from, ADDRESS_FROM)
        return id
    }

    /** Remplace l'avis d'arrivée par le MMS complet. */
    fun saveRetrievedMms(mms: MmsRetrieved, notice: Message): Long {
        val from = mms.from?.takeIf(String::isNotBlank) ?: notice.address.ifBlank { UNKNOWN_SENDER }
        val values = ContentValues().apply {
            put(Mms.THREAD_ID, threadFor(from))
            put(Mms.DATE, System.currentTimeMillis() / 1000)
            mms.date?.let { put(Mms.DATE_SENT, it) }
            put(Mms.MESSAGE_BOX, Mms.MESSAGE_BOX_INBOX)
            put(Mms.READ, 0)
            put(Mms.SEEN, 0)
            put(Mms.MESSAGE_TYPE, M_RETRIEVED)
            put(Mms.CONTENT_TYPE, mms.contentType)
            mms.transactionId?.let { put(Mms.TRANSACTION_ID, it) }
            mms.messageId?.let { put(Mms.MESSAGE_ID, it) }
            mms.version?.let { put(Mms.MMS_VERSION, it) }
            (mms.subject ?: notice.subject)?.let {
                put(Mms.SUBJECT, encodeLegacy(it))
                put(Mms.SUBJECT_CHARSET, UTF8_MIB)
            }
            if (notice.subId >= 0) put(Mms.SUBSCRIPTION_ID, notice.subId)
        }
        val id = ContentUris.parseId(resolver.insert(Mms.Inbox.CONTENT_URI, values) ?: throw IOException("MMS non enregistré"))
        addAddress(id, from, ADDRESS_FROM)
        mms.to.forEach { addAddress(id, it, ADDRESS_TO) }
        mms.parts.forEachIndexed { index, part -> addPart(id, index, part) }
        resolver.delete(ContentUris.withAppendedId(Mms.CONTENT_URI, notice.id), null, null)
        return id
    }

    private fun addAddress(mmsId: Long, address: String, type: Int) {
        val values = ContentValues().apply {
            put(Mms.Addr.ADDRESS, address)
            put(Mms.Addr.TYPE, type)
            put(Mms.Addr.CHARSET, UTF8_MIB)
        }
        resolver.insert(Uri.parse("content://mms/$mmsId/addr"), values)
    }

    private fun addPart(mmsId: Long, index: Int, part: MmsPart) {
        val inline = part.contentType.equals(TEXT_PLAIN, true) || part.contentType.equals(SMIL, true)
        val values = ContentValues().apply {
            put(Mms.Part.SEQ, index)
            put(Mms.Part.CONTENT_TYPE, part.contentType)
            part.name?.let { put(Mms.Part.NAME, it) }
            part.contentId?.let { put(Mms.Part.CONTENT_ID, "<$it>") }
            part.contentLocation?.let { put(Mms.Part.CONTENT_LOCATION, it) }
            part.charset?.let { put(Mms.Part.CHARSET, it) }
            if (inline) put(Mms.Part.TEXT, part.text)
        }
        val uri = resolver.insert(Uri.parse("content://mms/$mmsId/part"), values) ?: throw IOException("Pièce jointe non enregistrée")
        if (!inline) (resolver.openOutputStream(uri) ?: throw IOException("Pièce jointe non enregistrée")).use { it.write(part.data) }
    }

    fun markRead(threadId: Long) {
        val values = readValues(true)
        val args = arrayOf(threadId.toString())
        resolver.update(Sms.Inbox.CONTENT_URI, values, "thread_id = ? AND (read = 0 OR seen = 0)", args)
        resolver.update(Mms.Inbox.CONTENT_URI, values, "thread_id = ? AND (read = 0 OR seen = 0)", args)
    }

    /** Le texte n'est modifiable que sur un SMS : un MMS est fait de parties. */
    fun updateSmsBody(id: Long, body: String): Int =
        resolver.update(ContentUris.withAppendedId(Sms.CONTENT_URI, id), ContentValues().apply { put(Sms.BODY, body) }, null, null)

    fun setRead(kind: String, id: Long, read: Boolean): Int =
        resolver.update(messageUri(kind, id), readValues(read), null, null)

    /** « Non lu » ne vise que le dernier message reçu, comme dans une messagerie : le reste du fil a bien été lu. */
    fun markConversationUnread(threadId: Long): Int {
        val latest = messages(threadId).lastOrNull { it.box == MessageBox.INCOMING } ?: return 0
        return setRead(latest.kind, latest.id, read = false)
    }

    fun delete(kind: String, id: Long): Int = resolver.delete(messageUri(kind, id), null, null)

    fun deleteSms(id: Long) {
        delete(KIND_SMS, id)
    }

    fun deleteConversation(threadId: Long): Int =
        resolver.delete(Uri.parse("content://mms-sms/conversations/$threadId"), null, null)

    private fun messageUri(kind: String, id: Long): Uri =
        ContentUris.withAppendedId(if (kind == KIND_MMS) Mms.CONTENT_URI else Sms.CONTENT_URI, id)

    private fun readValues(read: Boolean) = ContentValues().apply {
        put("read", if (read) 1 else 0)
        put("seen", if (read) 1 else 0)
    }

    /** Android range sujet et extraits des MMS comme des octets déguisés en ISO-8859-1. */
    private fun decodeLegacy(value: String?, charset: Int): String =
        if (value == null || charset == 0) value.orEmpty() else decodeText(value.toByteArray(Charsets.ISO_8859_1), charset)

    private fun encodeLegacy(value: String): String = String(value.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)

    private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))
    private fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))
    private fun Cursor.str(column: String): String? = getString(getColumnIndexOrThrow(column))

    companion object {
        const val KIND_SMS = "sms"
        const val KIND_MMS = "mms"
        private const val HISTORY = 500
        private const val M_SEND = 128
        private const val M_NOTIFICATION = 130
        private const val M_RETRIEVED = 132
        private const val ADDRESS_FROM = 137
        private const val ADDRESS_TO = 151
        private const val UTF8_MIB = 106
        private const val TEXT_PLAIN = "text/plain"
        private const val SMIL = "application/smil"
        private const val UNKNOWN_SENDER = "Inconnu"
        private val CONVERSATIONS = Uri.parse("content://mms-sms/conversations?simple=true")
        private val CANONICAL_ADDRESSES = Uri.parse("content://mms-sms/canonical-addresses")
        private val OBSERVED = listOf(Uri.parse("content://sms"), Uri.parse("content://mms"), Uri.parse("content://mms-sms"))
        private val SMS_COLUMNS = arrayOf(Sms._ID, Sms.THREAD_ID, Sms.ADDRESS, Sms.BODY, Sms.SUBJECT, Sms.DATE, Sms.DATE_SENT, Sms.TYPE, Sms.SUBSCRIPTION_ID, Sms.READ)
        private val MMS_COLUMNS = arrayOf(Mms._ID, Mms.THREAD_ID, Mms.SUBJECT, Mms.SUBJECT_CHARSET, Mms.DATE, Mms.DATE_SENT, Mms.MESSAGE_BOX, Mms.MESSAGE_TYPE, Mms.SUBSCRIPTION_ID, Mms.READ)
        private val PART_COLUMNS = arrayOf(Mms.Part._ID, Mms.Part.CONTENT_TYPE, Mms.Part.TEXT, Mms.Part.NAME, Mms.Part.CONTENT_LOCATION)
    }
}
