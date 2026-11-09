package com.afroni.pocketwright.messaging

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/** Un avis d'arrivée de MMS : le contenu reste chez l'opérateur, à l'adresse [contentLocation]. */
class MmsNotification(
    val transactionId: String,
    val contentLocation: String,
    val from: String?,
    val subject: String?,
    val size: Long?,
    /** En secondes depuis 1970. */
    val expiry: Long?,
    val version: Int?,
)

class MmsPart(
    val contentType: String,
    val name: String?,
    val contentId: String?,
    val contentLocation: String?,
    val charset: Int?,
    val data: ByteArray,
) {
    val text: String get() = decodeText(data, charset)
}

/** Le MMS lui-même, tel que téléchargé chez l'opérateur. */
class MmsRetrieved(
    val transactionId: String?,
    val messageId: String?,
    val from: String?,
    val to: List<String>,
    val subject: String?,
    /** En secondes depuis 1970. */
    val date: Long?,
    val version: Int?,
    val contentType: String,
    val parts: List<MmsPart>,
)

internal fun decodeText(data: ByteArray, mib: Int?): String = String(data, charsetFor(mib))

internal fun charsetFor(mib: Int?): Charset = when (mib) {
    3 -> Charsets.US_ASCII
    4 -> Charsets.ISO_8859_1
    1000 -> Charsets.UTF_16BE
    1015 -> Charsets.UTF_16
    else -> Charsets.UTF_8
}

/**
 * Le strict nécessaire du format binaire des MMS (OMA-TS-MMS_ENC et WAP-230-WSP). Android ne publie pas son
 * propre décodeur : toute messagerie doit avoir le sien.
 */
object MmsPdu {
    private const val MESSAGE_NOTIFICATION = 0x82
    private const val MESSAGE_RETRIEVED = 0x84

    fun parseNotification(pdu: ByteArray, nowSeconds: Long = System.currentTimeMillis() / 1000): MmsNotification? = runCatching {
        val h = Reader(pdu).headers(nowSeconds)
        if (h.type != MESSAGE_NOTIFICATION) return null
        MmsNotification(h.transactionId ?: return null, h.contentLocation ?: return null, h.from, h.subject, h.size, h.expiry, h.version)
    }.getOrNull()

    fun parseRetrieved(pdu: ByteArray): MmsRetrieved? = runCatching {
        val r = Reader(pdu)
        val h = r.headers(0)
        if (h.type != MESSAGE_RETRIEVED) return null
        val type = h.contentType ?: return null
        val parts = if (type.media.contains("multipart")) r.parts() else listOf(r.remainingPart(type))
        MmsRetrieved(h.transactionId, h.messageId, h.from, h.to, h.subject, h.date, h.version, type.media, parts)
    }.getOrNull()

    /** L'accusé de réception (M-NotifyResp.ind, état « reçu ») : sans lui, certains opérateurs renvoient l'avis. */
    fun notifyResponse(transactionId: String, version: Int?): ByteArray = ByteArrayOutputStream().apply {
        write(0x8C)
        write(0x83)
        write(0x98)
        write(transactionId.toByteArray())
        write(0)
        write(0x8D)
        write(0x80 or (version ?: 0x12))
        write(0x95)
        write(0x81)
    }.toByteArray()

    private class ParsedType(val media: String, val charset: Int? = null, val name: String? = null)

    private class Headers {
        var type = -1
        var transactionId: String? = null
        var contentLocation: String? = null
        var messageId: String? = null
        var from: String? = null
        val to = mutableListOf<String>()
        var subject: String? = null
        var size: Long? = null
        var expiry: Long? = null
        var date: Long? = null
        var version: Int? = null
        var contentType: ParsedType? = null
    }

    private class Reader(val bytes: ByteArray, var pos: Int = 0, val end: Int = bytes.size) {
        val more get() = pos < end

        fun peek(): Int {
            check(pos < end) { "PDU tronqué" }
            return bytes[pos].toInt() and 0xFF
        }

        fun u8(): Int = peek().also { pos++ }

        fun uintvar(): Long {
            var value = 0L
            repeat(5) {
                val b = u8()
                value = (value shl 7) or (b and 0x7F).toLong()
                if (b and 0x80 == 0) return value
            }
            error("Entier trop long")
        }

        fun valueLength(): Int {
            val first = u8()
            val length = if (first == 31) uintvar().toInt() else first
            check(first <= 31 && length >= 0 && pos + length <= end) { "Longueur invalide" }
            return length
        }

        fun longInteger(): Long {
            val count = u8()
            check(count in 1..8) { "Entier long invalide" }
            var value = 0L
            repeat(count) { value = (value shl 8) or u8().toLong() }
            return value
        }

        /** Entier court (bit de poids fort à 1) ou long. */
        fun integer(): Long = if (peek() >= 0x80) (u8() and 0x7F).toLong() else longInteger()

        private fun rawText(): ByteArray {
            if (peek() == 0x7F) pos++
            val start = pos
            while (peek() != 0) pos++
            return bytes.copyOfRange(start, pos++)
        }

        fun text(): String = String(rawText(), Charsets.UTF_8).trim('"')

        fun encodedString(): String {
            if (peek() > 31) return text()
            val stop = valueLength() + pos
            val charset = integer().toInt()
            val value = decodeText(rawText(), charset)
            pos = stop
            return value
        }

        fun skipValue() {
            val first = peek()
            when {
                first <= 31 -> { val length = valueLength(); pos += length }
                first < 0x80 -> rawText()
                else -> pos++
            }
        }

        fun headers(nowSeconds: Long): Headers {
            val h = Headers()
            while (more) {
                val field = u8()
                if (field < 0x80) {
                    // En-tête d'application : un nom puis une valeur, en texte.
                    pos--
                    rawText()
                    rawText()
                    continue
                }
                when (field and 0x7F) {
                    0x03 -> h.contentLocation = text()
                    0x04 -> { h.contentType = contentType(); return h }
                    0x05 -> h.date = longInteger()
                    0x08 -> h.expiry = expiry(nowSeconds)
                    0x09 -> h.from = from()
                    0x0B -> h.messageId = text()
                    0x0C -> h.type = u8()
                    0x0D -> h.version = u8() and 0x7F
                    0x0E -> h.size = longInteger()
                    0x16 -> h.subject = encodedString()
                    0x17 -> h.to += address(encodedString())
                    0x18 -> h.transactionId = text()
                    else -> skipValue()
                }
            }
            return h
        }

        private fun expiry(nowSeconds: Long): Long {
            val stop = valueLength() + pos
            val token = u8()
            val value = if (token == 0x80) longInteger() else nowSeconds + integer()
            pos = stop
            return value
        }

        private fun from(): String? {
            val stop = valueLength() + pos
            val value = if (u8() == 0x80) address(encodedString()) else null
            pos = stop
            return value
        }

        private fun address(raw: String) = raw.substringBefore("/TYPE=")

        fun contentType(): ParsedType {
            val first = peek()
            if (first >= 0x80) return ParsedType(wellKnownType(u8() and 0x7F))
            if (first > 31) return ParsedType(text())
            val stop = valueLength() + pos
            val inner = Reader(bytes, pos, stop)
            pos = stop
            val media = when {
                inner.peek() >= 0x80 -> wellKnownType(inner.u8() and 0x7F)
                inner.peek() <= 31 -> wellKnownType(inner.longInteger().toInt())
                else -> inner.text()
            }
            var charset: Int? = null
            var name: String? = null
            while (inner.more) {
                if (inner.peek() >= 0x80) {
                    when (inner.u8() and 0x7F) {
                        0x01 -> charset = inner.integer().toInt().takeIf { it != 0 }
                        0x05, 0x17, 0x18 -> name = inner.text()
                        else -> inner.skipValue()
                    }
                } else {
                    when (inner.text().lowercase()) {
                        "name", "filename" -> name = inner.text()
                        else -> inner.skipValue()
                    }
                }
            }
            return ParsedType(media, charset, name?.takeIf(String::isNotEmpty))
        }

        fun parts(): List<MmsPart> {
            val count = uintvar().toInt()
            check(count in 0..1000) { "Trop de parties" }
            return List(count) {
                val headerLength = uintvar().toInt()
                val dataLength = uintvar().toInt()
                check(headerLength >= 0 && dataLength >= 0 && pos + headerLength + dataLength <= end) { "Partie tronquée" }
                val head = Reader(bytes, pos, pos + headerLength)
                val type = head.contentType()
                var contentId: String? = null
                var location: String? = null
                while (head.more) {
                    if (head.peek() >= 0x80) {
                        when (head.u8() and 0x7F) {
                            0x0E -> location = head.text()
                            0x40 -> contentId = head.text().trim('<', '>')
                            else -> head.skipValue()
                        }
                    } else {
                        val header = head.text().lowercase()
                        val value = head.text()
                        if (header == "content-id") contentId = value.trim('<', '>')
                        if (header == "content-location") location = value
                    }
                }
                val start = pos + headerLength
                pos = start + dataLength
                MmsPart(type.media, type.name, contentId, location, type.charset, bytes.copyOfRange(start, pos))
            }
        }

        fun remainingPart(type: ParsedType): MmsPart =
            MmsPart(type.media, type.name, null, null, type.charset, bytes.copyOfRange(pos, end))

        private fun wellKnownType(code: Int): String = WELL_KNOWN_TYPES[code] ?: "application/octet-stream"
    }

    /** Les types assignés par WSP qu'on croise dans les MMS ; les autres arrivent écrits en toutes lettres. */
    private val WELL_KNOWN_TYPES = mapOf(
        0x02 to "text/html",
        0x03 to "text/plain",
        0x07 to "text/x-vCard",
        0x1D to "image/gif",
        0x1E to "image/jpeg",
        0x1F to "image/tiff",
        0x20 to "image/png",
        0x21 to "image/vnd.wap.wbmp",
        0x23 to "application/vnd.wap.multipart.mixed",
        0x26 to "application/vnd.wap.multipart.alternative",
        0x33 to "application/vnd.wap.multipart.related",
    )
}
