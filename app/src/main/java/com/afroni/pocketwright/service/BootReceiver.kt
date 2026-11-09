package com.afroni.pocketwright.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Relance le service au démarrage du téléphone et après une mise à jour de l'app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, PocketWrightService.ACTION_RESTART ->
                // Android peut refuser un service de premier plan depuis l'arrière-plan : ce n'est pas une raison de planter.
                runCatching { PocketWrightService.start(context) }
            PocketWrightService.ACTION_WATCHDOG -> {
                runCatching { PocketWrightService.scheduleWatchdog(context) }
                runCatching { PocketWrightService.start(context) }
            }
        }
    }
}
