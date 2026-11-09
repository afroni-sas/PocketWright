package com.afroni.pocketwright.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.afroni.pocketwright.PocketWrightApp
import com.afroni.pocketwright.service.PocketWrightService
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Un lien que Android nous demande d'ouvrir. */
    private val incomingUrl = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PocketWrightService.start(this)
        // La liste des onglets dès la première image (sans moteur : les pages viennent après).
        PocketWrightApp.instance.sessions.restore()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) incomingUrl.value = webUrl(intent)
        setContent { PocketWrightTheme { PocketWrightRoot(incomingUrl) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        webUrl(intent)?.let { incomingUrl.value = it }
    }

    override fun onStart() {
        super.onStart()
        PocketWrightApp.instance.sessions.setUiVisible(true)
    }

    override fun onStop() {
        PocketWrightApp.instance.sessions.setUiVisible(false)
        super.onStop()
    }

    /** Seuls les liens web : l'activité est exposée, et une autre app pourrait lui passer n'importe quoi. */
    private fun webUrl(intent: Intent?): String? =
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
}
