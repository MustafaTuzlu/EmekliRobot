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
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.emekli.robot.core.BatterySnapshot
import com.emekli.robot.core.BestFixWindow
import com.emekli.robot.core.Config
import com.emekli.robot.core.CornerDetector
import com.emekli.robot.core.Fix
import com.emekli.robot.core.FixFilter
import com.emekli.robot.core.LogTypes
import com.emekli.robot.core.MovementDetector
import com.emekli.robot.core.ParkedPosition
import com.emekli.robot.core.Pos
import com.emekli.robot.core.RuntimeState
import com.emekli.robot.core.Speed
import com.emekli.robot.core.StableValue
import com.emekli.robot.core.ThermalGovernor
import com.emekli.robot.core.ThermalState
import com.emekli.robot.core.TimeFmt
import com.emekli.robot.data.AppDatabase
import com.emekli.robot.data.LogEntity
import com.emekli.robot.sync.SyncPolicy
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
    private val requestSync: (urgent: Boolean) -> Unit,
    private val onModeChanged: () -> Unit,
) {
    private enum class GpsMode { OFF, MOVING, PROBE }

    private val main = Handler(Looper.getMainLooper())
    // Yalnızca gerçek GPS: fused sağlayıcı GPS yokken Wi-Fi/baz tahmini (100-600 m sapma) verdiği için kullanılmaz.
    private val lm = ctx.getSystemService(LocationManager::class.java)
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
    private var consecutiveJumps = 0
    private var rejectedFixes = 0
    private var lastRejectedAccM: Double? = null

    // Hareketteyken 1 sn'lik örnekler pencerede toplanır, her pencerenin en iyisi yazılır.
    private val window = BestFixWindow<Location>()
    private val corner = CornerDetector()
    private val parked = ParkedPosition()
    private var movePrev: Fix? = null
    private var lastEmittedNanos = 0L

    // DURUM için iyi fix bekleniyor (soğuk başlangıçta ilk fix genelde zayıf gelir)
    private var pendingDurum = false
    private var durumGotFix = false
    private var durumGoodUntil = 0L

    private var gpsMode = GpsMode.OFF
    private var currentInterval = 0L
    private var probeUntil = 0L
    private var noFixWatching = false
    private var started = false

    private val motionSensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    private var motionTriggers = 0
    private var lastMotionElapsedMs: Long? = null

    // ---- yaşam döngüsü -------------------------------------------------------------------------

    fun start() {
        started = true
        ContextCompat.registerReceiver(
            ctx, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        armMotion()
        rescheduleProbeAlarm()
        scheduleAlarm(ACTION_HEARTBEAT, Config.HEARTBEAT_MS)
        try {
            val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (last != null && lastLoc == null) acceptSilently(last, seedAnchor = false)
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
        stopUpdates()
        window.clear()
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
                rescheduleProbeAlarm()
                scheduleProbeNow()
            }
        }
    }

    /**
     * Hareket sensörüne güvenmeyen yedek yoklama:
     * - şarj takılıyken (kontak açık) her dakika kısa GPS penceresi,
     * - şarj yokken yalnızca cihazda hareket sensörü yoksa 3 dk'da bir.
     * Şarj yokken ve sensör varsa alarm iptal edilir (GPS tamamen beklemede kalır).
     */
    private fun rescheduleProbeAlarm() {
        val delay = when {
            chargeStable.committed -> Config.PLUGGED_PROBE_PERIOD_MS
            motionSensor == null -> Config.FALLBACK_PROBE_MS
            else -> null
        }
        if (delay != null) scheduleAlarm(ACTION_PROBE, delay) else cancelAlarm(ACTION_PROBE)
    }

    /** Alarmla gelen yoklama. Isınmışken (NORMAL değilse) şarjlı yoklama atlanır, GPS yorulmaz. */
    private fun scheduleProbeNow() {
        if (chargeStable.committed) {
            if (thermalStable.committed == ThermalState.NORMAL) startProbe(Config.PLUGGED_PROBE_WINDOW_MS)
        } else {
            startProbe(Config.FALLBACK_PROBE_WINDOW_MS)
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
        // Kayıt, debounce beklemesi değil değişimin ilk görüldüğü an olarak damgalanır.
        record(
            if (plugged) LogTypes.SARJ_BASLADI else LogTypes.SARJ_KESILDI,
            backdateMs = SystemClock.elapsedRealtime() - chargeStable.committedSince,
        )
        rescheduleProbeAlarm()
        if (plugged) startProbe(Config.PROBE_MS) // kontak açıldı: araç kısa süre sonra hareket edebilir
        applyMode()
    }

    private fun onThermalChanged(from: ThermalState, to: ThermalState) {
        ThermalGovernor.logTypeFor(from, to)?.let {
            record(it, backdateMs = SystemClock.elapsedRealtime() - thermalStable.committedSince)
        }
        applyMode()
        if (to == ThermalState.NORMAL) requestSync(false)
    }

    private fun gpsAllowed(): Boolean =
        thermalStable.committed != ThermalState.KRITIK &&
            !(battery.pct in 0 until Config.LOW_BATTERY_PCT && !battery.plugged) &&
            hasLocationPermission()

    private fun hasLocationPermission() =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // ---- GPS yönetimi --------------------------------------------------------------------------

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("API 29'da soyut, uygulanmak zorunda")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private fun flushIntervalMs() =
        if (thermalStable.committed == ThermalState.ISINMIS) Config.HOT_INTERVAL_MS else Config.MOVING_INTERVAL_MS

    private val flushRunnable = object : Runnable {
        override fun run() {
            if (!movement.moving || gpsMode != GpsMode.MOVING) return
            flushWindow()
            main.postDelayed(this, flushIntervalMs())
        }
    }

    private fun applyMode() {
        val th = thermalStable.committed
        val probing = SystemClock.elapsedRealtime() < probeUntil
        val (mode, interval) = when {
            !gpsAllowed() -> GpsMode.OFF to 0L
            movement.moving ->
                GpsMode.MOVING to (if (th == ThermalState.ISINMIS) Config.HOT_SAMPLE_INTERVAL_MS else Config.SAMPLE_INTERVAL_MS)
            probing -> GpsMode.PROBE to Config.PROBE_INTERVAL_MS
            else -> GpsMode.OFF to 0L
        }
        if (mode == gpsMode && interval == currentInterval) {
            publish()
            return
        }
        stopUpdates()
        main.removeCallbacks(flushRunnable)
        window.clear()
        gpsMode = mode
        currentInterval = interval
        if (mode != GpsMode.OFF) startUpdates(interval)
        if (mode == GpsMode.MOVING) {
            lastFixElapsedMs = SystemClock.elapsedRealtime()
            corner.reset()
            main.postDelayed(flushRunnable, flushIntervalMs())
            startNoFixWatch()
        }
        publish()
        onModeChanged()
    }

    private fun startUpdates(intervalMs: Long) {
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, locationListener, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.w(TAG, "Konum izni yok", e)
            gpsMode = GpsMode.OFF
            currentInterval = 0
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "GPS sağlayıcı yok", e)
            gpsMode = GpsMode.OFF
            currentInterval = 0
        }
    }

    private fun stopUpdates() {
        runCatching { lm.removeUpdates(locationListener) }
    }

    /** `windowMs` süreli kısa GPS penceresi açar. Zaten daha uzun bir pencere varsa onu kısaltmaz. */
    private fun startProbe(windowMs: Long = Config.PROBE_MS) {
        if (movement.moving || !gpsAllowed()) return
        val now = SystemClock.elapsedRealtime()
        probeUntil = maxOf(probeUntil, now + windowMs)
        val remaining = probeUntil - now
        wake.acquire(remaining + 10_000)
        applyMode()
        main.removeCallbacks(probeEnd)
        main.postDelayed(probeEnd, remaining + 500)
    }

    private val probeEnd = Runnable { applyMode() }

    // ---- hareket sensörü (durağanken GPS yerine) -----------------------------------------------

    private val motionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            main.post {
                motionTriggers++
                lastMotionElapsedMs = SystemClock.elapsedRealtime()
                startProbe()
                publish()
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
        hasBearing = loc.hasBearing(), bearingDeg = loc.bearing.toDouble(),
    )

    private class Gated(val fix: Fix, val speedPrev: Fix?)

    @Suppress("DEPRECATION")
    private fun isMock(loc: Location) = loc.isFromMockProvider

    /** Uydu sayısı bilinmiyorsa -1. GPS sağlayıcı "satellites" ekstrasını verir. */
    private fun satellites(loc: Location): Int = loc.extras?.getInt("satellites", -1) ?: -1

    /** Kalite kapısı: sahte, uydusuz, tekrar, zayıf ve imkânsız sıçrama konumlar elenir. */
    private fun gate(loc: Location): Gated? {
        val prev = lastFix
        val fix = buildFix(loc)
        val sats = satellites(loc)
        if (isMock(loc) || (sats in 0 until Config.MIN_SATELLITES)) {
            rejectFix(fix)
            return null
        }
        var speedPrev: Fix? = prev
        when (FixFilter.check(prev, fix)) {
            FixFilter.Verdict.DUPLICATE -> return null
            FixFilter.Verdict.POOR_ACCURACY -> {
                rejectFix(fix)
                return null
            }
            FixFilter.Verdict.IMPLAUSIBLE_JUMP -> {
                if (++consecutiveJumps < Config.MAX_CONSECUTIVE_JUMPS) {
                    rejectFix(fix)
                    return null
                }
                speedPrev = null // üst üste sıçrama: eski referans yanlış sayılır, yeni konum kabul edilir
            }
            FixFilter.Verdict.OK -> {}
        }
        consecutiveJumps = 0
        lastLoc = loc
        lastFix = fix
        lastFixElapsedMs = SystemClock.elapsedRealtime()
        noFixFlagged = false
        return Gated(fix, speedPrev)
    }

    private fun onLocation(loc: Location) {
        val g = gate(loc) ?: return
        if (movement.moving) onMovingSample(loc, g) else onProbeFix(loc, g)
        publish()
    }

    /** Hareketteyken: örnek pencereye girer; yön belirgin değiştiyse (köşe) pencere beklenmeden yazılır. */
    private fun onMovingSample(loc: Location, g: Gated) {
        val fix = g.fix
        if (g.speedPrev == null && movePrev != null) { // referans sıfırlandı
            movePrev = null
            window.clear()
        }
        val now = SystemClock.elapsedRealtime()
        val spd = if (fix.hasSpeed) fix.speedMps * 3.6 else null
        val brg = if (fix.hasBearing) fix.bearingDeg else null
        val isCorner = thermalStable.committed == ThermalState.NORMAL &&
            corner.isCorner(brg, spd, fix.accuracyM, now)
        if (isCorner) {
            window.clear()
            window.offer(fix, loc)
            flushWindow()
            main.removeCallbacks(flushRunnable)
            main.postDelayed(flushRunnable, flushIntervalMs())
        } else {
            window.offer(fix, loc)
        }
    }

    /** Penceredeki en iyi fix'i hareket kararına verir ve KONUM olarak yazar. */
    private fun flushWindow() {
        val (loc, fix) = window.take() ?: return
        if (fix.elapsedNanos == lastEmittedNanos) return
        lastEmittedNanos = fix.elapsedNanos
        val now = SystemClock.elapsedRealtime()
        val pos = Pos(fix.lat, fix.lon, fix.accuracyM)
        val change = movement.onFix(Speed.kmh(fix, movePrev), pos, now)
        movePrev = fix
        when (change) {
            MovementDetector.Change.STOPPED -> {
                record(LogTypes.ARAC_DURUYOR, loc = loc, backdateMs = ageMs(loc))
                parked.clear()
                parked.add(fix.lat, fix.lon, fix.accuracyM, now - ageMs(loc))
                applyMode() // GPS kapanır
            }
            MovementDetector.Change.STARTED -> {} // zaten hareketteydi, olmaz
            MovementDetector.Change.NONE -> {
                record(LogTypes.KONUM, loc = loc, backdateMs = ageMs(loc))
                corner.onEmitted(if (fix.hasBearing) fix.bearingDeg else null, now)
            }
        }
    }

    /** Durağanken (yoklama): her fix hemen hareket kararına girer; park konumu medyanı beslenir. */
    private fun onProbeFix(loc: Location, g: Gated) {
        val fix = g.fix
        val now = SystemClock.elapsedRealtime()
        parked.add(fix.lat, fix.lon, fix.accuracyM, now - ageMs(loc))
        if (pendingDurum) durumGotFix = true

        val pos = Pos(fix.lat, fix.lon, fix.accuracyM)
        when (movement.onFix(Speed.kmh(fix, g.speedPrev), pos, now)) {
            MovementDetector.Change.STARTED -> {
                record(LogTypes.ARAC_HAREKET, loc = loc, backdateMs = ageMs(loc))
                cancelPendingDurum()
                probeUntil = 0
                parked.clear()
                movePrev = fix
                lastEmittedNanos = fix.elapsedNanos
                applyMode()
                corner.onEmitted(if (fix.hasBearing) fix.bearingDeg else null, now)
            }
            else -> if (pendingDurum && durumReady(fix, now)) finishDurum()
        }
    }

    /** DURUM için fix yeterince iyi mi: ≤20 m (ya da bekleme süresi doldu) ve park medyanı oluştu. */
    private fun durumReady(fix: Fix, now: Long): Boolean {
        val good = fix.accuracyM <= Config.GOOD_FIX_ACC_M || now >= durumGoodUntil
        return good && (parked.estimate(now) != null || now >= durumGoodUntil)
    }

    private fun rejectFix(fix: Fix) {
        rejectedFixes++
        lastRejectedAccM = fix.accuracyM.takeIf { it < 9999.0 }
        publish()
    }

    /**
     * Hareket kararına karışmadan son konumu günceller (kalp atışı fix'i, son bilinen konum).
     * Zayıf (>50 m) konum DURUM kaydında doğruluğuyla birlikte görünebilir (garajda/evde yine de bir fikir verir)
     * ama referans nokta ve hız hesabı için kullanılmaz.
     */
    private fun acceptSilently(loc: Location, seedAnchor: Boolean = true) {
        val fix = buildFix(loc)
        lastLoc = loc
        if (FixFilter.isReliable(fix)) {
            lastFix = fix
            // Eski "son bilinen konum" referans olmaz: araç o zamandan beri taşınmış olabilir.
            if (seedAnchor) movement.seedAnchor(Pos(fix.lat, fix.lon, fix.accuracyM))
        }
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
            record(LogTypes.DURUM, useParked = true)
            startProbe(Config.HEARTBEAT_PROBE_WINDOW_MS)
            return
        }
        // Fix'i yoklama penceresi getirir; iyi fix gelince (ya da süre dolunca) DURUM yazılır.
        val now = SystemClock.elapsedRealtime()
        pendingDurum = true
        durumGotFix = false
        durumGoodUntil = now + Config.GOOD_FIX_WAIT_MS
        startProbe(Config.FIX_TIMEOUT_MS)
        main.removeCallbacks(durumTimeout)
        main.postDelayed(durumTimeout, Config.FIX_TIMEOUT_MS)
    }

    private val durumTimeout = Runnable {
        if (pendingDurum) {
            if (!durumGotFix) flagNoFix()
            finishDurum()
        }
    }

    private fun cancelPendingDurum() {
        pendingDurum = false
        main.removeCallbacks(durumTimeout)
    }

    private fun finishDurum() {
        cancelPendingDurum()
        record(LogTypes.DURUM, useParked = true)
        // Hareket sensörü kaçırmış olabilir: GPS ısınmışken kısa bir hız kontrolü, sonra pencere kapanır
        val now = SystemClock.elapsedRealtime()
        if (!movement.moving && gpsAllowed()) {
            probeUntil = now + Config.HEARTBEAT_PROBE_WINDOW_MS
            main.removeCallbacks(probeEnd)
            main.postDelayed(probeEnd, Config.HEARTBEAT_PROBE_WINDOW_MS + 500)
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
    private fun record(
        type: String,
        withLocation: Boolean = true,
        backdateMs: Long = 0L,
        loc: Location? = lastLoc,
        useParked: Boolean = false,
    ) {
        // Debounce'lu olaylar ve taze fix'li kayıtlar (KONUM, ARAC_*) gerçek an'a geri çekilir; zaman ve elapsedRealtime birlikte kayar.
        val back = backdateMs.coerceIn(0L, 300_000L)
        val nowElapsed = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis() - back

        var lat: Double? = null
        var lon: Double? = null
        var acc: Double? = null
        var age: Long? = null
        var speed: Double? = null
        var bearing: Double? = null
        if (withLocation) {
            val est = if (useParked) parked.estimate(nowElapsed) else null
            if (est != null) {
                lat = est.lat; lon = est.lon; acc = est.accM
                age = (nowElapsed - est.newestElapsedMs).coerceAtLeast(0)
            } else if (loc != null) {
                lat = loc.latitude; lon = loc.longitude
                acc = loc.takeIf { it.hasAccuracy() }?.accuracy?.toDouble()
                age = (ageMs(loc) - back).coerceAtLeast(0)
                if (age <= 5_000 && loc.hasSpeed()) { // hız/yön yalnızca taze fix'te anlamlı
                    speed = loc.speed * 3.6
                    if (loc.hasBearing() && speed >= Config.MIN_BEARING_SPEED_KMH) bearing = loc.bearing.toDouble()
                }
            }
        }
        val entity = LogEntity(
            uuid = UUID.randomUUID().toString(),
            logType = type,
            lat = lat,
            lon = lon,
            accuracy = acc,
            batteryPct = battery.pct.takeIf { it >= 0 },
            charging = battery.plugged,
            batteryTempC = battery.tempC,
            moving = movement.moving,
            eventTime = TimeFmt.iso(wall),
            eventEpochMs = wall,
            elapsedRealtimeMs = nowElapsed - back,
            locationAgeMs = age,
            speedKmh = speed,
            bearingDeg = bearing,
        )
        scope.launch {
            try {
                dao.insert(entity)
                // Sıcaklık uyarıları gönderim durmuş olsa da hemen gönderilir (SyncPolicy).
                val urgent = SyncPolicy.isUrgentType(type)
                main.post { requestSync(urgent) }
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

    private fun cancelAlarm(action: String) {
        val pi = PendingIntent.getForegroundService(
            ctx, action.hashCode(), Intent(ctx, TrackerService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarms.cancel(pi)
        pi.cancel()
    }

    private fun publish() {
        val loc = lastLoc
        RuntimeState.update {
            it.copy(
                running = started,
                moving = movement.moving,
                gpsMode = when (gpsMode) {
                    GpsMode.OFF -> when {
                        !started -> "KAPALI"
                        !hasLocationPermission() -> "KAPALI (konum izni yok)"
                        thermalStable.committed == ThermalState.KRITIK -> "DURDURULDU (aşırı sıcaklık)"
                        battery.pct in 0 until Config.LOW_BATTERY_PCT && !battery.plugged -> "DURDURULDU (pil düşük)"
                        else -> "BEKLEMEDE (durağan, pil tasarrufu)"
                    }
                    GpsMode.MOVING -> "AÇIK (${flushIntervalMs() / 1000} sn)"
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
                motionSensor = motionSensor != null,
                motionTriggers = motionTriggers,
                lastMotionElapsedMs = lastMotionElapsedMs,
                rejectedFixes = rejectedFixes,
                lastRejectedAccM = lastRejectedAccM,
            )
        }
    }

    companion object {
        private const val TAG = "TrackerEngine"
        const val ACTION_HEARTBEAT = "com.emekli.robot.HEARTBEAT"
        const val ACTION_PROBE = "com.emekli.robot.PROBE"
    }
}
