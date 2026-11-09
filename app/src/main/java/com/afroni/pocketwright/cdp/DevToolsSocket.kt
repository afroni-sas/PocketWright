package com.afroni.pocketwright.cdp

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.util.Base64
import com.afroni.pocketwright.session.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Une connexion WebSocket au DevTools des WebViews de l'app, par la socket abstraite
 * `webview_devtools_remote_<pid>`. Chromium l'ouvre au processus de même UID, c'est-à-dire à l'app elle-même
 * (content/browser/android/devtools_auth.cc), comme à `adb`.
 *
 * Le protocole WebSocket est fait à la main : c'est ce que fait aussi Playwright pour Android.
 */
class DevToolsSocket private constructor(
    private val socket: LocalSocket,
    private val input: DataInputStream,
    private val output: OutputStream,
) {
    private val random = SecureRandom()
    @Volatile var isOpen = true
        private set

    /** Lit les messages sur un fil à soi ; [onClose] est appelé une fois, quand la connexion se termine. */
    fun start(onMessage: (String) -> Unit, onClose: (Throwable?) -> Unit) {
        thread(name = "devtools-reader", isDaemon = true) {
            var error: Throwable? = null
            try {
                val message = ByteArrayOutputStream()
                while (isOpen) {
                    val b0 = input.readUnsignedByte()
                    val b1 = input.readUnsignedByte()
                    val fin = b0 and 0x80 != 0
                    val opcode = b0 and 0x0F
                    var length = (b1 and 0x7F).toLong()
                    if (length == 126L) length = input.readUnsignedShort().toLong()
                    else if (length == 127L) length = input.readLong()
                    val mask = if (b1 and 0x80 != 0) ByteArray(4).also { input.readFully(it) } else null
                    if (length > MAX_MESSAGE) throw IOException("Message DevTools trop grand ($length octets)")
                    val payload = ByteArray(length.toInt())
                    input.readFully(payload)
                    if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                    when (opcode) {
                        0x0, 0x1, 0x2 -> {
                            message.write(payload)
                            if (fin) {
                                onMessage(message.toString(Charsets.UTF_8.name()))
                                message.reset()
                            }
                        }
                        0x8 -> break
                        0x9 -> writeFrame(0xA, payload)
                        else -> Unit
                    }
                }
            } catch (e: Throwable) {
                if (isOpen) error = e
            } finally {
                close()
                onClose(error)
            }
        }
    }

    fun send(text: String) {
        writeFrame(0x1, text.toByteArray(Charsets.UTF_8))
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        runCatching { writeFrame(0x8, ByteArray(0), force = true) }
        runCatching { socket.shutdownInput() }
        runCatching { socket.close() }
    }

    @Synchronized
    private fun writeFrame(opcode: Int, payload: ByteArray, force: Boolean = false) {
        if (!isOpen && !force) throw IOException("Connexion DevTools fermée")
        val header = ByteArrayOutputStream(14)
        header.write(0x80 or opcode)
        val n = payload.size
        when {
            n < 126 -> header.write(0x80 or n)
            n <= 0xFFFF -> { header.write(0x80 or 126); header.write(n ushr 8); header.write(n and 0xFF) }
            else -> {
                header.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) header.write(((n.toLong() ushr shift) and 0xFF).toInt())
            }
        }
        // Un client masque ses trames (RFC 6455).
        val mask = ByteArray(4).also { random.nextBytes(it) }
        header.write(mask)
        val masked = ByteArray(n) { i -> (payload[i].toInt() xor mask[i % 4].toInt()).toByte() }
        output.write(header.toByteArray())
        output.write(masked)
        output.flush()
    }

    companion object {
        private const val MAX_MESSAGE = 256L * 1024 * 1024

        val socketName: String get() = "webview_devtools_remote_${Process.myPid()}"

        /** Ouvre une connexion en réessayant : la socket n'apparaît qu'avec la première WebView. */
        suspend fun open(): DevToolsSocket = withContext(Dispatchers.IO) {
            var last: Exception? = null
            repeat(40) {
                try {
                    return@withContext connect()
                } catch (e: IOException) {
                    last = e
                    delay(250)
                }
            }
            throw ApiException(503, "DevTools des WebViews injoignable : ${last?.message}")
        }

        /** Ouvre la connexion au navigateur (`/devtools/browser`), qui voit toutes les WebViews de l'app. */
        private fun connect(path: String = "/devtools/browser"): DevToolsSocket {
            val socket = LocalSocket()
            socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
            val input = DataInputStream(socket.inputStream.buffered())
            val output = socket.outputStream
            val key = Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
            output.write(
                ("GET $path HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray(),
            )
            output.flush()
            val status = readHeaders(input)
            if (!status.startsWith("HTTP/1.1 101")) {
                socket.close()
                throw IOException("DevTools a refusé la connexion : $status")
            }
            return DevToolsSocket(socket, input, output)
        }

        /** Lit la réponse HTTP jusqu'à la ligne vide et rend sa ligne de statut. */
        private fun readHeaders(input: DataInputStream): String {
            val head = StringBuilder()
            var last4 = 0
            while (true) {
                val b = input.read()
                if (b < 0) throw IOException("DevTools a fermé la connexion")
                head.append(b.toChar())
                last4 = (last4 shl 8) or b
                if (last4 == 0x0D0A0D0A) break
                if (head.length > 16_384) throw IOException("Réponse DevTools invalide")
            }
            return head.lineSequence().first().trim()
        }
    }
}
