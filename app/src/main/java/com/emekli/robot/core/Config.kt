package com.emekli.robot.core

/** Tüm eşikler ve süreler tek yerde (CLAUDE.md "Kurallar ve Eşikler" ile birebir). */
object Config {
    const val DEVICE_NAME = "EmektarNote9"

    // Hareket
    const val MOVE_SPEED_KMH = 5.0
    const val START_CONFIRM_SAMPLES = 2          // art arda hızlı örnek sayısı (GPS sıçramasına karşı)
    const val STOP_AFTER_MS = 120_000L           // 2 dk yavaş = ARAC_DURUYOR
    // Yer değiştirme kuralı: hız ne olursa olsun referans noktadan bu kadar uzaklaşırsa hareket (tek örnek, hemen).
    const val MOVE_DISTANCE_M = 50.0
    const val MAX_RELIABLE_ACC_M = 50.0          // doğruluğu bundan kötü fix yer değiştirme için güvenilmez sayılır
    const val MIN_DISPLACED_SPEED_KMH = 2.0      // GPS sapmasını (Doppler hızı ~0) hareketten ayırır

    // Konum kalite filtresi (Wi-Fi/baz istasyonu tahminleri ve sıçramalar kayda girmesin)
    const val MAX_PLAUSIBLE_KMH = 150.0          // iki iyi fix arasında bundan hızlı "gidiş" sıçramadır
    const val JUMP_MIN_DISTANCE_M = 100.0        // daha kısa mesafeler sıçrama sayılmaz
    const val MAX_CONSECUTIVE_JUMPS = 3          // üst üste bu kadar sıçrama: referans yanlış, yeni konumu kabul et
    const val MOVING_INTERVAL_MS = 15_000L       // hareketteyken KONUM
    const val HOT_INTERVAL_MS = 60_000L          // ISINMIS modda KONUM
    const val NO_FIX_MS = 60_000L                // 60 sn fix yok = KONUM_ALINAMADI

    // Konum doğruluğu: GPS hızlı örneklenir, her pencerenin en iyi fix'i yazılır
    const val SAMPLE_INTERVAL_MS = 1_000L        // hareketteyken GPS örnekleme aralığı
    const val HOT_SAMPLE_INTERVAL_MS = 5_000L    // ISINMIS modda örnekleme aralığı
    const val AGE_PENALTY_M_PER_S = 3.0          // pencerede eski fix'e saniye başına bu kadar metre ceza
    const val MIN_SATELLITES = 4                 // uydu sayısı biliniyorsa bundan azı güvenilmez
    const val GOOD_FIX_ACC_M = 20.0              // DURUM için aranan iyi doğruluk
    const val GOOD_FIX_WAIT_MS = 15_000L         // bu süre iyi fix beklenir, sonra 50 m'lik fix de kabul
    // Köşe noktaları: yön bu kadar değişince pencereyi beklemeden ek KONUM
    const val CORNER_DEG = 25.0
    const val CORNER_MIN_GAP_MS = 3_000L
    const val CORNER_MIN_SPEED_KMH = 10.0        // düşük hızda yön güvenilmez
    const val CORNER_MAX_ACC_M = 20.0
    const val MIN_BEARING_SPEED_KMH = 2.0        // bundan yavaşken yön kayda yazılmaz
    // Park halindeki konum: son N iyi fix'in medyanı
    const val PARKED_RING_SIZE = 9
    const val PARKED_MIN_SAMPLES = 3
    const val PARKED_MAX_AGE_MS = 30 * 60_000L
    // hizKmh/yon alanlarını sunucuya gönder (sunucu 937d90c ile hazır)
    const val SEND_MOTION_FIELDS = true

    // Durağan
    const val HEARTBEAT_MS = 15 * 60_000L        // DURUM
    const val STALE_FIX_MS = 15 * 60_000L        // son konum bundan eskiyse yeni fix dene
    const val FIX_TIMEOUT_MS = 60_000L
    // "Hareketli" iken bu süredir kabul edilen konum yoksa (kapalı otopark) kalp atışında yine DURUM yazılır
    const val MOVING_NO_FIX_DURUM_MS = 15 * 60_000L
    const val PROBE_MS = 60_000L                 // hareket var mı diye kısa GPS penceresi
    const val PROBE_INTERVAL_MS = 5_000L
    const val FALLBACK_PROBE_MS = 3 * 60_000L    // hareket sensörü yoksa (şarj yokken)
    const val FALLBACK_PROBE_WINDOW_MS = 30_000L
    // Kontak açıkken (şarj takılı) hareket sensörüne güvenmeden düzenli GPS yoklaması
    const val PLUGGED_PROBE_PERIOD_MS = 60_000L
    const val PLUGGED_PROBE_WINDOW_MS = 20_000L
    // 15 dk'lık DURUM kaydından sonra hareket var mı diye kısa GPS penceresi
    const val HEARTBEAT_PROBE_WINDOW_MS = 30_000L

    // Şarj / pil
    const val CHARGE_DEBOUNCE_MS = 15_000L
    const val LOW_BATTERY_PCT = 5

    // Termal (pil sıcaklığı, °C)
    const val THERMAL_DEBOUNCE_MS = 30_000L
    const val HOT_ON_C = 45.0
    const val HOT_OFF_C = 41.0
    const val CRIT_ON_C = 48.0
    const val CRIT_OFF_C = 46.0

    // Olası darbe (darbe adayı), Aşama 1: yalnız telefonda veri toplar.
    // EŞİKLER DOĞRULANMAMIŞ BAŞLANGIÇ DEĞERLERİDİR; gerçek sürüş verisiyle ayarlanacak.
    const val IMPACT_TRIGGER_G = 2.5             // doğrusal ivme: aday kaydı
    const val IMPACT_STRONG_G = 4.0              // doğrusal ivme: OLASI_DARBE için en az tepe
    const val IMPACT_DURATION_G = 1.5            // süre ve Δv bu düzeyin üstünde ölçülür
    const val IMPACT_FREEFALL_G = 0.3            // HAM ivme büyüklüğü
    const val IMPACT_FREEFALL_MIN_MS = 120.0
    const val IMPACT_MIN_SPEED_KMH = 20.0
    const val IMPACT_LOSS_MIN_DROP_KMH = 15.0    // belirgin hız kaybı: en az bu kadar düşüş
    const val IMPACT_LOSS_MAX_RATIO = 0.5        // ve önceki hızın en çok bu oranına
    const val IMPACT_LOSS_WINDOW_MS = 15_000L
    const val IMPACT_SPEED_STALE_MS = 5_000L     // darbe öncesi hız bundan eskiyse yok sayılır
    const val IMPACT_SATURATION_RATIO = 0.98f    // eksen tavanın bu oranına ulaşırsa "doymuş"
    const val IMPACT_LOW_CEILING_G = 4.05        // tavan bu ya da altındaysa kullanıcı uyarılır
    const val IMPACT_GRAVITY_TAU_S = 0.5f        // yerçekimi süzgeci zaman sabiti
    const val IMPACT_GRAVITY_GATE_G = 0.25f      // süzgeç yalnız |ham-1G| bundan küçükken güncellenir
    const val IMPACT_SAMPLING_US = 5_000         // 200 Hz; daha hızlısı ek izin ister
    const val IMPACT_RING_SAMPLES = 12_288       // 500 Hz'e kadar 20 sn'den uzun pencere
    const val IMPACT_PRE_MS = 10_000L
    const val IMPACT_POST_MS = 10_000L
    const val IMPACT_MERGE_MS = 30_000L
    const val IMPACT_GPS_BEFORE_MS = 30_000L
    const val IMPACT_GPS_AFTER_MS = 120_000L
    const val IMPACT_AFTER_STOP_MS = 3 * 60_000L
    const val IMPACT_UNPLUGGED_FIX_MAX_AGE_MS = 15 * 60_000L
    const val IMPACT_MAX_EVENTS = 50
    const val IMPACT_MAX_BYTES = 20L * 1024 * 1024

    // Kuyruk ve gönderim
    const val QUEUE_CAP = 200_000
    const val BATCH_SIZE = 200
    const val MIN_BATCH_SIZE = 20
    const val SYNC_MIN_GAP_MS = 60_000L
    val BACKOFF_SECONDS = longArrayOf(30, 60, 120, 240, 480, 900)
}
