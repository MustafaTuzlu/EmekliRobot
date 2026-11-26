package com.emekli.robot.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Veri kullanımı ekranı için saf mantık (saatlik kovalar -> saat/gün/hafta/ay/yıl toplamları). Testlidir. */
object UsageBuckets {
    enum class Range(val count: Int) { SAAT(24), GUN(30), HAFTA(12), AY(12), YIL(5) }

    /** Saatlik veritabanı satırı. */
    data class Row(val hourStart: Long, val tx: Long, val rx: Long, val requests: Int)

    data class Bucket(val start: Long, val label: String, val tx: Long, val rx: Long, val requests: Int) {
        val total get() = tx + rx
    }

    private fun zdt(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone)

    /** Saatlik kova başlangıcı (yerel saat dilimine göre tam saat). */
    fun hourStart(ms: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        zdt(ms, zone).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli()

    /** `ms` anını içeren kovanın başlangıcı. Hafta PAZARTESİ başlar. */
    fun bucketStart(range: Range, ms: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val t = zdt(ms, zone)
        val s: ZonedDateTime = when (range) {
            Range.SAAT -> t.truncatedTo(ChronoUnit.HOURS)
            Range.GUN -> t.truncatedTo(ChronoUnit.DAYS)
            Range.HAFTA -> t.truncatedTo(ChronoUnit.DAYS).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            Range.AY -> t.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1)
            Range.YIL -> t.truncatedTo(ChronoUnit.DAYS).withDayOfYear(1)
        }
        return s.toInstant().toEpochMilli()
    }

    private fun next(range: Range, start: Long, zone: ZoneId): Long {
        val t = zdt(start, zone)
        return when (range) {
            Range.SAAT -> t.plusHours(1)
            Range.GUN -> t.plusDays(1)
            Range.HAFTA -> t.plusWeeks(1)
            Range.AY -> t.plusMonths(1)
            Range.YIL -> t.plusYears(1)
        }.toInstant().toEpochMilli()
    }

    private fun previous(range: Range, start: Long, zone: ZoneId): Long {
        val t = zdt(start, zone)
        return when (range) {
            Range.SAAT -> t.minusHours(1)
            Range.GUN -> t.minusDays(1)
            Range.HAFTA -> t.minusWeeks(1)
            Range.AY -> t.minusMonths(1)
            Range.YIL -> t.minusYears(1)
        }.toInstant().toEpochMilli()
    }

    /** Seçili aralığın ilk kovasının başlangıcı (veritabanı sorgusu için). */
    fun windowStart(range: Range, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        var s = bucketStart(range, now, zone)
        repeat(range.count - 1) { s = previous(range, s, zone) }
        return s
    }

    fun label(range: Range, start: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = zdt(start, zone)
        return when (range) {
            Range.SAAT -> String.format(Locale.US, "%02d:00", t.hour)
            Range.GUN, Range.HAFTA -> String.format(Locale.US, "%02d.%02d", t.dayOfMonth, t.monthValue)
            Range.AY -> String.format(Locale.US, "%02d.%02d", t.monthValue, t.year % 100)
            Range.YIL -> t.year.toString()
        }
    }

    /** Boş kovalar dahil, eskiden yeniye `range.count` kova. */
    fun aggregate(rows: List<Row>, range: Range, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Bucket> {
        val first = windowStart(range, now, zone)
        val tx = HashMap<Long, Long>(); val rx = HashMap<Long, Long>(); val rq = HashMap<Long, Int>()
        for (r in rows) {
            if (r.hourStart < first) continue
            val k = bucketStart(range, r.hourStart, zone)
            tx[k] = (tx[k] ?: 0L) + r.tx
            rx[k] = (rx[k] ?: 0L) + r.rx
            rq[k] = (rq[k] ?: 0) + r.requests
        }
        val out = ArrayList<Bucket>(range.count)
        var s = first
        repeat(range.count) {
            out += Bucket(s, label(range, s, zone), tx[s] ?: 0L, rx[s] ?: 0L, rq[s] ?: 0)
            s = next(range, s, zone)
        }
        return out
    }

    /** 1024 tabanlı: B, KB, MB, GB. */
    fun formatBytes(b: Long): String {
        val v = b.toDouble()
        return when {
            b < 1024 -> "$b B"
            v < 1024.0 * 1024 -> String.format(Locale.US, "%.1f KB", v / 1024)
            v < 1024.0 * 1024 * 1024 -> String.format(Locale.US, "%.2f MB", v / (1024.0 * 1024))
            else -> String.format(Locale.US, "%.3f GB", v / (1024.0 * 1024 * 1024))
        }
    }

    /**
     * Sayaç farkı. Sayaç açılıştan beri birikir; yeniden başlatmada küçülür: o zaman yeni değerin tamamı sayılır.
     * `prev` null (ilk ölçüm) ise fark 0 (geçmişe ait trafik bu uygulamaya yazılmaz).
     */
    fun delta(prev: Long?, cur: Long): Long = when {
        prev == null -> 0L
        cur >= prev -> cur - prev
        else -> cur
    }
}
