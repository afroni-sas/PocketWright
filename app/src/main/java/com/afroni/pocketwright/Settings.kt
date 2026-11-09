package com.afroni.pocketwright

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

/** Le moteur de recherche de la barre d'adresse. */
enum class SearchEngine(val label: String, private val template: String) {
    GOOGLE("Google", "https://www.google.com/search?q="),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q="),
    BING("Bing", "https://www.bing.com/search?q=");

    fun urlFor(query: String): String = template + java.net.URLEncoder.encode(query, "UTF-8")

    companion object {
        fun from(name: String?): SearchEngine = entries.firstOrNull { it.name == name } ?: GOOGLE
    }
}

/** Les réglages de l'app : la clé de l'API, le moteur de recherche. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("pocketwright", Context.MODE_PRIVATE)

    private val _apiKey = MutableStateFlow(prefs.getString(KEY_API, null) ?: newKey().also { prefs.edit().putString(KEY_API, it).apply() })
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _requireKey = MutableStateFlow(prefs.getBoolean(KEY_REQUIRE, true))
    /**
     * Toute app du téléphone peut joindre 127.0.0.1, et une page web aussi : la clé empêche qu'elles pilotent
     * les sessions (et leurs comptes connectés) à votre place.
     */
    val requireKey: StateFlow<Boolean> = _requireKey.asStateFlow()

    private val _searchEngine = MutableStateFlow(SearchEngine.from(prefs.getString(KEY_SEARCH, null)))
    val searchEngine: StateFlow<SearchEngine> = _searchEngine.asStateFlow()

    fun setSearchEngine(engine: SearchEngine) {
        prefs.edit().putString(KEY_SEARCH, engine.name).apply()
        _searchEngine.value = engine
    }

    private val _messagesApi = MutableStateFlow(prefs.getBoolean(KEY_MESSAGES_API, false))
    /** Se règle dans l'app seulement : l'API ne peut pas s'ouvrir elle-même l'accès aux SMS. */
    val messagesApi: StateFlow<Boolean> = _messagesApi.asStateFlow()

    fun setMessagesApi(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MESSAGES_API, enabled).apply()
        _messagesApi.value = enabled
    }

    /** Le bruit canvas et audio des onglets ouverts à la main : stable, comme l'est un vrai téléphone. */
    val browsingNoiseSeed: Long by lazy {
        if (prefs.contains(KEY_NOISE)) prefs.getLong(KEY_NOISE, 0) else SecureRandom().nextLong().also { prefs.edit().putLong(KEY_NOISE, it).apply() }
    }

    /** L'onglet regardé en dernier, à rouvrir au lancement. */
    var lastTab: String?
        get() = prefs.getString(KEY_LAST_TAB, null)
        set(value) = prefs.edit().putString(KEY_LAST_TAB, value).apply()

    /** Une clé interne, jamais montrée, par laquelle le moteur Playwright de l'app joint la passerelle CDP. */
    val internalKey: String = newKey()

    fun setRequireKey(required: Boolean) {
        prefs.edit().putBoolean(KEY_REQUIRE, required).apply()
        _requireKey.value = required
    }

    fun regenerateKey() {
        val key = newKey()
        prefs.edit().putString(KEY_API, key).apply()
        _apiKey.value = key
    }

    private fun newKey(): String {
        val bytes = ByteArray(18).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private companion object {
        const val KEY_API = "api_key"
        const val KEY_REQUIRE = "require_key"
        const val KEY_SEARCH = "search_engine"
        const val KEY_LAST_TAB = "last_tab"
        const val KEY_MESSAGES_API = "messages_api"
        const val KEY_NOISE = "browsing_noise_seed"
    }
}
