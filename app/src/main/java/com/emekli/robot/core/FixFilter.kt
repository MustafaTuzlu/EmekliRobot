package com.emekli.robot.core

/**
 * Hareket halindeki KONUM kayıtlarına ve hareket kararına girecek konumların kalite kapısı.
 *
 * GPS fix alamayınca telefon Wi-Fi/baz istasyonu tahmini verir (doğruluk 100-600 m, çoğu zaman aynı
 * koordinat tekrar tekrar gelir). Bunlar haritada yüzlerce metrelik sahte sıçramalar yaratır ve
 * hareket/durma kararını bozar, bu yüzden hareket takibinde kullanılmaz.
 */
object FixFilter {
    enum class Verdict { OK, DUPLICATE, POOR_ACCURACY, IMPLAUSIBLE_JUMP }

    /** @param prevGood son kabul edilen güvenilir fix (yoksa null) */
    fun check(prevGood: Fix?, cur: Fix): Verdict {
        // Aynı fix iki kez iletilir (örn. GPS ayarı değişince son konum yeniden verilir)
        if (prevGood != null && prevGood.elapsedNanos == cur.elapsedNanos) return Verdict.DUPLICATE
        if (!isReliable(cur)) return Verdict.POOR_ACCURACY
        if (prevGood != null) {
            val dtSec = (cur.elapsedNanos - prevGood.elapsedNanos) / 1e9
            if (dtSec > 0) {
                val d = Speed.distanceM(prevGood.lat, prevGood.lon, cur.lat, cur.lon)
                if (d >= Config.JUMP_MIN_DISTANCE_M && d / dtSec * 3.6 > Config.MAX_PLAUSIBLE_KMH) {
                    return Verdict.IMPLAUSIBLE_JUMP
                }
            }
        }
        return Verdict.OK
    }

    fun isReliable(fix: Fix): Boolean = fix.accuracyM <= Config.MAX_RELIABLE_ACC_M
}
