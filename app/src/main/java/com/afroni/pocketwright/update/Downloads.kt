package com.afroni.pocketwright.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Un serveur a refusé la demande. Le message s'affiche tel quel dans l'app. */
private class HttpFailure(code: Int, host: String) : IOException(
    when {
        code == 403 || code == 429 -> "$host limite le nombre de demandes. Réessayez dans une heure."
        code >= 500 -> "$host ne répond pas pour le moment (erreur $code). Réessayez plus tard."
        else -> "$host a répondu par l'erreur $code."
    },
) {
    val temporary = code == 408 || code >= 500
}

/** Les accès réseau des mises à jour : un document JSON, ou un fichier qui reprend là où il s'était arrêté. */
internal object Downloads {
    private const val USER_AGENT = "PocketWright"

    /** Lit un document JSON. Un serveur en panne ou une coupure passagère ne coûtent qu'un nouvel essai. */
    suspend fun json(url: String): JsonObject = retried(url) { Json.parseToJsonElement(readText(url)).jsonObject }

    suspend fun text(url: String, accept: String = "*/*"): String = retried(url) { readText(url, accept) }

    /** L'adresse de téléchargement, temporaire, d'un fichier stocké en Git LFS. */
    suspend fun lfsDownloadUrl(endpoint: String, oid: String, size: Long): String = retried(endpoint) {
        val batch = "$endpoint/objects/batch"
        val connection = (URL(batch).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 25_000
            doOutput = true
            requestMethod = "POST"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", LFS_JSON)
            setRequestProperty("Content-Type", LFS_JSON)
        }
        try {
            connection.outputStream.use {
                it.write("""{"operation":"download","transfers":["basic"],"objects":[{"oid":"$oid","size":$size}]}""".toByteArray())
            }
            if (connection.responseCode != 200) throw HttpFailure(connection.responseCode, URL(batch).host)
            val body = Json.parseToJsonElement(connection.inputStream.use { it.readBytes().decodeToString() }).jsonObject
            val entry = body["objects"]?.jsonArray?.firstOrNull()?.jsonObject
            (entry?.get("actions") as? JsonObject)?.get("download")?.jsonObject?.get("href")?.jsonPrimitive?.content
                ?: throw IOException("Le serveur du moteur n'a pas fourni le fichier")
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun <T> retried(url: String, read: () -> T): T {
        var attempt = 0
        while (true) {
            val failure = try {
                return withContext(Dispatchers.IO) { read() }
            } catch (e: HttpFailure) {
                if (!e.temporary) throw e
                e
            } catch (e: IOException) {
                IOException("Impossible de joindre ${URL(url).host} : vérifiez la connexion.", e)
            }
            if (++attempt >= JSON_ATTEMPTS) throw failure
            delay(JSON_RETRY_MS * attempt)
        }
    }

    private fun readText(url: String, accept: String = JSON): String {
        val connection = open(url, accept = accept)
        try {
            if (connection.responseCode != 200) throw HttpFailure(connection.responseCode, URL(url).host)
            return connection.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            connection.disconnect()
        }
    }

    /** Télécharge [url] dans [target] ; un fichier partiel déjà là est repris. [progress] va de 0 à 1. */
    suspend fun file(url: String, target: File, expectedSize: Long, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        var attempt = 0
        while (true) {
            try {
                fetch(url, target, expectedSize, progress)
                return@withContext
            } catch (e: IOException) {
                if (e is HttpFailure && !e.temporary) throw e
                // Le réseau mobile coupe : on reprend à l'octet près, quelques fois.
                if (++attempt >= MAX_ATTEMPTS) throw e
                delay(2_000L * attempt)
            }
        }
    }

    private suspend fun fetch(url: String, target: File, expectedSize: Long, progress: (Float) -> Unit) {
        val already = target.length()
        // L'app a été tuée juste après la fin du téléchargement : il n'y a plus rien à demander.
        if (expectedSize > 0 && already == expectedSize) return
        val connection = open(url, range = already.takeIf { it > 0 }, binary = true)
        try {
            val code = connection.responseCode
            val resuming = code == 206
            if (code == 416) {
                // Le fichier partiel ne correspond plus à celui du serveur : on repart de zéro à l'essai suivant.
                target.delete()
                throw IOException("Téléchargement à reprendre depuis le début")
            }
            if (code != 200 && !resuming) throw HttpFailure(code, URL(url).host)
            val total = if (expectedSize > 0) expectedSize else connection.contentLengthLong + (if (resuming) already else 0)
            var done = if (resuming) already else 0L
            connection.inputStream.use { input ->
                java.io.FileOutputStream(target, resuming).use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) progress((done.toFloat() / total).coerceAtMost(1f))
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, range: Long? = null, binary: Boolean = false, accept: String = if (binary) "application/octet-stream" else JSON): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = if (binary) 60_000 else 25_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", accept)
            range?.let { setRequestProperty("Range", "bytes=$it-") }
        }

    fun digest(file: File, algorithm: String): ByteArray {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest()
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private const val JSON = "application/json"
    private const val LFS_JSON = "application/vnd.git-lfs+json"
    private const val BUFFER = 256 * 1024
    private const val MAX_ATTEMPTS = 8
    private const val JSON_ATTEMPTS = 3
    private const val JSON_RETRY_MS = 2_500L
}
