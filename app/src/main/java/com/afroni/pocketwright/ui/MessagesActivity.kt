package com.afroni.pocketwright.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.afroni.pocketwright.PocketWrightApp
import com.afroni.pocketwright.messaging.recipientsOf
import com.afroni.pocketwright.service.PocketWrightService
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * La messagerie, à part du navigateur : elle s'ouvre sans charger Chromium. Android l'appelle aussi pour un lien
 * `sms:` ou « Envoyer un SMS » depuis une autre app ; le texte est prérempli, jamais envoyé sans l'utilisateur.
 */
class MessagesActivity : ComponentActivity() {

    private val route = MutableStateFlow<MessagesRoute>(MessagesRoute.Conversations)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        runCatching { PocketWrightService.start(this) }
        if (savedInstanceState == null) route.value = routeFor(intent)
        setContent { PocketWrightTheme { MessagesRoot(route, onClose = ::finish) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route.value = routeFor(intent)
    }

    override fun onStart() {
        super.onStart()
        // Un contact a pu être ajouté ou renommé pendant qu'on était ailleurs.
        PocketWrightApp.instance.messaging.store.clearNames()
    }

    private fun routeFor(intent: Intent?): MessagesRoute {
        val thread = intent?.getLongExtra(EXTRA_THREAD, -1) ?: -1
        if (thread >= 0) return MessagesRoute.Thread(thread)
        val data = intent?.data
        if (data == null || data.scheme !in SCHEMES) return MessagesRoute.Conversations
        val body = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getStringExtra("sms_body")
            ?: data.schemeSpecificPart.orEmpty().substringAfter('?', "").takeIf { it.startsWith("body=") }?.removePrefix("body=")
        return MessagesRoute.Compose(recipientsOf(data).joinToString(", "), body.orEmpty())
    }

    companion object {
        private const val EXTRA_THREAD = "thread"
        private val SCHEMES = setOf("sms", "smsto", "mms", "mmsto")

        fun threadIntent(context: Context, threadId: Long): Intent =
            Intent(context, MessagesActivity::class.java)
                .putExtra(EXTRA_THREAD, threadId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
