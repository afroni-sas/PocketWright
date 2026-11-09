package com.afroni.pocketwright.update

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * La version de `playwright-core` du moteur embarqué : du JavaScript pur, téléchargé depuis le registre npm
 * officiel et vérifié par son empreinte. Une version qui empêche le moteur de démarrer est écartée.
 */
class PlaywrightUpdater(context: Context) {
    private val prefs = context.getSharedPreferences("pocketwright-engine", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "playwright")

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _available = MutableStateFlow<Available?>(null)
    val available: StateFlow<Available?> = _available.asStateFlow()

    val installedVersion: String? get() = prefs.getString(KEY_ACTIVE, null)
    val lastFailure: String? get() = prefs.getString(KEY_FAILURE, null)

    /** Le dossier de la version téléchargée à utiliser ; null pour celle livrée avec l'app. */
    fun corePath(): File? {
        val version = installedVersion ?: return null
        val attempts = prefs.getInt(KEY_ATTEMPTS, 0)
        if (attempts >= MAX_ATTEMPTS) {
            disable("Playwright $version n'a pas pu démarrer ($attempts essais)")
            return null
        }
        val path = File(root, "$version/package")
        if (!File(path, "index.js").exists()) {
            disable("Playwright $version est incomplet")
            return null
        }
        prefs.edit().putInt(KEY_ATTEMPTS, attempts + 1).commit()
        return path
    }

    fun confirmHealthy() {
        val active = installedVersion ?: return
        prefs.edit().putInt(KEY_ATTEMPTS, 0).commit()
        root.listFiles()?.filter { it.isDirectory && it.name != active }?.forEach { it.deleteRecursively() }
    }

    /** Cherche la dernière version, ou [wanted] pour en choisir une précise (y compris plus ancienne). */
    suspend fun check(currentVersion: String?, nodeMajor: Int?, wanted: String? = null): Available? {
        if (wanted == null && _state.value.locked) return _available.value
        _state.value = UpdateState.Checking
        try {
            val latest = Downloads.json("$REGISTRY_URL/${wanted ?: "latest"}")
            val version = latest["version"]!!.jsonPrimitive.content
            val required = latest["engines"]?.jsonObject?.get("node")?.jsonPrimitive?.contentOrNull
                ?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
            if (required != null && nodeMajor != null && required > nodeMajor) {
                throw IOException("Playwright $version demande Node $required ; l'app embarque Node $nodeMajor")
            }
            val dist = latest["dist"]!!.jsonObject
            val found = Available(version, dist["tarball"]!!.jsonPrimitive.content, dist["unpackedSize"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0, dist["integrity"]?.jsonPrimitive?.contentOrNull)
            _available.value = found.takeUnless { it.version == (installedVersion ?: currentVersion) }
            _state.value = UpdateState.Idle
            return _available.value
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.value = UpdateState.Idle
            throw e
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(e.message ?: e.toString())
            throw e
        }
    }

    suspend fun install(target: Available) = withContext(Dispatchers.IO) {
        try {
            root.mkdirs()
            val archive = File(root, "${target.version}.tgz")
            archive.delete()
            Downloads.file(target.url, archive, 0) { _state.value = UpdateState.Downloading(it) }
            _state.value = UpdateState.Installing
            verify(archive, target.checksum)
            val dir = File(root, target.version)
            dir.deleteRecursively()
            extract(archive, dir)
            archive.delete()
            if (!File(dir, "package/index.js").exists()) throw IOException("Archive Playwright inattendue")
            prefs.edit().putString(KEY_ACTIVE, target.version).putInt(KEY_ATTEMPTS, 0).remove(KEY_FAILURE).commit()
            _available.value = null
            _state.value = UpdateState.RestartNeeded(target.version)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Installation de Playwright échouée", e)
            _state.value = UpdateState.Failed(e.message ?: e.toString())
            throw e
        }
    }

    /** Revient à la version livrée avec l'app au prochain démarrage. */
    fun useBundled() {
        prefs.edit().remove(KEY_ACTIVE).putInt(KEY_ATTEMPTS, 0).remove(KEY_FAILURE).commit()
        _state.value = UpdateState.RestartNeeded("intégrée")
    }

    private fun disable(reason: String) {
        Log.e(TAG, reason)
        prefs.edit().remove(KEY_ACTIVE).putInt(KEY_ATTEMPTS, 0).putString(KEY_FAILURE, reason).commit()
    }

    /** `integrity` du registre npm : `sha512-<base64>`. */
    private fun verify(archive: File, integrity: String?) {
        val expected = integrity?.takeIf { it.startsWith("sha512-") }?.removePrefix("sha512-") ?: throw IOException("Pas d'empreinte dans le registre npm")
        val actual = Base64.encodeToString(Downloads.digest(archive, "SHA-512"), Base64.NO_WRAP)
        if (actual != expected) {
            archive.delete()
            throw IOException("Archive corrompue : l'empreinte ne correspond pas")
        }
    }

    /** Déballe l'archive tar.gz, sans les outils de développement (trace viewer, rapports, types). */
    private fun extract(archive: File, target: File) {
        val root = target.canonicalPath + File.separator
        GZIPInputStream(BufferedInputStream(archive.inputStream())).use { input ->
            val header = ByteArray(512)
            while (true) {
                if (!readFully(input, header)) break
                if (header.all { it == 0.toByte() }) break
                val name = cString(header, 345, 155).let { prefix -> (if (prefix.isEmpty()) "" else "$prefix/") + cString(header, 0, 100) }
                val size = cString(header, 124, 12).trim().ifEmpty { "0" }.toLong(8)
                val type = header[156].toInt().toChar()
                val out = File(target, name)
                // Une archive ne doit rien écrire hors de son dossier.
                if (!out.canonicalPath.startsWith(root)) throw IOException("Chemin d'archive refusé : $name")
                val skipped = SKIPPED.any { name.startsWith(it) }
                if ((type == '0' || type == '\u0000') && !skipped) {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { copy(input, it, size) }
                } else {
                    copy(input, null, size)
                }
                val padding = (512 - size % 512) % 512
                copy(input, null, padding)
            }
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun copy(input: InputStream, output: java.io.OutputStream?, count: Long) {
        val buffer = ByteArray(64 * 1024)
        var left = count
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (n < 0) throw IOException("Archive tronquée")
            output?.write(buffer, 0, n)
            left -= n
        }
    }

    private fun cString(bytes: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && bytes[end] != 0.toByte()) end++
        return String(bytes, offset, end - offset, Charsets.UTF_8)
    }

    private companion object {
        const val TAG = "PocketWright"
        const val KEY_ACTIVE = "playwright_active"
        const val KEY_ATTEMPTS = "playwright_attempts"
        const val KEY_FAILURE = "playwright_failure"
        const val MAX_ATTEMPTS = 3
        const val REGISTRY_URL = "https://registry.npmjs.org/playwright-core"
        val SKIPPED = listOf("package/lib/vite/", "package/types/")
    }
}
