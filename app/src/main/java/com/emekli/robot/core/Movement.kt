package com.emekli.robot.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
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

/** Hareket kararı için konum + doğruluk. */
data class Pos(val lat: Double, val lon: Double, val accM: Double)

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

    fun distanceM(a: Pos, b: Pos) = distanceM(a.lat, a.lon, b.lat, b.lon)
}

/**
 * Hareket / durağan kararı. İki bağımsız kural, hangisi önce olursa:
 *
 * Harekete geçiş (durağanken):
 *  1. HIZ: art arda START_CONFIRM_SAMPLES örnek 5 km/h üstünde.
 *  2. YER DEĞİŞTİRME: güvenilir bir fix, referans noktadan >= 50 m (ya da iki fix'in doğruluk toplamı,
 *     hangisi büyükse) uzakta VE hız >= 2 km/h (ya da hız bilinmiyor). Tek örnek yeter, kayıt hemen atılır.
 *     Hız şartı, park halinde GPS'in 50+ m "zıplamasını" hareket saymamak içindir (Doppler hızı ~0 kalır).
 *     Böylece trafikte 4 km/h ile ilerleyen araç da yakalanır.
 *
 * Durma (hareketteyken): hem 2 dk boyunca 5 km/h üstü örnek yok, hem de 2 dk boyunca 50 m'lik ilerleme yok.
 *
 * Referans nokta: ilk fix, ve her ARAC_DURUYOR anındaki konum. Sürekli güncellenmez, yoksa yavaşça
 * ilerleyen araç referansı peşinden sürükler ve asla 50 m'ye ulaşmazdı.
 * Fix gelmeyen süre "bilinmiyor" sayılır; tünelde yanlışlıkla durdu denmez.
 */
class MovementDetector {
    enum class Change { NONE, STARTED, STOPPED }

    var moving: Boolean = false
        private set
    private var fastStreak = 0
    private var lastFastMs = 0L
    private var anchor: Pos? = null
    private var progress: Pos? = null
    private var lastProgressMs = 0L

    /** Hareket kararına karışmadan referans noktayı ilk kez belirler (örn. kalp atışı fix'i). */
    fun seedAnchor(pos: Pos) {
        if (anchor == null && !moving) anchor = pos
    }

    /** Yalnızca hızla karar (konum bilinmiyor). */
    fun onSpeed(speedKmh: Double?, nowMs: Long): Change = onFix(speedKmh, null, nowMs)

    fun onFix(speedKmh: Double?, pos: Pos?, nowMs: Long): Change {
        if (speedKmh == null && pos == null) return Change.NONE
        val fast = speedKmh != null && speedKmh > Config.MOVE_SPEED_KMH

        if (!moving) {
            val ref = anchor
            if (ref == null && pos != null) anchor = pos
            if (speedKmh != null) fastStreak = if (fast) fastStreak + 1 else 0

            val displaced = ref != null && pos != null &&
                pos.accM <= Config.MAX_RELIABLE_ACC_M &&
                Speed.distanceM(ref, pos) >= max(Config.MOVE_DISTANCE_M, ref.accM + pos.accM) &&
                (speedKmh == null || speedKmh >= Config.MIN_DISPLACED_SPEED_KMH)

            if (displaced || fastStreak >= Config.START_CONFIRM_SAMPLES) {
                moving = true
                lastFastMs = nowMs
                lastProgressMs = nowMs
                progress = pos
                fastStreak = 0
                return Change.STARTED
            }
            return Change.NONE
        }

        if (fast) lastFastMs = nowMs
        if (pos != null) {
            val p = progress
            if (p == null || Speed.distanceM(p, pos) >= Config.MOVE_DISTANCE_M) {
                progress = pos
                lastProgressMs = nowMs
            }
        }
        if (nowMs - lastFastMs >= Config.STOP_AFTER_MS && nowMs - lastProgressMs >= Config.STOP_AFTER_MS) {
            moving = false
            anchor = pos ?: progress ?: anchor
            progress = null
            return Change.STOPPED
        }
        return Change.NONE
    }
}
