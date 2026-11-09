package com.afroni.pocketwright.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.afroni.pocketwright.PocketWrightApp
import com.afroni.pocketwright.R
import com.afroni.pocketwright.ServerStatus
import com.afroni.pocketwright.api.ApiServer
import com.afroni.pocketwright.engine.EngineStatus
import com.afroni.pocketwright.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Le service de premier plan qui garde l'app en marche : il porte le serveur API, le moteur Playwright et
 * les onglets, écran éteint compris. Android le relance s'il est tué (START_STICKY), [BootReceiver] au
 * démarrage du téléphone, et il se surveille lui-même : un moteur figé relance le processus.
 */
class PocketWrightService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var server: ApiServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val app get() = application as PocketWrightApp

    override fun onCreate() {
        super.onCreate()
        createChannel()
        goForeground("Démarrage…")
        scheduleWatchdog(this)
        scheduleWatchdogBackup(this)
        runCatching { app.sessions.restore() }.onFailure { Log.e(TAG, "Onglets non restaurés", it) }
        // Sans écran (démarrage du téléphone), personne d'autre ne lancera les pages.
        scope.launch {
            delay(ENGINE_DELAY_MS)
            runCatching { app.sessions.startPages() }.onFailure { Log.e(TAG, "Pages non lancées", it) }
        }
        val api = ApiServer(PocketWrightApp.API_PORT, app.sessions, app.settings, app.engine, app.browser, app.commands, app.locator, app)
        server = api
        scope.launch(Dispatchers.IO) { startServer(api) }
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketWright:server")
            .apply { setReferenceCounted(false) }
        scope.launch {
            combine(app.sessions.infos, app.serverStatus) { sessions, status ->
                val state = when (status) {
                    is ServerStatus.Running -> "API sur 127.0.0.1:${status.port}"
                    is ServerStatus.Failed -> "Erreur : ${status.message}"
                    ServerStatus.Starting -> "Démarrage…"
                }
                "$state · ${sessions.size} onglet(s)"
            }.distinctUntilChanged().collect { goForeground(it) }
        }
        scope.launch { keepCpuWhileBusy() }
        scope.launch {
            // Vingt secondes sans plantage : le moteur Chromium chargé est bon.
            delay(20_000)
            app.chromium.confirmHealthy()
        }
        scope.launch { watchEngine() }
    }

    /** Le port peut rester pris quelques secondes après un redémarrage : on insiste. */
    private suspend fun startServer(api: ApiServer) {
        while (true) {
            try {
                api.start()
                app.serverStatus.value = ServerStatus.Running(PocketWrightApp.API_PORT)
                // Node charge Playwright pendant plusieurs secondes : l'interface passe d'abord.
                delay(ENGINE_DELAY_MS)
                app.engine.start()
                return
            } catch (e: Exception) {
                Log.e(TAG, "Serveur API non démarré", e)
                app.serverStatus.value = ServerStatus.Failed(e.message ?: e.toString())
                delay(3_000)
            }
        }
    }

    /**
     * Le processeur reste éveillé tant qu'un client est branché ou qu'une commande vient de passer, pas
     * au-delà : un verrou permanent viderait la batterie, et certains constructeurs tuent l'app pour ça.
     */
    private suspend fun keepCpuWhileBusy() {
        val lock = wakeLock ?: return
        while (true) {
            val now = System.currentTimeMillis()
            val busy = app.sessions.infos.value.any { it.clients > 0 || now - it.lastActivityAt < BUSY_WINDOW_MS }
            when {
                busy && !lock.isHeld -> lock.acquire()
                !busy && lock.isHeld -> lock.release()
            }
            delay(30_000)
        }
    }

    /**
     * Node ne peut tourner qu'une fois par processus : s'il s'arrête, ou s'il est figé (un script en boucle
     * infinie), seule une relance du processus le rétablit. Les onglets reviennent tout seuls.
     */
    private suspend fun watchEngine() {
        var failures = 0
        while (true) {
            delay(WATCH_PERIOD_MS)
            val status = app.engine.status.value
            failures = when {
                status is EngineStatus.Failed -> failures + 1
                status is EngineStatus.Ready && !app.engine.healthy() -> failures + 1
                else -> 0
            }
            if (failures >= MAX_ENGINE_FAILURES && restartsAllowed()) {
                Log.e(TAG, "Moteur Playwright hors service : relance du processus")
                restartProcess()
            }
        }
    }

    /** Pas plus de trois relances en dix minutes : au-delà, relancer ne ferait que tourner en boucle. */
    private fun restartsAllowed(): Boolean {
        val prefs = getSharedPreferences("pocketwright-watchdog", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val recent = prefs.getString("restarts", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }.filter { now - it < RESTART_WINDOW_MS }
        if (recent.size >= MAX_RESTARTS) return false
        prefs.edit().putString("restarts", (recent + now).joinToString(",")).commit()
        return true
    }

    private fun restartProcess() {
        scheduleRestart(this)
        Process.killProcess(Process.myPid())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        server?.stop()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    private fun goForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(text), type)
        } catch (e: Exception) {
            Log.e(TAG, "Premier plan refusé", e)
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("PocketWright")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "Serveur PocketWright", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Garde le serveur API et les onglets en marche"
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "PocketWright"
        private const val CHANNEL = "server"
        private const val NOTIFICATION_ID = 1
        private const val ENGINE_DELAY_MS = 2_500L
        private const val BUSY_WINDOW_MS = 5L * 60_000
        private const val WATCH_PERIOD_MS = 30_000L
        private const val MAX_ENGINE_FAILURES = 3
        private const val MAX_RESTARTS = 3
        private const val RESTART_WINDOW_MS = 10L * 60_000
        const val ACTION_RESTART = "com.afroni.pocketwright.RESTART"
        const val ACTION_WATCHDOG = "com.afroni.pocketwright.WATCHDOG"
        private const val WATCHDOG_PERIOD_MS = 5L * 60_000
        private const val WATCHDOG_BACKUP_PERIOD_MS = 15L * 60_000

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PocketWrightService::class.java))
        }

        /**
         * Après un plantage du moteur, ce téléphone ne relance pas le service de lui-même. Cette alarme, qui survit
         * au processus, le fait : au pire, l'API revient en cinq minutes.
         */
        fun scheduleWatchdog(context: Context) {
            val intent = Intent(context, BootReceiver::class.java).setAction(ACTION_WATCHDOG)
            val alarm = PendingIntent.getBroadcast(context, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val alarms = context.getSystemService(AlarmManager::class.java)
            val at = SystemClock.elapsedRealtime() + WATCHDOG_PERIOD_MS
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarm)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarm)
            }
        }

        /**
         * L'alarme exacte se reprogramme à chaque passage : si elle tombe sur un processus figé, la chaîne s'arrête.
         * Celle-ci se répète toute seule et la relance. Moins ponctuelle, elle ne peut pas démarrer le service.
         */
        fun scheduleWatchdogBackup(context: Context) {
            val intent = Intent(context, BootReceiver::class.java).setAction(ACTION_WATCHDOG)
            val alarm = PendingIntent.getBroadcast(context, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            context.getSystemService(AlarmManager::class.java).setRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + WATCHDOG_BACKUP_PERIOD_MS,
                WATCHDOG_BACKUP_PERIOD_MS,
                alarm,
            )
        }

        /**
         * Une alarme survit à la fin du processus : c'est elle qui relance le service juste après. Elle doit être
         * exacte : depuis Android 12, seules celles-là ont le droit de démarrer un service de premier plan.
         */
        fun scheduleRestart(context: Context) {
            val intent = Intent(context, BootReceiver::class.java).setAction(ACTION_RESTART)
            val restart = PendingIntent.getBroadcast(context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val alarms = context.getSystemService(AlarmManager::class.java)
            val at = SystemClock.elapsedRealtime() + 1_500
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, restart)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, restart)
            }
        }
    }
}
