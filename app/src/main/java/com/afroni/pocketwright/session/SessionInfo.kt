package com.afroni.pocketwright.session

import com.afroni.pocketwright.fingerprint.Fingerprint
import com.afroni.pocketwright.fingerprint.FingerprintRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** D'où vient un onglet : ouvert à la main, par l'API, ou par une page (`window.open`, lien `_blank`). */
object TabOrigin {
    const val USER = "user"
    const val API = "api"
    const val POPUP = "popup"
}

/** Ce qu'on montre d'une session, à l'écran comme dans l'API. */
data class SessionInfo(
    val id: String,
    val name: String,
    val url: String,
    val title: String,
    val loading: Boolean,
    val progress: Int,
    val lastError: String?,
    val createdAt: Long,
    val lastActivityAt: Long,
    val lastAction: String?,
    val clients: Int,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val isolated: Boolean,
    val shown: Boolean,
    val generation: Int,
    val group: String,
    val origin: String,
    val live: Boolean,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val desktop: Boolean,
    val thumbnailVersion: Int,
    val faviconVersion: Int,
    val logVersion: Int,
    val incognito: Boolean,
    /** La couleur de la page (`theme-color`, sinon son fond), pour teinter le haut de l'écran. */
    val tint: Int?,
    val fingerprint: JsonObject? = null,
) {
    /** Un programme pilote cet onglet, ou vient de le faire. */
    val automated: Boolean get() = clients > 0 || origin == TabOrigin.API

    val secure: Boolean get() = url.startsWith("https://")

    /** Le nom affiché dans l'interface : le titre de la page, sinon le domaine, sinon le nom de la session. */
    val label: String
        get() = title.takeIf { it.isNotBlank() && it != url && it != "about:blank" } ?: host.ifBlank { name }

    val host: String
        get() = url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringAfter('@')
            .removePrefix("www.")

    val blank: Boolean get() = url.isBlank() || url == "about:blank"

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("url", url)
        put("title", title)
        put("loading", loading)
        put("progress", progress)
        lastError?.let { put("error", it) }
        put("createdAt", createdAt)
        put("lastActivityAt", lastActivityAt)
        lastAction?.let { put("lastAction", it) }
        put("clients", clients)
        put("viewport", buildJsonObject { put("width", viewportWidth); put("height", viewportHeight) })
        put("isolated", isolated)
        put("shown", shown)
        put("group", group)
        put("origin", origin)
        put("live", live)
        put("incognito", incognito)
        fingerprint?.let { put("fingerprint", it) }
    }
}

/** Une session telle qu'elle est gardée sur le disque, pour revenir après un redémarrage. */
data class SessionRecord(
    val id: String,
    val name: String,
    val profile: String?,
    val url: String,
    val userAgent: String?,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val createdAt: Long,
    val group: String = id,
    val origin: String = TabOrigin.API,
    val desktop: Boolean = false,
    val title: String = "",
    val lastActivityAt: Long = createdAt,
    /** Navigation privée : jamais écrite sur le disque, son profil disparaît avec le dernier onglet privé. */
    val incognito: Boolean = false,
    val fingerprint: Fingerprint? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        profile?.let { put("profile", it) }
        put("url", url)
        userAgent?.let { put("userAgent", it) }
        put("viewportWidth", viewportWidth)
        put("viewportHeight", viewportHeight)
        put("createdAt", createdAt)
        put("group", group)
        put("origin", origin)
        put("desktop", desktop)
        put("title", title)
        put("lastActivityAt", lastActivityAt)
        fingerprint?.let { put("fingerprint", it.toRecord()) }
    }

    companion object {
        fun fromJson(o: JsonObject): SessionRecord? {
            val id = o.string("id") ?: return null
            val created = o.string("createdAt")?.toLongOrNull() ?: System.currentTimeMillis()
            return SessionRecord(
                id = id,
                name = o.string("name") ?: id,
                profile = o.string("profile"),
                url = o.string("url") ?: "about:blank",
                userAgent = o.string("userAgent"),
                viewportWidth = o.string("viewportWidth")?.toIntOrNull() ?: 412,
                viewportHeight = o.string("viewportHeight")?.toIntOrNull() ?: 915,
                createdAt = created,
                group = o.string("group") ?: id,
                origin = o.string("origin") ?: TabOrigin.API,
                desktop = o.string("desktop") == "true",
                title = o.string("title").orEmpty(),
                lastActivityAt = o.string("lastActivityAt")?.toLongOrNull() ?: created,
                fingerprint = Fingerprint.fromRecord(o["fingerprint"] as? JsonObject),
            )
        }
    }
}

/** Ce qu'un client demande en créant une session. */
data class SessionOptions(
    val name: String? = null,
    val url: String? = null,
    val userAgent: String? = null,
    val viewportWidth: Int? = null,
    val viewportHeight: Int? = null,
    /** Rejoint un groupe existant (même profil, donc mêmes cookies), au lieu d'en ouvrir un. */
    val group: String? = null,
    val origin: String = TabOrigin.API,
    val incognito: Boolean = false,
    val fingerprint: FingerprintRequest = FingerprintRequest.AUTO,
) {
    companion object {
        /** Lit `{name, url, userAgent, viewport: {width, height}}`, ou `width`/`height` à plat (paramètres d'URL). */
        fun from(o: JsonObject): SessionOptions {
            val viewport = o["viewport"] as? JsonObject
            return SessionOptions(
                name = o.string("name")?.takeIf { it.isNotBlank() },
                url = o.string("url")?.takeIf { it.isNotBlank() },
                userAgent = o.string("userAgent")?.takeIf { it.isNotBlank() },
                viewportWidth = (viewport?.string("width") ?: o.string("width"))?.toDoubleOrNull()?.toInt(),
                viewportHeight = (viewport?.string("height") ?: o.string("height"))?.toDoubleOrNull()?.toInt(),
                fingerprint = FingerprintRequest.from(o["fingerprint"]),
            )
        }
    }
}

/** Ce qu'il y a sous le doigt lors d'un appui long dans la page. */
data class PressTarget(val link: String?, val image: String?)

/** Une ligne du journal d'un onglet : commande, chargement, console, dialogue. */
data class LogEntry(val time: Long, val kind: String, val text: String)
