package com.emekli.robot.service

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.emekli.robot.core.BatterySnapshot
import com.emekli.robot.core.Config
import com.emekli.robot.core.Fix
import com.emekli.robot.core.LogTypes
import com.emekli.robot.core.MovementDetector
import com.emekli.robot.core.RuntimeState
import com.emekli.robot.core.Speed
import com.emekli.robot.core.StableValue
import com.emekli.robot.core.ThermalGovernor
import com.emekli.robot.core.ThermalState
import com.emekli.robot.core.TimeFmt
import com.emekli.robot.data.AppDatabase
import com.emekli.robot.data.LogEntity
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Takip motoru. Tüm olaylar ana iş parçacığında işlenir; veritabanı yazımı IO'da yapılır.
 * Kurallar CLAUDE.md "Kurallar ve Eşikler" ve "Termal Koruma" bölümleriyle birebir aynıdır.
 *
 * Güç tasarımı: araç durağanken GPS KAPALI. Uyanma kaynakları: şarj bağlanması (kontak),
 * "significant motion" sensörü, 15 dk'lık alarm. Uyanınca 60 sn'lik kısa bir GPS penceresi
 * (probe) açılır, hız 5 km/h üstü görülürse HAREKET moduna geçilir.
 */
class TrackerEngine(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val requestSync: () -> Unit,
    private val onModeChanged: () -> Unit,
) {
    private enum class GpsMode { OFF, MOVING, PROBE }

    private val main = Handler(Looper.getMainLooper())
    private val fused = LocationServices.getFusedLocationProviderClient(ctx)
    private val dao = AppDatabase.get(ctx).logDao()
    private val alarms = ctx.getSystemService(AlarmManager::class.java)
    private val sensors = ctx.getSystemService(SensorManager::class.java)
    private val wake = ctx.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "emekli:engine")
        .apply { setReferenceCounted(false) }

    private var battery = BatterySnapshot.read(ctx)
    private val chargeStable = StableValue(battery.plugged, Config.CHARGE_DEBOUNCE_MS)
    private val thermalStable = StableValue(ThermalState.NORMAL, Config.THERMAL_DEBOUNCE_MS)
    private val movement = MovementDetector()

    private var lastLoc: Location? = null
    private var lastFix: Fix? = null
    private var lastFixElapsedMs = 0L
    private var noFixFlagged = false

    private var gpsMode = GpsMode.OFF
    private var currentInterval = 0L
    private var probeUntil = 0L
    private var noFixWatching = false
    private var heartbeatToken: CancellationTokenSource? = null
    private var started = false

    private val motionSensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

    // ---- yaşam döngüsü -------------------------------------------------------------------------

    fun start() {
        started = true
        ContextCompat.registerReceiver(
            ctx, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        armMotion()
        if (motionSensor == null) scheduleAlarm(ACTION_PROBE, Config.FALLBACK_PROBE_MS)
        scheduleAlarm(ACTION_HEARTBEAT, Config.HEARTBEAT_MS)
        try {
            fused.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && lastLoc == null) acceptSilently(loc)
            }
        } catch (_: SecurityException) { }
        evaluateBattery()
        // Başlangıçta hemen bir DURUM kaydı (servisin çalıştığını gösterir).
        doHeartbeat()
        publish()
    }

    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacksAndMessages(null)
        runCatching { ctx.unregisterReceiver(batteryReceiver) }
        runCatching { motionSensor?.let { sensors.cancelTriggerSensor(motionListener, it) } }
        heartbeatToken?.cancel()
        heartbeatToken = null
        stopUpdates()
        if (wake.isHeld) wake.release()
        RuntimeState.update { it.copy(running = false, gpsMode = "KAPALI") }
    }

    /** Alarm geldiğinde servisten çağrılır. */
    fun onAlarm(action: String?) {
        when (action) {
            ACTION_HEARTBEAT -> {
                scheduleAlarm(ACTION_HEARTBEAT, Config.HEARTBEAT_MS)
                doHeartbeat()
            }
            ACTION_PROBE -> {
                scheduleAlarm(ACTION_PROBE, Config.FALLBACK_PROBE_MS)
                startProbe()
            }
        }
    }

    // ---- pil, şarj, termal ---------------------------------------------------------------------

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            battery = BatterySnapshot.from(intent)
            evaluateBattery()
            publish()
        }
    }

    private val reevaluate = Runnable {
        battery = BatterySnapshot.read(ctx)
        evaluateBattery()
        publish()
    }

    private fun evaluateBattery() {
        val now = SystemClock.elapsedRealtime()
        if (chargeStable.update(battery.plugged, now)) onChargeChanged(chargeStable.committed)

        val before = thermalStable.committed
        val target = ThermalGovernor.target(battery.tempC, before)
        if (thermalStable.update(target, now)) onThermalChanged(before, thermalStable.committed)

        if (chargeStable.hasPending || thermalStable.hasPending) {
            wake.acquire(40_000) // debounce süresince işlemci uyumasın
            main.removeCallbacks(reevaluate)
            main.postDelayed(reevaluate, 5_000)
        }
    }

    private fun onChargeChanged(plugged: Boolean) {
        record(if (plugged) LogTypes.SARJ_BASLADI else LogTypes.SARJ_KESILDI)
        if (plugged) startProbe() // kontak açıldı: araç kısa süre sonra hareket edebilir
        applyMode()
    }

    private fun onThermalChanged(from: ThermalState, to: ThermalState) {
        ThermalGovernor.logTypeFor(from, to)?.let { record(it) }
        applyMode()
        if (to == ThermalState.NORMAL) requestSync()
    }

    private fun gpsAllowed(): Boolean =
        thermalStable.committed != ThermalState.KRITIK &&
            !(battery.pct in 0 until Config.LOW_BATTERY_PCT && !battery.plugged) &&
            hasLocationPermission()

    private fun hasLocationPermission() =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // ---- GPS yönetimi --------------------------------------------------------------------------

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onLocation(it) }
        }
    }

    private fun applyMode() {
        val th = thermalStable.committed
        val probing = SystemClock.elapsedRealtime() < probeUntil
        val (mode, interval) = when {
            !gpsAllowed() -> GpsMode.OFF to 0L
            movement.moving ->
                GpsMode.MOVING to (if (th == ThermalState.ISINMIS) Config.HOT_INTERVAL_MS else Config.MOVING_INTERVAL_MS)
            probing -> GpsMode.PROBE to Config.PROBE_INTERVAL_MS
            else -> GpsMode.OFF to 0L
        }
        if (mode == gpsMode && interval == currentInterval) {
            publish()
            return
        }
        stopUpdates()
        gpsMode = mode
        currentInterval = interval
        if (mode != GpsMode.OFF) startUpdates(interval)
        if (mode == GpsMode.MOVING) {
            lastFixElapsedMs = SystemClock.elapsedRealtime()
            startNoFixWatch()
        }
        publish()
        onModeChanged()
    }

    private fun startUpdates(intervalMs: Long) {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            fused.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.w(TAG, "Konum izni yok", e)
            gpsMode = GpsMode.OFF
            currentInterval = 0
        }
    }

    private fun stopUpdates() {
        runCatching { fused.removeLocationUpdates(locationCallback) }
    }

    private fun startProbe() {
        if (movement.moving || !gpsAllowed()) return
        probeUntil = SystemClock.elapsedRealtime() + Config.PROBE_MS
        wake.acquire(Config.PROBE_MS + 10_000)
        applyMode()
        main.removeCallbacks(probeEnd)
        main.postDelayed(probeEnd, Config.PROBE_MS + 500)
    }

    private val probeEnd = Runnable { applyMode() }

    // ---- hareket sensörü (durağanken GPS yerine) -----------------------------------------------

    private val motionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            main.post {
                startProbe()
                main.postDelayed({ armMotion() }, 30_000) // art arda tetiklenmeyi sınırla
            }
        }
    }

    private fun armMotion() {
        if (!started) return
        motionSensor?.let { runCatching { sensors.requestTriggerSensor(motionListener, it) } }
    }

    // ---- konum işleme --------------------------------------------------------------------------

    private fun buildFix(loc: Location) = Fix(
        lat = loc.latitude, lon = loc.longitude,
        accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 9999.0,
        elapsedNanos = loc.elapsedRealtimeNanos,
        hasSpeed = loc.hasSpeed(), speedMps = loc.speed.toDouble(),
    )

    private fun onLocation(loc: Location) {
        val prev = lastFix
        val fix = buildFix(loc)
        lastLoc = loc
        lastFix = fix
        lastFixElapsedMs = SystemClock.elapsedRealtime()
        noFixFlagged = false

        when (movement.onSpeed(Speed.kmh(fix, prev), SystemClock.elapsedRealtime())) {
            MovementDetector.Change.STARTED -> {
                record(LogTypes.ARAC_HAREKET)
                probeUntil = 0
                applyMode()
            }
            MovementDetector.Change.STOPPED -> {
                record(LogTypes.ARAC_DURUYOR)
                applyMode() // GPS kapanır
            }
            MovementDetector.Change.NONE -> if (movement.moving) record(LogTypes.KONUM)
        }
        publish()
    }

    /** Hareket kararına karışmadan son konumu günceller (kalp atışı fix'i, son bilinen konum). */
    private fun acceptSilently(loc: Location) {
        lastLoc = loc
        lastFix = buildFix(loc)
        lastFixElapsedMs = SystemClock.elapsedRealtime()
        noFixFlagged = false
        publish()
    }

    private val noFixCheck = object : Runnable {
        override fun run() {
            if (!movement.moving || gpsMode != GpsMode.MOVING) {
                noFixWatching = false
                return
            }
            val silentMs = SystemClock.elapsedRealtime() - lastFixElapsedMs
            if (silentMs >= Config.NO_FIX_MS && !noFixFlagged) flagNoFix()
            main.postDelayed(this, 15_000)
        }
    }

    private fun startNoFixWatch() {
        if (noFixWatching) return
        noFixWatching = true
        main.postDelayed(noFixCheck, 15_000)
    }

    /** Konum geri gelene kadar tek bir KONUM_ALINAMADI yazılır. */
    private fun flagNoFix() {
        if (noFixFlagged) return
        noFixFlagged = true
        record(LogTypes.KONUM_ALINAMADI, withLocation = false)
        publish()
    }

    // ---- 15 dk'lık kalp atışı (DURUM) ----------------------------------------------------------

    private fun doHeartbeat() {
        wake.acquire(Config.FIX_TIMEOUT_MS + 30_000)
        trimQueue()
        if (movement.moving) return // hareketteyken KONUM kayıtları zaten akıyor

        val age = lastLoc?.let { ageMs(it) }
        val needFix = gpsAllowed() && (age == null || age > Config.STALE_FIX_MS)
        if (!needFix) {
            record(LogTypes.DURUM)
            return
        }
        heartbeatToken?.cancel()
        val cts = CancellationTokenSource().also { heartbeatToken = it }
        val req = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(Config.FIX_TIMEOUT_MS)
            .setMaxUpdateAgeMillis(0)
            .build()
        try {
            fused.getCurrentLocation(req, cts.token).addOnCompleteListener { task ->
                val loc = if (task.isSuccessful) task.result else null
                if (loc != null) acceptSilently(loc) else flagNoFix()
                record(LogTypes.DURUM)
            }
            main.postDelayed({ cts.cancel() }, Config.FIX_TIMEOUT_MS + 5_000)
        } catch (e: SecurityException) {
            flagNoFix()
            record(LogTypes.DURUM)
        }
    }

    private fun trimQueue() {
        scope.launch {
            try {
                val total = dao.total()
                if (total > Config.QUEUE_CAP) dao.deleteOldestKonum(total - Config.QUEUE_CAP + 1_000)
            } catch (t: Throwable) {
                Log.e(TAG, "Kuyruk budama hatası", t)
            }
        }
    }

    // ---- kayıt oluşturma -----------------------------------------------------------------------

    private fun ageMs(loc: Location): Long =
        ((SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000).coerceAtLeast(0)

    /**
     * Kaydı oluşturur ve kuyruğa yazar. `eventTime` kaydın oluştuğu andaki duvar saatidir;
     * konumun kendi yaşı ayrıca `locationAgeMs` olarak gider. Son bilinen konum (yaşıyla birlikte)
     * park halindeki araç için doğru yerdir, bu yüzden eski olsa da eklenir.
     */
    private fun record(type: String, withLocation: Boolean = true) {
        val wall = System.currentTimeMillis()
        val loc = if (withLocation) lastLoc else null
        val entity = LogEntity(
            uuid = UUID.randomUUID().toString(),
            logType = type,
            lat = loc?.latitude,
            lon = loc?.longitude,
            accuracy = loc?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble(),
            batteryPct = battery.pct.takeIf { it >= 0 },
            charging = battery.plugged,
            batteryTempC = battery.tempC,
            moving = movement.moving,
            eventTime = TimeFmt.iso(wall),
            eventEpochMs = wall,
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
            locationAgeMs = loc?.let { ageMs(it) },
        )
        scope.launch {
            try {
                dao.insert(entity)
                main.post { requestSync() }
            } catch (t: Throwable) {
                Log.e(TAG, "Kayıt yazılamadı: $type", t)
            }
        }
    }

    // ---- alarm ve durum yayını -----------------------------------------------------------------

    private fun scheduleAlarm(action: String, delayMs: Long) {
        val pi = PendingIntent.getForegroundService(
            ctx, action.hashCode(), Intent(ctx, TrackerService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val at = SystemClock.elapsedRealtime() + delayMs
        val exactOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exactOk) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }
    }

    private fun publish() {
        val loc = lastLoc
        RuntimeState.update {
            it.copy(
                running = started,
                moving = movement.moving,
                gpsMode = when (gpsMode) {
                    GpsMode.OFF -> "KAPALI"
                    GpsMode.MOVING -> "AÇIK (${currentInterval / 1000} sn)"
                    GpsMode.PROBE -> "AÇIK (hareket kontrolü)"
                },
                thermal = thermalStable.committed,
                plugged = battery.plugged,
                batteryPct = battery.pct.takeIf { p -> p >= 0 },
                batteryTempC = battery.tempC,
                lat = loc?.latitude,
                lon = loc?.longitude,
                accuracyM = loc?.takeIf { l -> l.hasAccuracy() }?.accuracy?.toDouble(),
                fixElapsedMs = if (loc != null) lastFixElapsedMs else null,
                noFix = noFixFlagged,
            )
        }
    }

    companion object {
        private const val TAG = "TrackerEngine"
        const val ACTION_HEARTBEAT = "com.emekli.robot.HEARTBEAT"
        const val ACTION_PROBE = "com.emekli.robot.PROBE"
    }
}
