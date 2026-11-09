package com.afroni.pocketwright.api

import android.content.ContentUris
import android.util.Base64
import com.afroni.pocketwright.messaging.Conversation
import com.afroni.pocketwright.messaging.Message
import com.afroni.pocketwright.messaging.MessageStore
import com.afroni.pocketwright.messaging.Messaging
import com.afroni.pocketwright.session.ApiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Le CRUD des messages, par le WebSocket `/messages/stream`. Même format de commande que `/ws`. */
class MessageCommands(private val messaging: Messaging) {
    private val store get() = messaging.store

    suspend fun run(action: String, params: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        when (action) {
            "send" -> send(params)
            "conversations" -> conversations(params)
            "messages" -> messages(params)
            "message" -> found(target(params).let { (kind, id) -> store.load(kind, id) }).toJson()
            "attachment" -> attachment(params)
            "update" -> update(params)
            "delete" -> delete(params)
            "deleteConversation" -> buildJsonObject { put("deleted", store.deleteConversation(params.requireLong("threadId"))) }
            else -> throw ApiException(404, "Action inconnue : « $action »")
        }
    }

    private suspend fun send(params: JsonObject): JsonElement {
        val text = params.text("text")?.takeIf(String::isNotBlank) ?: throw ApiException(400, "Champ « text » manquant")
        // Au-delà, un envoi par erreur coûterait des dizaines de SMS.
        if (text.length > MAX_TEXT) throw ApiException(400, "Texte trop long : $MAX_TEXT caractères au plus")
        val recipients = when (val to = params["to"]) {
            is JsonArray -> to.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(to.contentOrNull)
            else -> emptyList()
        }.map(String::trim).filter(String::isNotEmpty)
        if (recipients.isEmpty()) throw ApiException(400, "Champ « to » manquant")
        val subId = params.long("subId")?.toInt() ?: -1
        val sent = recipients.map { address ->
            val done = CompletableDeferred<Boolean>()
            val uri = messaging.send(address, text, subId, done)
            // Sans réponse du réseau à temps, le message est rendu tel quel : `box` vaut alors « sending ».
            withTimeoutOrNull(SEND_TIMEOUT_MS) { done.await() }
            found(store.sms(ContentUris.parseId(uri))).toJson()
        }
        return buildJsonObject { put("messages", JsonArray(sent)) }
    }

    private fun conversations(params: JsonObject): JsonElement {
        val limit = params.long("limit")?.toInt()?.coerceIn(1, MAX_CONVERSATIONS) ?: DEFAULT_CONVERSATIONS
        val offset = params.long("offset")?.toInt()?.coerceAtLeast(0) ?: 0
        return JsonArray(store.conversations().drop(offset).take(limit).map { it.toJson() })
    }

    private fun messages(params: JsonObject): JsonElement {
        val limit = params.long("limit")?.toInt()?.coerceIn(1, MAX_MESSAGES) ?: DEFAULT_MESSAGES
        return JsonArray(store.messages(params.requireLong("threadId"), limit, params.long("before")).map { it.toJson() })
    }

    private fun attachment(params: JsonObject): JsonElement {
        val partId = params.requireLong("partId")
        val (bytes, type) = messaging.attachment(partId) ?: throw ApiException(404, "Pièce jointe introuvable")
        return buildJsonObject {
            put("partId", partId)
            put("contentType", type)
            put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
    }

    private fun update(params: JsonObject): JsonElement {
        val read = params.boolean("read")
        val threadId = params.long("threadId")
        if (threadId != null) {
            when (read) {
                true -> messaging.markRead(threadId)
                false -> store.markConversationUnread(threadId)
                null -> throw ApiException(400, "Pour une conversation, seul « read » se modifie")
            }
            return buildJsonObject {
                put("threadId", threadId)
                put("read", read)
            }
        }
        val (kind, id) = target(params)
        val body = params.text("body")
        if (read == null && body == null) throw ApiException(400, "Rien à modifier : « read » ou « body »")
        found(store.load(kind, id))
        if (body != null) {
            if (kind != MessageStore.KIND_SMS) throw ApiException(400, "Le texte d'un MMS ne se modifie pas")
            store.updateSmsBody(id, body)
        }
        read?.let { store.setRead(kind, id, it) }
        return found(store.load(kind, id)).toJson()
    }

    private fun delete(params: JsonObject): JsonElement {
        val (kind, id) = target(params)
        if (store.delete(kind, id) == 0) throw ApiException(404, "Message introuvable")
        return buildJsonObject { put("deleted", 1) }
    }

    /** Un message se désigne par `id`, et `kind` (« sms » par défaut). */
    private fun target(params: JsonObject): Pair<String, Long> {
        val kind = params.text("kind") ?: MessageStore.KIND_SMS
        if (kind != MessageStore.KIND_SMS && kind != MessageStore.KIND_MMS) throw ApiException(400, "« kind » vaut sms ou mms")
        return kind to params.requireLong("id")
    }

    private fun found(message: Message?): Message = message ?: throw ApiException(404, "Message introuvable")

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.requireLong(key: String): Long = long(key) ?: throw ApiException(400, "Champ « $key » manquant")

    private fun Conversation.toJson() = buildJsonObject {
        put("id", id)
        put("title", title)
        put("addresses", JsonArray(addresses.map(::JsonPrimitive)))
        put("snippet", snippet)
        put("date", date)
        put("unread", unread)
    }

    companion object {
        private const val MAX_TEXT = 1600
        private const val SEND_TIMEOUT_MS = 60_000L
        private const val DEFAULT_CONVERSATIONS = 100
        private const val MAX_CONVERSATIONS = 1000
        private const val DEFAULT_MESSAGES = 50
        private const val MAX_MESSAGES = 500

        /** Le même objet pour le flux et les réponses aux commandes. */
        fun Message.toJson(): JsonObject = buildJsonObject {
            put("kind", kind)
            put("id", id)
            put("threadId", threadId)
            put("from", address)
            put("box", box.name.lowercase())
            put("read", read)
            put("body", body)
            subject?.let { put("subject", it) }
            put("date", date)
            put("dateSent", dateSent)
            put("subId", subId)
            if (kind == MessageStore.KIND_MMS) {
                put("downloaded", !pending)
                put("attachments", JsonArray(attachments.map { part ->
                    buildJsonObject {
                        put("partId", part.partId)
                        put("contentType", part.contentType)
                        part.name?.let { put("name", it) }
                        put("url", "/messages/parts/${part.partId}")
                    }
                }))
            }
        }
    }
}
