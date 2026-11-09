package com.afroni.pocketwright

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebSettings
import androidx.webkit.WebViewCompat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Le moteur des sessions : Android System WebView, c'est-à-dire Chromium, à la même version que Chrome stable
 * (les deux sortent ensemble, mis à jour par le Play Store). On peut prendre plus récent encore en choisissant
 * WebView Beta, Dev ou Canary dans les options pour les développeurs (« Implémentation WebView »).
 */
class BrowserInfo(private val context: Context, private val engineReady: () -> Boolean) {
    // Relu à chaque fois et jamais avant le chargement du moteur : le lire trop tôt figerait celui du système.
    private val pkg get() = if (engineReady()) WebViewCompat.getCurrentWebViewPackage(context) else null

    val packageName: String get() = pkg?.packageName ?: "?"
    val version: String get() = pkg?.versionName ?: "?"
    val major: Int get() = version.substringBefore('.').toIntOrNull() ?: 0
    val userAgent: String get() = if (engineReady()) runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault("") else ""

    val channel: String
        get() = when {
            packageName.endsWith(".canary") -> "Canary"
            packageName.endsWith(".dev") -> "Dev"
            packageName.endsWith(".beta") -> "Beta"
            else -> "Stable"
        }

    /**
     * Chromium sort une version majeure toutes les quatre semaines : on en déduit celle qu'on devrait avoir
     * à cette date, et on signale un moteur en retard de plus d'un an.
     */
    val outdated: Boolean
        get() {
            val weeks = (System.currentTimeMillis() - REFERENCE_DATE_MS) / WEEK_MS
            return major in 1 until REFERENCE_VERSION + (weeks / 4).toInt() - MAX_MILESTONES_BEHIND
        }

    fun toJson(): JsonObject = buildJsonObject {
        put("engine", "Android System WebView (Chromium)")
        put("package", packageName)
        put("channel", channel)
        put("version", version)
        put("userAgent", userAgent)
        put("outdated", outdated)
    }

    private companion object {
        // Chromium 131 : novembre 2024.
        const val REFERENCE_VERSION = 131
        const val REFERENCE_DATE_MS = 1_731_369_600_000L
        const val WEEK_MS = 7L * 24 * 3_600_000
        const val MAX_MILESTONES_BEHIND = 13
    }

    /** La page Play Store du moteur, pour le mettre à jour. */
    fun updateIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
