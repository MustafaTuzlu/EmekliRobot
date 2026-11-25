package com.emekli.robot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.emekli.robot.MainActivity
import com.emekli.robot.core.RuntimeState
import com.emekli.robot.core.ThermalState
import com.emekli.robot.sync.SyncEngine
import com.emekli.robot.sync.SyncPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 7/24 çalışan ön plan servisi (foregroundServiceType=location). Motoru ve ağ dinleyicisini barındırır.
 * Süreç öldürülürse START_STICKY, alarm ve WatchdogWorker servisi yeniden ayağa kaldırır.
 */
class TrackerService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var engine: TrackerEngine? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var lastSyncAt = 0L
    private var syncScheduled = false
    private var freshStart = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Başlatılıyor..."), foregroundType())

        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        engine = TrackerEngine(
            ctx = this,
            scope = s,
            requestSync = { urgent -> requestSync(urgent = urgent) },
            onModeChanged = { updateNotification() },
        )
        freshStart = true
        engine?.start()
        registerNetworkCallback()
        updateNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground servis her başlatılışta kısa sürede startForeground çağırmalı.
        startForeground(NOTIF_ID, buildNotification(statusText()), foregroundType())
        val action = intent?.action
        // Servis alarmla yeni doğduysa motor start() zaten kalp atışını yaptı: çift kayıt olmasın.
        if (action != null && !freshStart) engine?.onAlarm(action)
        freshStart = false
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        networkCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        main.removeCallbacksAndMessages(null)
        engine?.stop()
        engine = null
        scope?.cancel()
        scope = null
        RuntimeState.update { it.copy(running = false) }
        super.onDestroy()
    }

    // ---- gönderim tetikleme -------------------------------------------------------------------

    /**
     * Kayıt eklenince / ağ gelince çağrılır. Isınmışken gönderim durur; en az 60 sn arayla denenir.
     * `urgent` (sıcaklık uyarısı) bu iki kuralı da aşar ve bekleme süresini beklemeden bir kez dener.
     */
    private fun requestSync(force: Boolean = false, urgent: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val gap = now - lastSyncAt
        // force: 60 sn bekleme süresini atlar ama ısınma kuralını atlamaz (yalnızca urgent atlar)
        val decision = SyncPolicy.decide(
            RuntimeState.flow.value.thermal, isOnline(), urgent, if (force) Long.MAX_VALUE else gap,
        )
        when (decision) {
            SyncPolicy.Decision.SKIP -> return
            SyncPolicy.Decision.LATER -> {
                if (!syncScheduled) {
                    syncScheduled = true
                    main.postDelayed({ syncScheduled = false; requestSync() }, SyncPolicy.delayMs(gap))
                }
            }
            SyncPolicy.Decision.NOW -> {
                lastSyncAt = now
                // Acil gönderim önceki hata bekleme süresini de atlar.
                SyncEngine.launch(this, force = urgent)
            }
        }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // İnternet geri geldi: bekleme süresini sıfırla ve hemen dene.
                SyncEngine.resetBackoff()
                main.post { requestSync(force = true) }
            }
        }
        networkCallback = cb
        cm.registerDefaultNetworkCallback(cb)
    }

    // ---- bildirim -----------------------------------------------------------------------------

    private fun foregroundType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Takip", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "EmekliRobot arka plan takip servisi"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun statusText(): String {
        val s = RuntimeState.flow.value
        val hareket = if (s.moving) "Hareket" else "Durağan"
        val termal = when (s.thermal) {
            ThermalState.NORMAL -> ""
            ThermalState.ISINMIS -> " · ISINDI"
            ThermalState.KRITIK -> " · KRİTİK SICAKLIK"
        }
        return "$hareket · GPS ${s.gpsMode}$termal"
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("EmekliRobot takip çalışıyor")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(statusText()))
    }

    companion object {
        private const val CHANNEL_ID = "takip"
        private const val NOTIF_ID = 1

        /** İzin verilmişse servisi başlatır (zaten çalışıyorsa zararsız). */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TrackerService::class.java))
        }
    }
}
