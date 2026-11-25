package com.emekli.robot.core

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

object TimeFmt {
    /** API biçimi: saat dilimli ISO 8601, saniye hassasiyeti. Örn. 2026-11-25T14:03:07+03:00 */
    fun iso(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone)
            .truncatedTo(ChronoUnit.SECONDS)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private val shortFmt = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss")

    /** Ekranda gösterim: 25.11 14:03:07 */
    fun short(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        shortFmt.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    /** "12 sn önce", "3 dk önce", "2 sa önce" */
    fun ago(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "$s sn önce"
            s < 3600 -> "${s / 60} dk önce"
            s < 86400 -> "${s / 3600} sa önce"
            else -> "${s / 86400} gün önce"
        }
    }
}
