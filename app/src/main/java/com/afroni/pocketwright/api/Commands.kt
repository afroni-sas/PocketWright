package com.afroni.pocketwright.api

import android.util.Base64
import com.afroni.pocketwright.engine.PlaywrightEngine
import com.afroni.pocketwright.fingerprint.FingerprintRequest
import com.afroni.pocketwright.session.ApiException
import com.afroni.pocketwright.session.BrowserSession
import com.afroni.pocketwright.session.SessionManager
import com.afroni.pocketwright.session.bool
import com.afroni.pocketwright.session.int
import com.afroni.pocketwright.session.long
import com.afroni.pocketwright.session.require
import com.afroni.pocketwright.session.string
import com.afroni.pocketwright.session.timeout
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Exécute une commande sur une session.
 *
 * Par défaut, c'est le vrai Playwright (le moteur embarqué) qui l'exécute : toutes ses actions, ses
 * sélecteurs (`css=`, `text=`, `role=button[name="OK"]`, `xpath=`…) et son attente automatique. Le moteur
 * natif de l'app prend le relais pour les commandes de base si Playwright n'est pas prêt, ou si on le
 * demande avec `"engine": "native"`.
 */
class Commands(private val manager: SessionManager, private val engine: PlaywrightEngine) {

    suspend fun run(session: BrowserSession, action: String, params: JsonObject): JsonElement {
        if (action == "info" || action == "close") return dispatch(session, action, params)
        // Un onglet endormi ou en pause se réveille à la première commande.
        return session.whileActive {
            session.awaitFingerprint()
            dispatch(session, action, params)
        }
    }

    private suspend fun dispatch(session: BrowserSession, action: String, params: JsonObject): JsonElement {
        // Ce qui touche la session elle-même, et non la page.
        when (action) {
            "info" -> return session.info().toJson()
            "close" -> return buildJsonObject { put("closed", manager.close(session.id)) }
            "rename" -> {
                manager.rename(session, params.require("name"))
                return session.info().toJson()
            }
            "dialogs" -> {
                val mode = params.string("mode") ?: "accept"
                if (mode != "accept" && mode != "dismiss") throw ApiException(400, "mode : accept ou dismiss")
                session.dialogMode = mode
                session.promptText = params.string("promptText")
                return buildJsonObject { put("mode", mode) }
            }
            "fingerprint" -> return session.fingerprint?.toJson(manager.engineMajor) ?: JsonNull
            "setFingerprint" -> {
                val updated = manager.setFingerprint(session, FingerprintRequest.parse(params), params.bool("reload", true))
                return updated.toJson(manager.engineMajor)
            }
        }
        val choice = params.string("engine") ?: "auto"
        val rest = JsonObject(params - "engine")
        val playwright = when (choice) {
            "playwright" -> true
            "native" -> false
            "auto" -> engine.ready || action !in NATIVE
            else -> throw ApiException(400, "engine : auto, playwright ou native")
        }
        if (!playwright) return session.exclusive(action) { native(session, action, rest) }
        session.note(action)
        return try {
            engine.command(session.id, action, rest, commandTimeout(action, params))
        } catch (e: ApiException) {
            // Filet de sécurité : la capture native ne dépend ni du compositeur ni de Playwright.
            if (action == "screenshot" && choice == "auto" && (e.status >= 500 || e.status == 408)) native(session, action, rest) else throw e
        }
    }

    /** Le temps qu'une commande peut prendre : `run` a son propre délai, une attente dure ce qu'on demande. */
    private fun commandTimeout(action: String, params: JsonObject): Long = when (action) {
        "run" -> params.long("timeout", 120_000)
        "waitForTimeout", "wait" -> maxOf(params.timeout(), params.long("ms", 0))
        else -> params.timeout()
    }

    private suspend fun native(session: BrowserSession, action: String, p: JsonObject): JsonElement {
        val timeout = p.timeout()
        val wait = p.string("waitUntil") != "commit" && p.bool("wait", true)
        return when (action) {
            "goto" -> session.goto(p.require("url"), timeout, wait)
            "reload" -> session.reload(timeout, wait)
            "back" -> session.back(timeout, wait)
            "forward" -> session.forward(timeout, wait)
            "setContent" -> session.setContent(p.require("html"), p.string("baseUrl"), timeout)
            "evaluate" -> session.evaluate(p.require("expression"), p["arg"], timeout)
            "click" -> session.click(p.require("selector"), timeout, p.bool("native", true), p.bool("waitForNavigation", false))
            "fill", "select", "selectOption" -> session.fill(p.require("selector"), p.string("value") ?: "", timeout)
            "type" -> session.type(p.string("selector"), p.require("text"), timeout)
            "press" -> session.press(p.require("key"), p.string("selector"), timeout)
            "waitForSelector" -> session.waitFor(p.require("selector"), p.string("state") ?: "visible", timeout)
            "wait", "waitForTimeout" -> p.string("selector")?.let { session.waitFor(it, p.string("state") ?: "visible", timeout) }
                ?: JsonPrimitive(p.long("ms", 1_000).coerceIn(0, 600_000).also { delay(it) })
            "waitForLoad" -> session.waitForLoad(timeout)
            "content" -> session.content(timeout)
            "text" -> session.text(p.string("selector"), timeout)
            "html" -> p.string("selector")?.let { session.html(it, timeout) } ?: session.content(timeout)
            "attribute" -> session.attribute(p.require("selector"), p.require("name"), timeout)
            "queryAll" -> session.queryAll(p.require("selector"), p.int("limit", 100).coerceIn(1, 5_000), timeout)
            "title" -> JsonPrimitive(session.pageInfo()["title"]?.jsonPrimitive?.contentOrNull)
            "url" -> JsonPrimitive(session.url)
            "scroll" -> session.scroll(p.string("selector"), p.int("x", 0), p.int("y", 0), p.string("to"), timeout)
            "screenshot" -> {
                val type = p.string("type") ?: "png"
                val bytes = session.screenshot(type, p.int("quality", 90).coerceIn(1, 100), p.bool("fullPage", false), p.string("selector"), timeout)
                buildJsonObject { put("type", type); put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP)) }
            }
            "viewport" -> session.setViewport(p.int("width", session.viewportWidth), p.int("height", session.viewportHeight))
            "cookies" -> session.cookies(p.string("url"))
            "setCookie" -> session.setCookie(p.require("url"), p.require("cookie"))
            "clearCookies" -> session.clearCookies()
            else -> throw ApiException(
                if (engine.ready) 404 else 503,
                "L'action « $action » demande le moteur Playwright, qui n'est pas prêt. Actions natives : ${NATIVE.joinToString()}",
            )
        }
    }

    /** Une capture d'écran en octets, pour la route GET qui rend directement l'image. */
    suspend fun screenshotBytes(session: BrowserSession, params: JsonObject): Pair<ByteArray, String> {
        val shot = run(session, "screenshot", params).jsonObject
        val type = shot["type"]?.jsonPrimitive?.contentOrNull ?: "png"
        val data = shot["base64"]?.jsonPrimitive?.contentOrNull ?: throw ApiException(500, "Capture vide")
        return Base64.decode(data, Base64.DEFAULT) to type
    }

    companion object {
        /**
         * Les commandes sans effet sur la page : les seules permises en GET, qu'une simple balise `<img>`
         * d'un site web pourrait déclencher.
         */
        val READ_ONLY = setOf(
            "info", "url", "title", "content", "text", "textContent", "html", "innerHTML", "attribute", "inputValue",
            "count", "isVisible", "isEnabled", "isChecked", "boundingBox", "ariaSnapshot", "queryAll", "cookies", "screenshot", "fingerprint",
        )

        /** Les commandes que le moteur natif sait faire sans Playwright. */
        val NATIVE = listOf(
            "goto", "reload", "back", "forward", "setContent", "evaluate", "click", "fill", "select", "selectOption",
            "type", "press", "waitForSelector", "wait", "waitForTimeout", "waitForLoad", "content", "text", "html",
            "attribute", "queryAll", "title", "url", "scroll", "screenshot", "viewport", "cookies", "setCookie", "clearCookies",
        )
    }
}
