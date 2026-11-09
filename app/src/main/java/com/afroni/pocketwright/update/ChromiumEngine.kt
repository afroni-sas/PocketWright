package com.afroni.pocketwright.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.norman.webviewup.lib.WebViewReplace
import com.norman.webviewup.lib.util.ApksUtils
import com.norman.webviewup.lib.util.FileUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Une version disponible d'un composant. */
data class Available(val version: String, val url: String, val size: Long, val checksum: String?)

/** Où en est un composant qu'on peut mettre à jour. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data object Installing : UpdateState
    /** Installé : la nouvelle version démarrera avec l'app. */
    data class RestartNeeded(val version: String) : UpdateState
    data class Failed(val message: String) : UpdateState

    val installing: Boolean get() = this is Downloading || this is Installing

    /** Une installation est en cours, ou attend le redémarrage : une simple recherche ne doit pas l'effacer. */
    val locked: Boolean get() = installing || this is RestartNeeded
}

/**
 * Le moteur Chromium de l'app, indépendant du système. Android n'accepte comme WebView système qu'un paquet
 * signé par Google, livré par le Play Store : sur un téléphone sans Play Store, le moteur système reste figé.
 * L'app télécharge donc son propre moteur (la WebView Chromium compilée par LineageOS, qui garde WebGL et dont
 * le dépôt Git donne l'empreinte de chaque version) et le charge pour elle seule, sans rien installer sur le système
 * (bibliothèque WebViewUpgrade).
 *
 * Le chargement est protégé : au-delà de [MAX_ATTEMPTS] démarrages ratés, l'app revient au moteur du système.
 */
class ChromiumEngine(private val app: Context) {
    private val prefs = app.getSharedPreferences("pocketwright-engine", Context.MODE_PRIVATE)
    private val root = File(app.filesDir, "chromium")

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _available = MutableStateFlow<Available?>(null)
    val available: StateFlow<Available?> = _available.asStateFlow()

    /** La version de notre moteur chargée dans ce processus ; null si c'est celui du système. */
    @Volatile var loadedVersion: String? = null
        private set

    /** Le dossier de version effectivement chargé. */
    @Volatile private var loadedKey: String? = null

    /** Pourquoi notre moteur a été écarté au dernier démarrage. */
    val lastFailure: String? get() = prefs.getString(KEY_FAILURE, null)

    private val abiName: String?
        get() = when (Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a" -> "arm"
            "x86_64" -> "x86_64"
            else -> null
        }

    /**
     * À appeler au tout début du processus principal, avant la première WebView : une fois le moteur chargé,
     * le processus ne peut plus en changer.
     */
    fun loadAtStartup() {
        val version = prefs.getString(KEY_ACTIVE, null) ?: return
        val attempts = prefs.getInt(KEY_ATTEMPTS, 0)
        if (attempts >= MAX_ATTEMPTS) {
            disable("Le moteur Chromium $version a empêché l'app de démarrer ($attempts essais)")
            return
        }
        prefs.edit().putInt(KEY_ATTEMPTS, attempts + 1).commit()
        val dir = File(root, version)
        try {
            WebViewReplace.replace(app, File(dir, APK).path, File(dir, LIBS).path)
            loadedVersion = WebViewReplace.getReplaceWebViewVersion() ?: version
            loadedKey = version
            Log.i(TAG, "Moteur Chromium chargé : $loadedVersion")
        } catch (e: Throwable) {
            Log.e(TAG, "Moteur Chromium $version non chargé", e)
            disable("Le moteur Chromium $version n'a pas pu se charger : ${e.message}")
        }
    }

    /** L'app tourne avec ce moteur depuis un moment : il est bon, les précédents peuvent partir. */
    fun confirmHealthy() {
        val active = prefs.getString(KEY_ACTIVE, null) ?: return
        if (active != loadedKey) return
        prefs.edit().putInt(KEY_ATTEMPTS, 0).commit()
        root.listFiles()?.filter { it.isDirectory && it.name != active }?.forEach { it.deleteRecursively() }
    }

    suspend fun check(): Available? {
        if (_state.value.locked) return _available.value
        val abi = abiName ?: throw IOException("Architecture non prise en charge : ${Build.SUPPORTED_ABIS.firstOrNull()}")
        _state.value = UpdateState.Checking
        try {
            val repository = REPOSITORY + abi
            // Le flux public des commits : contrairement à l'API de GitHub, il n'a pas de quota par adresse IP.
            val feed = Downloads.text("https://github.com/LineageOS/$repository/commits/main/$APK_IN_REPOSITORY.atom", "application/atom+xml")
            val entry = ENTRY.find(feed)?.groupValues?.get(1) ?: throw IOException("Aucune publication du moteur pour $abi")
            val commit = COMMIT.find(entry)?.groupValues?.get(1) ?: throw IOException("Publication du moteur illisible")
            val version = TITLE.find(entry)?.groupValues?.get(1)?.let { VERSION.find(it)?.value } ?: throw IOException("Version du moteur illisible")
            // Le pointeur Git LFS, à ce commit précis : c'est lui qui donne l'empreinte attendue du fichier.
            val pointer = Downloads.text("https://raw.githubusercontent.com/LineageOS/$repository/$commit/$APK_IN_REPOSITORY")
            val oid = OID.find(pointer)?.groupValues?.get(1) ?: throw IOException("Empreinte du moteur illisible")
            val size = SIZE.find(pointer)?.groupValues?.get(1)?.toLongOrNull() ?: throw IOException("Taille du moteur illisible")
            val found = Available(version, "$LFS_HOST/LineageOS/$repository.git/info/lfs", size, oid)
            _available.value = found.takeUnless { isCurrent(it.version) }
            _state.value = UpdateState.Idle
            return _available.value
        } catch (e: CancellationException) {
            _state.value = UpdateState.Idle
            throw e
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(e.message ?: e.toString())
            throw e
        }
    }

    private fun isCurrent(version: String): Boolean = prefs.getString(KEY_ACTIVE, null) == version

    suspend fun install(target: Available) = withContext(Dispatchers.IO) {
        try {
            root.mkdirs()
            if (root.usableSpace < target.size * 2 + MIN_FREE_BYTES) throw IOException("Espace insuffisant pour installer le moteur")
            val partial = File(root, "${target.version}.part")
            // L'adresse de téléchargement est temporaire : on la demande au dernier moment.
            val address = Downloads.lfsDownloadUrl(target.url, requireNotNull(target.checksum), target.size)
            Downloads.file(address, partial, target.size) { _state.value = UpdateState.Downloading(it) }
            _state.value = UpdateState.Installing
            verify(partial, target)
            val dir = File(root, target.version)
            dir.deleteRecursively()
            dir.mkdirs()
            val apk = File(dir, APK)
            if (!partial.renameTo(apk)) throw IOException("Déplacement du moteur impossible")
            ApksUtils.extractNativeLibrary(apk.path, File(dir, LIBS).path)
            // Lu par les processus de rendu ; et Android refuse de charger du code depuis un fichier modifiable.
            FileUtils.makeFileWorldReadable(app, apk)
            prefs.edit().putString(KEY_ACTIVE, target.version).putInt(KEY_ATTEMPTS, 0).remove(KEY_FAILURE).commit()
            _available.value = null
            _state.value = UpdateState.RestartNeeded(target.version)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Installation du moteur échouée", e)
            _state.value = UpdateState.Failed(e.message ?: e.toString())
            throw e
        }
    }

    /** Vérifie le fichier reçu : l'empreinte du dépôt Git de LineageOS, puis la clé attendue. */
    private fun verify(apk: File, target: Available) {
        target.checksum?.let { expected ->
            if (Downloads.hex(Downloads.digest(apk, "SHA-256")) != expected) {
                apk.delete()
                throw IOException("Fichier corrompu : l'empreinte ne correspond pas")
            }
        }
        val signer = signerOf(apk)
        if (signer !in TRUSTED_SIGNERS) {
            apk.delete()
            throw IOException("Moteur refusé : signature inconnue ($signer)")
        }
    }

    @Suppress("DEPRECATION")
    private fun signerOf(apk: File): String? {
        val info = if (Build.VERSION.SDK_INT >= 28) {
            app.packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)?.signingInfo?.apkContentsSigners
        } else {
            app.packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)?.signatures
        }
        return info?.firstOrNull()?.let { Downloads.hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }
    }

    /** Revient au moteur du système au prochain démarrage. */
    fun useSystemEngine() {
        prefs.edit().remove(KEY_ACTIVE).putInt(KEY_ATTEMPTS, 0).remove(KEY_FAILURE).commit()
        _state.value = UpdateState.RestartNeeded("système")
    }

    private fun disable(reason: String) {
        Log.e(TAG, reason)
        prefs.edit().remove(KEY_ACTIVE).putInt(KEY_ATTEMPTS, 0).putString(KEY_FAILURE, reason).commit()
    }

    private companion object {
        const val TAG = "PocketWright"
        const val APK = "base.apk"
        const val LIBS = "libs"
        const val KEY_ACTIVE = "active"
        const val KEY_ATTEMPTS = "attempts"
        const val KEY_FAILURE = "failure"
        const val MAX_ATTEMPTS = 3
        const val MIN_FREE_BYTES = 500L * 1024 * 1024
        const val REPOSITORY = "android_external_chromium-webview_prebuilt_"
        const val APK_IN_REPOSITORY = "webview.apk"
        const val LFS_HOST = "https://review.lineageos.org"
        val ENTRY = Regex("<entry>([\\s\\S]*?)</entry>")
        val COMMIT = Regex("Commit/([0-9a-f]{40})")
        val TITLE = Regex("<title>([\\s\\S]*?)</title>")
        val VERSION = Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")
        val OID = Regex("oid sha256:([0-9a-f]{64})")
        val SIZE = Regex("size (\\d+)")

        /**
         * La clé des WebView publiées par LineageOS, qui est la clé de débogage publique de Chromium : elle écarte un
         * mauvais fichier, mais ne prouve pas l'origine. C'est l'empreinte inscrite dans leur dépôt Git qui le fait.
         */
        val TRUSTED_SIGNERS = setOf("32a2fc74d731105859e5a85df16d95f102d85b22099b8064c5d8915c61dad1e0")
    }
}
