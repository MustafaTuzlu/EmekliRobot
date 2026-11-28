package com.emekli.robot.core

/**
 * "Olası darbe" (darbe adayı) kuralları. Android'den bağımsız saf mantık.
 * Bu aşama yalnız veri toplar; hiçbir etiket kaza tespiti iddiası değildir.
 * Hiçbir ölçüt adayı sildirmez, yalnız etiketi etkiler.
 */
enum class ImpactLabel { TELEFON_DUSMESI_SUPHESI, OLASI_DARBE, BELIRLENEMEDI }

/** OZET: eşik aşıldı. DALGA: +10 sn penceresi yazıldı. TAMAM: GPS devamı da eklendi. */
enum class ImpactStage { OZET, DALGA, TAMAM }

/** Kullanıcının sonradan seçtiği gerçek durum. */
enum class ImpactVerdict(val title: String) {
    KASIS_CUKUR("Kasis / çukur"),
    SERT_FREN("Sert fren"),
    KAPI("Kapı çarpması"),
    TELEFON("Telefona dokunma / düşme"),
    CARPISMA("Gerçek çarpışma"),
    BILINMIYOR("Bilinmiyor"),
}

/** GPS izi noktası. `tNanos` sensörlerle aynı saat (elapsedRealtimeNanos). */
data class GpsPoint(
    val tNanos: Long,
    val lat: Double,
    val lon: Double,
    val speedKmh: Double?,
    val bearingDeg: Double?,
    val accuracyM: Double?,
    val satellites: Int?,
    /** Kalite süzgecinin reddettiği fix: kaybolmasın diye saklanır, hız kararında kullanılmaz. */
    val rejected: Boolean,
)

/** Darbe penceresinden ölçülenler. Bilinmeyen (sensör yok) alanlar null. */
data class ImpactMetrics(
    val peakG: Double,
    /** Tepeyi içeren, 1,5 G üstünde kalınan süre. */
    val durationMs: Double,
    /** İvme VEKTÖRÜNÜN aynı süredeki integralinin büyüklüğü. Telefon dönerse hata artar. */
    val deltaVKmh: Double,
    /** Eşik üstünde yalnız tek örnek: gürültü olabilir. */
    val singleSample: Boolean,
    /** Ham ivme ölçüm tavanına dayandı: gerçek tepe daha yüksek olabilir. */
    val saturated: Boolean,
    /** Darbeden hemen önce ham ivmenin 0,3 G altında kesintisiz kaldığı süre. */
    val freeFallMs: Double,
    val maxGyroDps: Double?,
    /** Yerçekimi yönünün darbe öncesi ve sonrası arasındaki açı. */
    val tiltDeg: Double?,
    /** Darbe çevresinde basıncın önceki ortalamadan en büyük sapması. */
    val pressureDeltaHpa: Double?,
)

data class SpeedProfile(
    val beforeKmh: Double?,
    val after10Kmh: Double?,
    val after60Kmh: Double?,
    val after120Kmh: Double?,
    /** Belirgin hız kaybı; GPS yetersizse null (bilinmiyor). */
    val significantLoss: Boolean?,
)

object ImpactRules {
    private const val S = 1_000_000_000L

    /** `startNanos`: ilk tetikleme (önceki hız), `peakNanos`: en yüksek tepe (sonraki hız). */
    fun speedProfile(track: List<GpsPoint>, startNanos: Long, peakNanos: Long): SpeedProfile {
        val good = track.filter { !it.rejected && it.speedKmh != null }
        val before = good.lastOrNull { it.tNanos <= startNanos && startNanos - it.tNanos <= Config.IMPACT_SPEED_STALE_MS * 1_000_000 }
            ?.speedKmh

        fun at(offsetSec: Long): Double? {
            val target = peakNanos + offsetSec * S
            return good.filter { it.tNanos > peakNanos && kotlin.math.abs(it.tNanos - target) <= 5 * S }
                .minByOrNull { kotlin.math.abs(it.tNanos - target) }?.speedKmh
        }

        val soon = good.filter { it.tNanos > peakNanos + S && it.tNanos <= peakNanos + Config.IMPACT_LOSS_WINDOW_MS * 1_000_000 }
        val loss = if (before == null || soon.isEmpty()) {
            null
        } else {
            val min = soon.minOf { it.speedKmh!! }
            before - min >= Config.IMPACT_LOSS_MIN_DROP_KMH && min <= before * Config.IMPACT_LOSS_MAX_RATIO
        }
        return SpeedProfile(before, at(10), at(60), at(120), loss)
    }

    /**
     * Doymuş sensör, tek örnek, GPS yok/bayat, park halinde darbe ve çelişkili işaretler: BELIRLENEMEDI.
     * Darbe süresi ve Δv bu aşamada yalnız kaydedilir, karara girmez.
     */
    fun label(m: ImpactMetrics, speed: SpeedProfile): ImpactLabel {
        if (m.saturated || m.singleSample) return ImpactLabel.BELIRLENEMEDI
        val freeFall = m.freeFallMs >= Config.IMPACT_FREEFALL_MIN_MS
        val loss = speed.significantLoss ?: return ImpactLabel.BELIRLENEMEDI
        if (freeFall) return if (loss) ImpactLabel.BELIRLENEMEDI else ImpactLabel.TELEFON_DUSMESI_SUPHESI
        val before = speed.beforeKmh ?: return ImpactLabel.BELIRLENEMEDI
        return if (m.peakG >= Config.IMPACT_STRONG_G && before >= Config.IMPACT_MIN_SPEED_KMH && loss) {
            ImpactLabel.OLASI_DARBE
        } else {
            ImpactLabel.BELIRLENEMEDI
        }
    }
}

/** Algılama ne zaman çalışır. Kapalıysa nedeni de döner (durum ekranı için). */
object ImpactPolicy {
    data class Inputs(
        val enabled: Boolean,
        val thermal: ThermalState,
        val plugged: Boolean,
        val batteryPct: Int,
        val moving: Boolean,
        /** Son kabul edilen konumun yaşı; hiç konum yoksa null. */
        val lastFixAgeMs: Long?,
        /** Aracın durmasından beri geçen süre; hiç hareket görülmediyse null. */
        val sinceStopMs: Long?,
    )

    /** null = çalışmalı; aksi halde kapalı olma nedeni. */
    fun offReason(i: Inputs): String? = when {
        !i.enabled -> "ayardan kapalı"
        i.thermal == ThermalState.KRITIK -> "aşırı sıcaklık"
        i.plugged -> null
        i.batteryPct in 0 until Config.LOW_BATTERY_PCT -> "pil düşük"
        // Pil koruması: kapalı otoparkta araç "hareketli" takılı kalabilir; şarj yokken saatlerce açık kalmasın.
        i.moving -> if (i.lastFixAgeMs != null && i.lastFixAgeMs < Config.IMPACT_UNPLUGGED_FIX_MAX_AGE_MS) {
            null
        } else {
            "şarj yok, uzun süredir konum yok"
        }
        i.sinceStopMs != null && i.sinceStopMs < Config.IMPACT_AFTER_STOP_MS -> null
        else -> "şarj yok, araç duruyor"
    }
}

/** Sınır dolunca silinecek en eski kayıtlar. "Sakla" işaretliler ve `current` korunur. */
object ImpactPrune {
    data class Item(val uuid: String, val wallMs: Long, val bytes: Long, val keep: Boolean)

    fun toDelete(
        items: List<Item>,
        current: String?,
        maxEvents: Int = Config.IMPACT_MAX_EVENTS,
        maxBytes: Long = Config.IMPACT_MAX_BYTES,
    ): List<String> {
        var count = items.size
        var bytes = items.sumOf { it.bytes }
        val out = ArrayList<String>()
        for (item in items.filter { !it.keep && it.uuid != current }.sortedBy { it.wallMs }) {
            if (count <= maxEvents && bytes <= maxBytes) break
            out += item.uuid
            count--
            bytes -= item.bytes
        }
        return out
    }
}
