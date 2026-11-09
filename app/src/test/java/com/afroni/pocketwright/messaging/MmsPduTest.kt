package com.afroni.pocketwright.messaging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Des messages écrits octet par octet d'après OMA-TS-MMS_ENC et WAP-230-WSP. */
class MmsPduTest {

    private fun pdu(vararg items: Any): ByteArray = ByteArrayOutputStream().apply {
        items.forEach { item ->
            when (item) {
                is Int -> write(item)
                is String -> { write(item.toByteArray()); write(0) }
                is ByteArray -> write(item)
                else -> error("Type inattendu")
            }
        }
    }.toByteArray()

    private val from = pdu(0x80, "+33639980001/TYPE=PLMN")

    @Test
    fun `lit un avis d'arrivee`() {
        val data = pdu(
            0x8C, 0x82,
            0x98, "TX1",
            0x8D, 0x92,
            0x89, from.size, from,
            0x96, "Bonjour",
            0x8A, 0x80,
            0x8E, 0x02, 0x12, 0x34,
            0x88, 0x05, 0x81, 0x03, 0x01, 0x51, 0x80,
            0x83, "http://mmsc.example/abc",
        )
        val notice = MmsPdu.parseNotification(data, nowSeconds = 1_000)
        assertNotNull(notice)
        assertEquals("TX1", notice!!.transactionId)
        assertEquals("http://mmsc.example/abc", notice.contentLocation)
        assertEquals("+33639980001", notice.from)
        assertEquals("Bonjour", notice.subject)
        assertEquals(0x1234L, notice.size)
        assertEquals(1_000L + 86_400L, notice.expiry)
        assertEquals(0x12, notice.version)
    }

    @Test
    fun `lit un MMS complet avec texte accentue et image`() {
        val subject = pdu(0xEA, "Été".toByteArray(), 0)
        val textBody = "Salut à toi".toByteArray()
        val image = byteArrayOf(1, 2, 3, 4, 5)

        // text/plain, charset UTF-8, puis Content-ID et Content-Location.
        val textType = pdu(0x03, 0x83, 0x81, 0xEA)
        val textHead = pdu(textType, 0xC0, "\"<t1>", 0x8E, "text.txt")

        // image/jpeg, nom a.jpg.
        val imageParams = pdu(0x9E, 0x85, "a.jpg")
        val imageHead = pdu(imageParams.size, imageParams)

        // Le premier type est écrit en toutes lettres : c'est le cas du SMIL dans les vrais MMS.
        val smil = "<smil></smil>".toByteArray()
        val smilType = pdu("application/smil")

        val related = pdu(0xB3, 0x89, "application/smil", 0x8A, "<s>")
        val data = pdu(
            0x8C, 0x84,
            0x98, "TX2",
            0x8D, 0x92,
            0x85, 0x04, 0x5F, 0x00, 0x00, 0x00,
            0x89, from.size, from,
            0x97, "+33639980002/TYPE=PLMN",
            0x96, subject.size, subject,
            0x8B, "mid-1",
            0x84, related.size, related,
            0x03,
            smilType.size, smil.size, smilType, smil,
            textHead.size, textBody.size, textHead, textBody,
            imageHead.size, image.size, imageHead, image,
        )
        val mms = MmsPdu.parseRetrieved(data)
        assertNotNull(mms)
        assertEquals("TX2", mms!!.transactionId)
        assertEquals("mid-1", mms.messageId)
        assertEquals("+33639980001", mms.from)
        assertEquals(listOf("+33639980002"), mms.to)
        assertEquals("Été", mms.subject)
        assertEquals(0x5F000000L, mms.date)
        assertEquals("application/vnd.wap.multipart.related", mms.contentType)
        assertEquals(3, mms.parts.size)
        assertEquals("application/smil", mms.parts[0].contentType)
        assertEquals("text/plain", mms.parts[1].contentType)
        assertEquals("Salut à toi", mms.parts[1].text)
        assertEquals("t1", mms.parts[1].contentId)
        assertEquals("text.txt", mms.parts[1].contentLocation)
        assertEquals("image/jpeg", mms.parts[2].contentType)
        assertEquals("a.jpg", mms.parts[2].name)
        assertArrayEquals(image, mms.parts[2].data)
    }

    @Test
    fun `lit une partie dont la longueur tient sur plusieurs octets`() {
        val body = ByteArray(300) { 7 }
        val type = pdu(0x9E)
        val related = pdu(0xA3)
        val data = pdu(0x8C, 0x84, 0x98, "T", 0x84, related, 0x01, type.size, 0x82, 0x2C, type, body)
        val mms = MmsPdu.parseRetrieved(data)
        assertEquals(300, mms!!.parts.single().data.size)
    }

    @Test
    fun `refuse sans planter un message tronque ou d'un autre type`() {
        assertNull(MmsPdu.parseNotification(ByteArray(0)))
        assertNull(MmsPdu.parseNotification(pdu(0x8C, 0x82, 0x98)))
        assertNull(MmsPdu.parseRetrieved(pdu(0x8C, 0x82, 0x98, "TX")))
        // Une partie annonce plus d'octets qu'il n'y en a.
        assertNull(MmsPdu.parseRetrieved(pdu(0x8C, 0x84, 0x84, 0xA3, 0x01, 0x01, 0x7F, 0x9E)))
    }

    @Test
    fun `ecrit l'accuse de reception`() {
        val expected = pdu(0x8C, 0x83, 0x98, "TX", 0x8D, 0x92, 0x95, 0x81)
        assertArrayEquals(expected, MmsPdu.notifyResponse("TX", 0x12))
    }
}
