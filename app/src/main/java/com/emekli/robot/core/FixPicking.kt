package com.emekli.robot.core

import kotlin.math.abs
import kotlin.math.max

/**
 * Bir penceredeki (örn. 15 sn) en iyi fix'i tutar; tek eleman saklar, bellek büyümez.
 * Skor: doğruluk + eskilik cezası. İki fix aynı anda kıyaslandığında bu, `doğruluk - k * zaman`
 * kıyasına denktir, yani karar fix geldiği anda verilebilir.
 */
class BestFixWindow<T> {
    private var bestKey = Double.MAX_VALUE
    private var best: T? = null
    private var bestFix: Fix? = null

    fun offer(fix: Fix, item: T) {
        val key = fix.accuracyM - Config.AGE_PENALTY_M_PER_S * fix.elapsedNanos / 1e9
        if (best == null || key < bestKey) {
            bestKey = key
            best = item
            bestFix = fix
        }
    }

    val isEmpty get() = best == null

    /** En iyiyi verir ve pencereyi sıfırlar. */
    fun take(): Pair<T, Fix>? {
        val b = best ?: return null
        val f = bestFix ?: return null
        best = null
        bestFix = null
        bestKey = Double.MAX_VALUE
        return b to f
    }

    fun clear() {
        take()
    }
}

/** Viraj/kavşakta rotayı kesmemek için: yön belirgin değişince pencereyi beklemeden ek nokta. */
class CornerDetector {
    private var lastBearing: Double? = null
    private var lastEmitMs = 0L

    fun reset() {
        lastBearing = null
        lastEmitMs = 0L
    }

    fun onEmitted(bearingDeg: Double?, nowMs: Long) {
        if (bearingDeg != null) lastBearing = bearingDeg
        lastEmitMs = nowMs
    }

    fun isCorner(bearingDeg: Double?, speedKmh: Double?, accM: Double, nowMs: Long): Boolean {
        val last = lastBearing ?: return false
        if (bearingDeg == null || speedKmh == null) return false
        if (speedKmh < Config.CORNER_MIN_SPEED_KMH || accM > Config.CORNER_MAX_ACC_M) return false
        if (nowMs - lastEmitMs < Config.CORNER_MIN_GAP_MS) return false
        return angleDelta(last, bearingDeg) >= Config.CORNER_DEG
    }

    companion object {
        /** İki yön arasındaki en küçük açı farkı, 0..180. */
        fun angleDelta(a: Double, b: Double): Double = abs(((a - b + 540.0) % 360.0) - 180.0)
    }
}

/**
 * Park halindeki konum kestirimi: son birkaç iyi fix'in medyanı. Tek bir gürültülü fix yerine
 * kararlı bir nokta verir, DURUM kayıtları haritada zıplamaz. Halka sınırlıdır (bellek büyümez).
 */
class ParkedPosition {
    class Estimate(val lat: Double, val lon: Double, val accM: Double, val newestElapsedMs: Long, val count: Int)

    private class Entry(val lat: Double, val lon: Double, val accM: Double, val elapsedMs: Long)

    private val ring = ArrayDeque<Entry>()

    fun clear() = ring.clear()

    fun add(lat: Double, lon: Double, accM: Double, elapsedMs: Long) {
        val last = ring.lastOrNull()
        // Önceki noktadan 50 m'den fazla uzaksa araç taşınmıştır: eski örnekler geçersiz.
        if (last != null && Speed.distanceM(last.lat, last.lon, lat, lon) > max(Config.MOVE_DISTANCE_M, last.accM + accM)) {
            ring.clear()
        }
        ring.addLast(Entry(lat, lon, accM, elapsedMs))
        while (ring.size > Config.PARKED_RING_SIZE) ring.removeFirst()
    }

    fun estimate(nowMs: Long): Estimate? {
        while (ring.isNotEmpty() && nowMs - ring.first().elapsedMs > Config.PARKED_MAX_AGE_MS) ring.removeFirst()
        if (ring.size < Config.PARKED_MIN_SAMPLES) return null
        return Estimate(
            lat = median(ring.map { it.lat }),
            lon = median(ring.map { it.lon }),
            accM = median(ring.map { it.accM }),
            newestElapsedMs = ring.last().elapsedMs,
            count = ring.size,
        )
    }

    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }
}
