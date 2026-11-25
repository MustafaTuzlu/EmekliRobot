package com.emekli.robot.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Android Location'dan bağımsız konum örneği (saf mantık testlenebilsin diye). */
data class Fix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Double,
    val elapsedNanos: Long,
    val hasSpeed: Boolean,
    val speedMps: Double,
)

object Speed {
    /** km/s cinsinden hız; güvenilir değilse null. GPS hızı yoksa iki fix arasından hesaplanır. */
    fun kmh(cur: Fix, prev: Fix?): Double? {
        if (cur.hasSpeed) return cur.speedMps * 3.6
        if (prev == null || cur.accuracyM > 30 || prev.accuracyM > 30) return null
        val dtSec = (cur.elapsedNanos - prev.elapsedNanos) / 1e9
        if (dtSec < 1.0 || dtSec > 60.0) return null
        return distanceM(prev.lat, prev.lon, cur.lat, cur.lon) / dtSec * 3.6
    }

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}

/**
 * Hareket / durağan kararı.
 * - Harekete geçiş: art arda START_CONFIRM_SAMPLES örnek 5 km/h üstünde.
 * - Durma: 2 dk boyunca hiç 5 km/h üstü örnek yok. Fix gelmeyen süre "bilinmiyor" sayılır,
 *   tünelde yanlışlıkla durdu denmez (yalnızca hız bilen örnekler değerlendirilir).
 */
class MovementDetector {
    enum class Change { NONE, STARTED, STOPPED }

    var moving: Boolean = false
        private set
    private var fastStreak = 0
    private var lastFastMs = 0L

    fun onSpeed(speedKmh: Double?, nowMs: Long): Change {
        if (speedKmh == null) return Change.NONE
        val fast = speedKmh > Config.MOVE_SPEED_KMH
        if (!moving) {
            fastStreak = if (fast) fastStreak + 1 else 0
            if (fastStreak >= Config.START_CONFIRM_SAMPLES) {
                moving = true
                lastFastMs = nowMs
                fastStreak = 0
                return Change.STARTED
            }
            return Change.NONE
        }
        if (fast) {
            lastFastMs = nowMs
            return Change.NONE
        }
        if (nowMs - lastFastMs >= Config.STOP_AFTER_MS) {
            moving = false
            return Change.STOPPED
        }
        return Change.NONE
    }
}
