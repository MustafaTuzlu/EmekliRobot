package com.emekli.robot.core

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Baştan ayrılmış sabit boyutlu halka tampon: çalışırken bellek büyümez, diske yazılmaz. */
class SampleRing(val capacity: Int, val channels: Int) {
    private val times = LongArray(capacity)
    private val data = FloatArray(capacity * channels)
    private var next = 0
    var size = 0
        private set

    /** `values` dizisinin ilk `channels` elemanı kopyalanır. */
    fun add(tNanos: Long, values: FloatArray) {
        times[next] = tNanos
        System.arraycopy(values, 0, data, next * channels, channels)
        next = (next + 1) % capacity
        if (size < capacity) size++
    }

    fun clear() {
        next = 0
        size = 0
    }

    private fun slot(i: Int) = (next - size + i + capacity) % capacity

    /** i: 0 = en eski. */
    fun time(i: Int) = times[slot(i)]
    fun value(i: Int, channel: Int) = data[slot(i) * channels + channel]

    /** [from, to] aralığındaki örneklerin kopyası. */
    fun slice(from: Long, to: Long): Slice {
        var first = 0
        while (first < size && time(first) < from) first++
        var end = first
        while (end < size && time(end) <= to) end++
        val n = end - first
        val t = LongArray(n)
        val v = FloatArray(n * channels)
        for (i in 0 until n) {
            t[i] = time(first + i)
            for (c in 0 until channels) v[i * channels + c] = value(first + i, c)
        }
        return Slice(t, v, channels)
    }
}

class Slice(val times: LongArray, val values: FloatArray, val channels: Int) {
    val size get() = times.size
    fun value(i: Int, channel: Int) = values[i * channels + channel]

    /** Saklama biçimi: örnek sayısı, sonra her örnek için tepeye göre ms (float) + kanallar (float). */
    fun encode(refNanos: Long): ByteArray {
        val buf = ByteBuffer.allocate(4 + size * 4 * (channels + 1))
        buf.putInt(size)
        for (i in 0 until size) {
            buf.putFloat(((times[i] - refNanos) / 1e6).toFloat())
            for (c in 0 until channels) buf.putFloat(value(i, c))
        }
        return buf.array()
    }

    companion object {
        /** Her satır: [tMs, kanal0, kanal1, ...]. */
        fun decode(bytes: ByteArray, channels: Int): List<FloatArray> {
            if (bytes.size < 4) return emptyList()
            val buf = ByteBuffer.wrap(bytes)
            val n = buf.int
            return List(n) { FloatArray(channels + 1) { buf.float } }
        }
    }
}

/** Bir darbe adayının o anki hali. Dalga verisi yalnız yeni yakalandığında doludur. */
class ImpactUpdate(
    /** Aynı olayın güncellemelerini eşleştiren anahtar (ilk tetikleme anı). */
    val key: Long,
    val peakNanos: Long,
    val stage: ImpactStage,
    val triggers: Int,
    val metrics: ImpactMetrics,
    val speed: SpeedProfile,
    val label: ImpactLabel,
    val gps: List<GpsPoint>,
    /** Kanallar: ham x,y,z + doğrusal x,y,z (m/s²). */
    val accel: Slice?,
    /** Kanallar: x,y,z (rad/s). */
    val gyro: Slice?,
    /** Kanal: hPa. */
    val pressure: Slice?,
)

/**
 * Darbe adayı algılayıcı. Tek iş parçacığından beslenir.
 *
 * SİNYAL SEÇİMİ: Eşikler DOĞRUSAL İVMEYE uygulanır = ham ivmeölçer − kendi yerçekimi süzgecimiz.
 * Android'in TYPE_LINEAR_ACCELERATION sanal sensörü kullanılmaz: üretici füzyonunun darbede ve doygunlukta
 * nasıl davrandığı belgesizdir, ham örneklerle aynı anda gelmeyebilir ve sentetik sinyalle test edilemez.
 * Süzgeç yalnız ham büyüklük 1 G'ye yakınken güncellenir; darbe ve serbest düşüş yerçekimi tahminini bozmaz.
 * Bilinen sapma: telefon darbe sırasında dönerse eski yerçekimi yönü kullanılır; hata en çok 2 G'dir
 * (tam ters dönme), yani dönme tek başına 2,5 G eşiğini aşamaz ama tepe, süre ve Δv değerlerini bozabilir.
 *
 * Serbest düşüş (0,3 G altı) HAM ivme büyüklüğüne uygulanır.
 */
class ImpactDetector(
    /** İvmeölçerin eksen başına ölçüm tavanı (m/s², Sensor.maximumRange). */
    private val maxRange: Float,
    private val onUpdate: (ImpactUpdate) -> Unit,
    capacity: Int = Config.IMPACT_RING_SAMPLES,
) {
    private class Event(val startNanos: Long) {
        var peakNanos = startNanos
        var peakG = 0.0
        var lastTriggerNanos = startNanos
        var triggers = 1
        var wavePending = true
        var metrics = ImpactMetrics(0.0, 0.0, 0.0, true, false, 0.0, null, null, null)
    }

    private val accel = SampleRing(capacity, 6)
    private val gyro = SampleRing(capacity, 3)
    private val pressure = SampleRing(1024, 1)
    private val scratch = FloatArray(6)
    private val track = ArrayDeque<GpsPoint>()

    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private var lastAccelNanos = 0L

    /** Birleştirmeye açık olay. */
    private var open: Event? = null
    /** Birleştirme penceresi kapanmış, GPS devamını bekleyen olaylar. */
    private val awaiting = ArrayList<Event>(4)

    fun onGps(p: GpsPoint) {
        track.addLast(p)
        while (track.isNotEmpty() &&
            (track.size > MAX_TRACK || p.tNanos - track.first().tNanos > TRACK_KEEP_NANOS)
        ) {
            track.removeFirst()
        }
    }

    fun onGyro(t: Long, x: Float, y: Float, z: Float) {
        scratch[0] = x; scratch[1] = y; scratch[2] = z
        gyro.add(t, scratch)
    }

    fun onPressure(t: Long, hPa: Float) {
        scratch[0] = hPa
        pressure.add(t, scratch)
    }

    fun onAccel(t: Long, x: Float, y: Float, z: Float) {
        if (lastAccelNanos == 0L || t - lastAccelNanos > GAP_RESET_NANOS) {
            gx = x; gy = y; gz = z
        } else {
            val rawG = norm(x, y, z) / G
            if (abs(rawG - 1f) < Config.IMPACT_GRAVITY_GATE_G) {
                val dt = (t - lastAccelNanos) / 1e9f
                val a = dt / (Config.IMPACT_GRAVITY_TAU_S + dt)
                gx += a * (x - gx); gy += a * (y - gy); gz += a * (z - gz)
            }
        }
        lastAccelNanos = t
        val lx = x - gx
        val ly = y - gy
        val lz = z - gz
        scratch[0] = x; scratch[1] = y; scratch[2] = z
        scratch[3] = lx; scratch[4] = ly; scratch[5] = lz
        accel.add(t, scratch)

        val linG = (norm(lx, ly, lz) / G).toDouble()
        if (linG >= Config.IMPACT_TRIGGER_G) trigger(t, linG)
        advance(t)
    }

    /** Sensörler kapanmadan önce: bekleyen pencereler eldeki veriyle yazılır, olaylar kapatılır. */
    fun flush() {
        open?.let {
            if (it.wavePending) capture(it)
            emit(it, ImpactStage.TAMAM, null)
        }
        open = null
        awaiting.forEach { emit(it, ImpactStage.TAMAM, null) }
        awaiting.clear()
        accel.clear()
        gyro.clear()
        pressure.clear()
        lastAccelNanos = 0L
    }

    private fun trigger(t: Long, linG: Double) {
        var e = open
        if (e != null && t - e.lastTriggerNanos > MERGE_NANOS) {
            close(e)
            e = null
        }
        if (e == null) {
            e = Event(t)
            open = e
            e.peakG = linG
            e.metrics = measure(t)
            emit(e, ImpactStage.OZET, null) // telefon hemen kapanırsa en az özet kalsın
            return
        }
        if (t - e.lastTriggerNanos > NEW_HIT_NANOS) e.triggers++
        e.lastTriggerNanos = t
        if (linG > e.peakG) {
            e.peakG = linG
            e.peakNanos = t
            e.wavePending = true // en yüksek tepenin penceresi saklanır
        }
    }

    private fun advance(t: Long) {
        open?.let {
            if (it.wavePending && t >= it.peakNanos + POST_NANOS) capture(it)
            if (!it.wavePending && t - it.lastTriggerNanos > MERGE_NANOS) {
                close(it)
                open = null
            }
        }
        var i = 0
        while (i < awaiting.size) {
            val e = awaiting[i]
            if (t >= e.peakNanos + GPS_AFTER_NANOS) {
                emit(e, ImpactStage.TAMAM, null)
                awaiting.removeAt(i)
            } else {
                i++
            }
        }
    }

    private fun close(e: Event) {
        if (e.wavePending) capture(e)
        awaiting.add(e)
    }

    private class Wave(val accel: Slice, val gyro: Slice?, val pressure: Slice?)

    private fun capture(e: Event) {
        e.wavePending = false
        e.metrics = measure(e.peakNanos)
        val from = e.peakNanos - PRE_NANOS
        val to = e.peakNanos + POST_NANOS
        emit(
            e, ImpactStage.DALGA,
            Wave(
                accel.slice(from, to),
                gyro.slice(from, to).takeIf { it.size > 0 },
                pressure.slice(from, to).takeIf { it.size > 0 },
            ),
        )
    }

    private fun emit(e: Event, stage: ImpactStage, wave: Wave?) {
        val speed = ImpactRules.speedProfile(track.toList(), e.startNanos, e.peakNanos)
        val metrics = e.metrics.copy(peakG = max(e.metrics.peakG, e.peakG))
        onUpdate(
            ImpactUpdate(
                key = e.startNanos,
                peakNanos = e.peakNanos,
                stage = stage,
                triggers = e.triggers,
                metrics = metrics,
                speed = speed,
                label = ImpactRules.label(metrics, speed),
                gps = track.filter { it.tNanos >= e.peakNanos - GPS_BEFORE_NANOS && it.tNanos <= e.peakNanos + GPS_AFTER_NANOS },
                accel = wave?.accel,
                gyro = wave?.gyro,
                pressure = wave?.pressure,
            ),
        )
    }

    // ---- ölçümler ------------------------------------------------------------------------------

    private fun linG(i: Int) = norm(accel.value(i, 3), accel.value(i, 4), accel.value(i, 5)) / G
    private fun rawG(i: Int) = norm(accel.value(i, 0), accel.value(i, 1), accel.value(i, 2)) / G

    private fun measure(peakNanos: Long): ImpactMetrics {
        var p = accel.size - 1
        while (p > 0 && accel.time(p) > peakNanos) p--

        // Tepeyi içeren 1,5 G üstü bölüm; 10 ms'den kısa boşluklar aynı darbe sayılır.
        var first = p
        var i = p - 1
        while (i >= 0 && accel.time(first) - accel.time(i) <= BRIDGE_NANOS) {
            if (linG(i) >= Config.IMPACT_DURATION_G) first = i
            i--
        }
        var last = p
        i = p + 1
        while (i < accel.size && accel.time(i) - accel.time(last) <= BRIDGE_NANOS) {
            if (linG(i) >= Config.IMPACT_DURATION_G) last = i
            i++
        }

        var peak = 0.0
        var over = 0
        var saturated = false
        var vx = 0.0
        var vy = 0.0
        var vz = 0.0
        var duration = 0L
        val ceiling = maxRange * Config.IMPACT_SATURATION_RATIO
        for (k in first..last) {
            val g = linG(k).toDouble()
            peak = max(peak, g)
            if (g >= Config.IMPACT_TRIGGER_G) over++
            if (abs(accel.value(k, 0)) >= ceiling || abs(accel.value(k, 1)) >= ceiling || abs(accel.value(k, 2)) >= ceiling) {
                saturated = true
            }
            // Her örnek, kendinden önceki aralığı temsil eder (örnekleme boşluğu şişirmesin diye sınırlı).
            val dtNanos = if (k > 0) min(accel.time(k) - accel.time(k - 1), MAX_DT_NANOS) else 0L
            duration += dtNanos
            val dt = dtNanos / 1e9
            vx += accel.value(k, 3) * dt
            vy += accel.value(k, 4) * dt
            vz += accel.value(k, 5) * dt
        }

        return ImpactMetrics(
            peakG = peak,
            durationMs = duration / 1e6,
            deltaVKmh = sqrt(vx * vx + vy * vy + vz * vz) * 3.6,
            singleSample = over <= 1,
            saturated = saturated,
            freeFallMs = freeFallBefore(first) / 1e6,
            maxGyroDps = maxGyro(peakNanos),
            tiltDeg = tilt(peakNanos),
            pressureDeltaHpa = pressureDelta(peakNanos),
        )
    }

    /** Darbe başlangıcından en çok 500 ms önce biten, ham ivmenin 0,3 G altında olduğu kesintisiz süre (ns). */
    private fun freeFallBefore(first: Int): Long {
        if (first <= 0) return 0
        val start = accel.time(first)
        var end = first - 1
        while (end >= 0 && rawG(end) >= Config.IMPACT_FREEFALL_G) {
            if (start - accel.time(end) > FREEFALL_GAP_NANOS) return 0
            end--
        }
        if (end < 0 || start - accel.time(end) > FREEFALL_GAP_NANOS) return 0
        var begin = end
        while (begin > 0 && rawG(begin - 1) < Config.IMPACT_FREEFALL_G &&
            accel.time(begin) - accel.time(begin - 1) <= MAX_DT_NANOS
        ) {
            begin--
        }
        return accel.time(end) - accel.time(begin)
    }

    private fun maxGyro(peakNanos: Long): Double? {
        val s = gyro.slice(peakNanos - SECOND, peakNanos + SECOND)
        if (s.size == 0) return null
        var m = 0f
        for (i in 0 until s.size) m = max(m, norm(s.value(i, 0), s.value(i, 1), s.value(i, 2)))
        return Math.toDegrees(m.toDouble())
    }

    private fun meanRaw(from: Long, to: Long): DoubleArray? {
        val sum = DoubleArray(3)
        var n = 0
        for (i in 0 until accel.size) {
            val t = accel.time(i)
            if (t < from) continue
            if (t > to) break
            for (c in 0..2) sum[c] += accel.value(i, c).toDouble()
            n++
        }
        if (n == 0) return null
        return DoubleArray(3) { sum[it] / n }
    }

    private fun tilt(peakNanos: Long): Double? {
        val a = meanRaw(peakNanos - 3 * SECOND, peakNanos - SECOND) ?: return null
        val b = meanRaw(peakNanos + 2 * SECOND, peakNanos + 4 * SECOND) ?: return null
        val na = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        val nb = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        if (na < 1.0 || nb < 1.0) return null
        val cos = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (na * nb)
        return Math.toDegrees(acos(cos.coerceIn(-1.0, 1.0)))
    }

    private fun pressureDelta(peakNanos: Long): Double? {
        val before = pressure.slice(peakNanos - 5 * SECOND, peakNanos - SECOND / 2)
        val around = pressure.slice(peakNanos - SECOND / 2, peakNanos + 2 * SECOND)
        if (before.size == 0 || around.size == 0) return null
        val base = (0 until before.size).sumOf { before.value(it, 0).toDouble() } / before.size
        return (0 until around.size).maxOf { abs(around.value(it, 0) - base) }
    }

    private fun norm(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z)

    companion object {
        const val G = 9.80665f
        private const val SECOND = 1_000_000_000L
        private const val MS = 1_000_000L
        private const val PRE_NANOS = Config.IMPACT_PRE_MS * MS
        private const val POST_NANOS = Config.IMPACT_POST_MS * MS
        private const val MERGE_NANOS = Config.IMPACT_MERGE_MS * MS
        private const val GPS_BEFORE_NANOS = Config.IMPACT_GPS_BEFORE_MS * MS
        private const val GPS_AFTER_NANOS = Config.IMPACT_GPS_AFTER_MS * MS
        private const val TRACK_KEEP_NANOS = 200 * SECOND
        private const val MAX_TRACK = 400
        private const val GAP_RESET_NANOS = SECOND
        private const val NEW_HIT_NANOS = 200 * MS
        private const val BRIDGE_NANOS = 10 * MS
        private const val MAX_DT_NANOS = 25 * MS
        private const val FREEFALL_GAP_NANOS = 500 * MS
    }
}
