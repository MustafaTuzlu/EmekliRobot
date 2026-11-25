package com.emekli.robot.core

/** Tüm eşikler ve süreler tek yerde (CLAUDE.md "Kurallar ve Eşikler" ile birebir). */
object Config {
    const val DEVICE_NAME = "EmektarNote9"

    // Hareket
    const val MOVE_SPEED_KMH = 5.0
    const val START_CONFIRM_SAMPLES = 2          // art arda hızlı örnek sayısı (GPS sıçramasına karşı)
    const val STOP_AFTER_MS = 120_000L           // 2 dk yavaş = ARAC_DURUYOR
    const val MOVING_INTERVAL_MS = 15_000L       // hareketteyken KONUM
    const val HOT_INTERVAL_MS = 60_000L          // ISINMIS modda KONUM
    const val NO_FIX_MS = 60_000L                // 60 sn fix yok = KONUM_ALINAMADI

    // Durağan
    const val HEARTBEAT_MS = 15 * 60_000L        // DURUM
    const val STALE_FIX_MS = 15 * 60_000L        // son konum bundan eskiyse yeni fix dene
    const val FIX_TIMEOUT_MS = 60_000L
    const val PROBE_MS = 60_000L                 // hareket var mı diye kısa GPS penceresi
    const val PROBE_INTERVAL_MS = 5_000L
    const val FALLBACK_PROBE_MS = 3 * 60_000L    // hareket sensörü yoksa

    // Şarj / pil
    const val CHARGE_DEBOUNCE_MS = 15_000L
    const val LOW_BATTERY_PCT = 5

    // Termal (pil sıcaklığı, °C)
    const val THERMAL_DEBOUNCE_MS = 30_000L
    const val HOT_ON_C = 45.0
    const val HOT_OFF_C = 41.0
    const val CRIT_ON_C = 48.0
    const val CRIT_OFF_C = 46.0

    // Kuyruk ve gönderim
    const val QUEUE_CAP = 200_000
    const val BATCH_SIZE = 200
    const val MIN_BATCH_SIZE = 20
    const val SYNC_MIN_GAP_MS = 60_000L
    val BACKOFF_SECONDS = longArrayOf(30, 60, 120, 240, 480, 900)
}
