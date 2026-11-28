package com.emekli.robot.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.emekli.robot.core.Config
import com.emekli.robot.core.GpsPoint
import com.emekli.robot.core.ImpactDetector
import com.emekli.robot.core.ImpactExport
import com.emekli.robot.core.ImpactPolicy
import com.emekli.robot.core.ImpactPrune
import com.emekli.robot.core.ImpactUpdate
import com.emekli.robot.core.RuntimeState
import com.emekli.robot.data.ImpactDatabase
import com.emekli.robot.data.ImpactEntity
import com.emekli.robot.data.ImpactWaveEntity
import com.emekli.robot.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/**
 * Olası darbe sensör kaydedicisi. Yalnız telefonda veri toplar; log kuyruğuna ve sunucuya hiçbir şey yazmaz.
 * Çağrılar ana iş parçacığından gelir; sensör verisi ve algılayıcı kendi iş parçacığında çalışır.
 * Çalışırken (yalnız o zaman) kısmi wake lock tutulur: ekran kapalıyken işlemci uyursa sensör verisi kesilir.
 */
class ImpactRecorder(
    private val ctx: Context,
    /** Politika girdileri (enabled hariç); ana iş parçacığında okunur. */
    private val inputs: () -> ImpactPolicy.Inputs,
) {
    private val main = Handler(Looper.getMainLooper())
    private val sensors = ctx.getSystemService(SensorManager::class.java)
    private val wake = ctx.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "emekli:impact")
        .apply { setReferenceCounted(false) }

    private var session: Session? = null
    private var enabled = true
    private var enabledReadAt = 0L
    private var offText = "?"
    private var wakeAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            evaluate()
            if (session != null) main.postDelayed(this, TICK_MS)
        }
    }

    /** Duruma göre algılamayı açar/kapatır. Sık çağrılabilir. */
    fun evaluate() {
        val now = SystemClock.elapsedRealtime()
        if (enabledReadAt == 0L || now - enabledReadAt > 5_000) {
            enabledReadAt = now
            enabled = runCatching { SettingsStore.get(ctx).impactEnabled }.getOrDefault(true)
        }
        val reason = ImpactPolicy.offReason(inputs().copy(enabled = enabled))
        val s = session
        if (reason == null) {
            if (s == null) start()
            // evaluate sık çağrılır: süreli kilit dakikada bir yenilenir.
            if (session != null && (!wake.isHeld || now - wakeAt > 60_000)) holdWake(now)
        } else {
            offText = "KAPALI ($reason)"
            if (s != null) stop() else publish(offText)
        }
    }

    fun onGps(loc: Location, satellites: Int, rejected: Boolean) {
        val s = session ?: return
        val p = GpsPoint(
            tNanos = loc.elapsedRealtimeNanos,
            lat = loc.latitude,
            lon = loc.longitude,
            speedKmh = if (loc.hasSpeed()) loc.speed * 3.6 else null,
            bearingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else null,
            accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
            satellites = satellites.takeIf { it >= 0 },
            rejected = rejected,
        )
        s.handler.post { s.detector?.onGps(p) }
    }

    fun shutdown() {
        main.removeCallbacks(tick)
        if (session != null) stop()
    }

    private fun start() {
        val accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accel == null) {
            publish("YOK (ivmeölçer bulunamadı)")
            return
        }
        val s = Session(accel)
        if (!s.register()) {
            s.close()
            publish("HATA (sensör açılamadı)")
            return
        }
        session = s
        holdWake(SystemClock.elapsedRealtime())
        publish("AÇIK · hız ölçülüyor")
        main.removeCallbacks(tick)
        main.postDelayed(tick, TICK_MS)
    }

    private fun stop() {
        val s = session ?: return
        session = null
        main.removeCallbacks(tick)
        // Bekleyen olaylar kayda geçene kadar işlemci uyumasın.
        wake.acquire(10_000)
        s.close()
        publish(offText)
    }

    private fun holdWake(now: Long) {
        wakeAt = now
        wake.acquire(WAKE_TIMEOUT_MS)
    }

    private fun publish(text: String) {
        RuntimeState.update { it.copy(impactStatus = text) }
    }

    /** Bir açık kalma dönemi: kendi iş parçacığı, algılayıcısı ve sensör kayıtları. */
    private inner class Session(private val accel: Sensor) : SensorEventListener {
        private val thread = HandlerThread("impact-sensors").apply { start() }
        val handler = Handler(thread.looper)
        private val gyro: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        private val baro: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_PRESSURE)
        var detector: ImpactDetector? = ImpactDetector(accel.maximumRange, ::persist)
            private set

        /** Olay anahtarı -> kayıt UUID'si (yalnız sensör iş parçacığı). */
        private val ids = HashMap<Long, String>()

        /** Sensör zaman damgası ile elapsedRealtimeNanos arasındaki fark (bazı cihazlarda saatler farklıdır). */
        private var clockOffset: Long? = null
        private var rateCount = 0
        private var rateSince = 0L
        private var rateHz = 0.0

        fun register(): Boolean {
            if (!sensors.registerListener(this, accel, Config.IMPACT_SAMPLING_US, 0, handler)) return false
            gyro?.let { sensors.registerListener(this, it, Config.IMPACT_SAMPLING_US, 0, handler) }
            baro?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, 0, handler) }
            return true
        }

        fun close() {
            sensors.unregisterListener(this)
            handler.post {
                runCatching { detector?.flush() }.onFailure { Log.e(TAG, "Olay kapatılamadı", it) }
                detector = null
                thread.quitSafely()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        override fun onSensorChanged(event: SensorEvent) {
            val d = detector ?: return
            val offset = clockOffset ?: run {
                val diff = SystemClock.elapsedRealtimeNanos() - event.timestamp
                (if (kotlin.math.abs(diff) > 2_000_000_000L) diff else 0L).also { clockOffset = it }
            }
            val t = event.timestamp + offset
            val v = event.values
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    measureRate(t)
                    d.onAccel(t, v[0], v[1], v[2])
                }
                Sensor.TYPE_GYROSCOPE -> d.onGyro(t, v[0], v[1], v[2])
                Sensor.TYPE_PRESSURE -> d.onPressure(t, v[0])
            }
        }

        private fun measureRate(t: Long) {
            if (rateSince == 0L) {
                rateSince = t
                return
            }
            rateCount++
            val span = t - rateSince
            if (span >= RATE_WINDOW_NANOS) {
                rateHz = rateCount * 1e9 / span
                rateCount = 0
                rateSince = t
                publish(statusLine())
            }
        }

        private fun statusLine(): String {
            val ceiling = accel.maximumRange / ImpactDetector.G
            val low = if (ceiling <= Config.IMPACT_LOW_CEILING_G) " ⚠ DÜŞÜK TAVAN" else ""
            val slow = if (rateHz in 0.1..99.0) " ⚠ yavaş" else ""
            return String.format(
                Locale.US, "AÇIK · %.0f Hz%s · tavan %.1f G%s · çözünürlük %.4f m/s² · jiro %s · basınç %s",
                rateHz, slow, ceiling, low, accel.resolution,
                if (gyro != null) "var" else "yok", if (baro != null) "var" else "yok",
            )
        }

        private fun sensorInfo() = String.format(
            Locale.US, "ivme=%s; tavan=%.2f m/s2 (%.2f G); cozunurluk=%.5f; hiz=%.0f Hz; jiro=%s; basinc=%s",
            accel.name, accel.maximumRange, accel.maximumRange / ImpactDetector.G, accel.resolution, rateHz,
            gyro?.name ?: "yok", baro?.name ?: "yok",
        )

        /** Sensör iş parçacığından çağrılır; veritabanı yazımı sırayla IO'da yapılır. */
        private fun persist(u: ImpactUpdate) {
            val uuid = ids.getOrPut(u.key) { UUID.randomUUID().toString() }
            val m = u.metrics
            val sinceStartMs = (SystemClock.elapsedRealtimeNanos() - u.key) / 1_000_000
            val entity = ImpactEntity(
                uuid = uuid,
                wallMs = System.currentTimeMillis() - sinceStartMs,
                stage = u.stage.name,
                label = u.label.name,
                triggers = u.triggers,
                peakG = m.peakG,
                durationMs = m.durationMs,
                deltaVKmh = m.deltaVKmh,
                singleSample = m.singleSample,
                saturated = m.saturated,
                freeFallMs = m.freeFallMs,
                maxGyroDps = m.maxGyroDps,
                tiltDeg = m.tiltDeg,
                pressureDeltaHpa = m.pressureDeltaHpa,
                speedBeforeKmh = u.speed.beforeKmh,
                speedAfter10Kmh = u.speed.after10Kmh,
                speedAfter60Kmh = u.speed.after60Kmh,
                speedAfter120Kmh = u.speed.after120Kmh,
                speedLoss = u.speed.significantLoss,
                sensorInfo = sensorInfo(),
                gpsJson = ImpactExport.gpsJson(u.gps, u.peakNanos).toString(),
                sizeBytes = 0,
            )
            val wave = u.accel?.let {
                ImpactWaveEntity(uuid, it.encode(u.peakNanos), u.gyro?.encode(u.peakNanos), u.pressure?.encode(u.peakNanos))
            }
            writes.trySend(entity to wave)
        }
    }

    companion object {
        private const val TAG = "ImpactRecorder"
        private const val TICK_MS = 30_000L
        private const val WAKE_TIMEOUT_MS = 10 * 60_000L
        private const val RATE_WINDOW_NANOS = 5_000_000_000L

        /**
         * Tek yazıcı: güncellemeler üretildiği sırayla yazılır ve servis kapanırken iptal edilmez
         * (motorun scope'u servisle birlikte iptal olur).
         */
        private val writes = Channel<Pair<ImpactEntity, ImpactWaveEntity?>>(Channel.UNLIMITED)
        private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Volatile private var writerStarted = false

        @Synchronized
        fun startWriter(context: Context) {
            if (writerStarted) return
            writerStarted = true
            val app = context.applicationContext
            writerScope.launch {
                for ((entity, wave) in writes) {
                    try {
                        val dao = ImpactDatabase.get(app).impactDao()
                        dao.save(entity, wave)
                        val old = ImpactPrune.toDelete(
                            dao.sizes().map { ImpactPrune.Item(it.uuid, it.wallMs, it.sizeBytes, it.keep) },
                            current = entity.uuid,
                        )
                        if (old.isNotEmpty()) dao.delete(old)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Darbe adayı yazılamadı", t)
                    }
                }
            }
        }
    }

    init {
        startWriter(ctx)
    }
}
