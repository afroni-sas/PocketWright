package com.afroni.pocketwright.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.afroni.pocketwright.PocketWrightApp
import com.afroni.pocketwright.session.SessionManager
import com.afroni.pocketwright.session.SessionOptions
import com.afroni.pocketwright.session.TabOrigin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private enum class Screen { Browser, Tabs, Settings, Library }

/** Les trois écrans : la page (le navigateur), la grille des onglets, les réglages. */
@Composable
fun PocketWrightRoot(incomingUrl: MutableStateFlow<String?>) {
    val app = PocketWrightApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tabs by app.sessions.infos.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.Browser) }
    var currentId by rememberSaveable { mutableStateOf(app.settings.lastTab) }
    val current = tabs.firstOrNull { it.id == currentId }

    fun select(id: String?) {
        currentId = id
        app.settings.lastTab = id
        screen = Screen.Browser
    }

    fun newTab(url: String?, incognito: Boolean = false, background: Boolean = false) {
        scope.launch {
            // Comme dans un navigateur : les onglets normaux partagent cookies et connexions.
            val group = if (incognito) SessionManager.INCOGNITO_GROUP else SessionManager.BROWSER_GROUP
            runCatching { app.sessions.create(SessionOptions(url = url, origin = TabOrigin.USER, group = group, incognito = incognito)) }
                .onSuccess { if (!background) select(it.id) }
                .onFailure { Toast.makeText(context, "Onglet impossible : ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    fun closeTab(id: String) {
        if (id == currentId) {
            // Comme un navigateur : l'onglet voisin prend la place.
            val index = tabs.indexOfFirst { it.id == id }
            val neighbour = tabs.getOrNull(index + 1) ?: tabs.getOrNull(index - 1)
            currentId = neighbour?.id
            app.settings.lastTab = currentId
        }
        scope.launch { runCatching { app.sessions.close(id) } }
    }

    // L'onglet regardé a été fermé par l'API : pas d'écran vide, retour à la page d'accueil.
    LaunchedEffect(currentId, current == null) {
        if (currentId != null && current == null) {
            currentId = null
            app.settings.lastTab = null
        }
    }
    LaunchedEffect(Unit) { app.sessions.focusRequests.collect { select(it) } }
    // Charger Chromium bloque le fil principal : d'abord montrer l'interface, ensuite seulement le moteur.
    val engineReady by app.sessions.engineReady.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        delay(30)
        runCatching { app.sessions.startPages() }
    }
    val incoming by incomingUrl.collectAsStateWithLifecycle()
    LaunchedEffect(incoming) {
        incoming?.let {
            incomingUrl.value = null
            newTab(it)
        }
    }

    when (screen) {
        Screen.Browser -> BrowserScreen(
            tab = current,
            tabs = tabs,
            engineReady = engineReady,
            onShowTabs = { screen = Screen.Tabs },
            onNewTab = { url, incognito, background -> newTab(url, incognito, background) },
            onSelectTab = ::select,
            onShowSettings = { screen = Screen.Settings },
            onShowLibrary = { screen = Screen.Library },
        )
        Screen.Tabs -> TabsScreen(
            tabs = tabs,
            currentId = currentId,
            onSelect = ::select,
            onClose = ::closeTab,
            onNewTab = { newTab(null) },
            onNewIncognitoTab = { newTab(null, incognito = true) },
            onShowSettings = { screen = Screen.Settings },
            onBack = { screen = Screen.Browser },
        )
        Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Browser })
        Screen.Library -> LibraryScreen(
            onOpen = { url ->
                screen = Screen.Browser
                app.sessions.get(currentId.orEmpty())?.loadFromUser(url) ?: newTab(url)
            },
            onBack = { screen = Screen.Browser },
        )
    }
}
