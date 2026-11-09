package com.afroni.pocketwright.session

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Les paramètres des commandes, lus avec indulgence : un nombre peut arriver en texte (paramètres d'URL) et
 * inversement.
 */

fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

fun JsonObject.require(key: String): String = string(key) ?: throw ApiException(400, "Paramètre « $key » manquant")

fun JsonObject.long(key: String, default: Long): Long =
    string(key)?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() } ?: default

fun JsonObject.int(key: String, default: Int): Int = long(key, default.toLong()).toInt()

fun JsonObject.bool(key: String, default: Boolean): Boolean = when (string(key)?.lowercase()) {
    "true", "1", "yes", "oui" -> true
    "false", "0", "no", "non" -> false
    else -> default
}

/** Le délai d'une commande, 30 s par défaut comme dans Playwright. */
fun JsonObject.timeout(): Long = long("timeout", 30_000).coerceIn(1, 600_000)
