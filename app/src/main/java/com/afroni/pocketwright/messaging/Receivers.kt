package com.afroni.pocketwright.messaging

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.Telephony
import android.util.Log
import com.afroni.pocketwright.PocketWrightApp
import com.afroni.pocketwright.service.PocketWrightService
import kotlinx.coroutines.launch

/** Hors du fil principal : l'écriture dans le stockage SMS peut prendre du temps. */
private fun BroadcastReceiver.inBackground(work: (Messaging) -> Unit) {
    val result = goAsync()
    val app = PocketWrightApp.instance
    app.background.launch {
        try {
            work(app.messaging)
        } catch (e: Exception) {
            Log.e("PocketWright", "Message non traité", e)
        } finally {
            result.finish()
        }
    }
}

/** Android a pu relancer le processus juste pour ce message : l'API doit repartir pour le diffuser. */
private fun startApi(context: Context) {
    runCatching { PocketWrightService.start(context) }
}

/** Android ne livre `SMS_DELIVER` qu'à la messagerie par défaut. */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        startApi(context)
        inBackground { it.onSms(intent) }
    }
}

class WapPushDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        startApi(context)
        inBackground { it.onWapPush(intent) }
    }
}

class MmsDownloadedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(Messaging.EXTRA_MMS_ID, -1)
        if (id < 0) return
        val ok = resultCode == Activity.RESULT_OK
        inBackground { it.onMmsDownloaded(id, ok) }
    }
}

class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val ok = resultCode == Activity.RESULT_OK
        val last = intent.getBooleanExtra(Messaging.EXTRA_LAST_PART, true)
        inBackground { it.onSmsResult(uri, ok, last) }
    }
}

/** La réponse rapide par SMS proposée quand on refuse un appel. */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val recipients = intent?.data?.let(::recipientsOf).orEmpty()
        if (text.isNullOrBlank() || recipients.isEmpty()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val app = PocketWrightApp.instance
        app.background.launch {
            try {
                recipients.forEach { app.messaging.send(it, text) }
            } catch (e: Exception) {
                Log.e("PocketWright", "Réponse rapide non envoyée", e)
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }
}

/** Les numéros d'une adresse `sms:` ou `smsto:`, qui peut en contenir plusieurs. */
fun recipientsOf(uri: Uri): List<String> =
    uri.schemeSpecificPart.orEmpty().substringBefore('?').split(',', ';').map(String::trim).filter(String::isNotEmpty)
