package com.emekli.robot.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Sunucuya gönderilmeyi bekleyen tek bir log kaydı. Tablo = kuyruk: sunucu 200 dönünce satır silinir.
 * `uuid` oluşturulurken bir kez üretilir ve yeniden denemelerde değişmez (idempotency).
 */
@Entity(tableName = "logs", indices = [Index(value = ["uuid"], unique = true)])
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val logType: String,
    val lat: Double?,
    val lon: Double?,
    val accuracy: Double?,
    val batteryPct: Int?,
    val charging: Boolean?,
    val batteryTempC: Double?,
    val moving: Boolean?,
    /** Kaydın oluştuğu andaki duvar saati, saat dilimli ISO 8601 (API: konumZamani). */
    val eventTime: String,
    val eventEpochMs: Long,
    /** Kayıt oluşurken SystemClock.elapsedRealtime() (sunucu saat sapmasını bununla düzeltir). */
    val elapsedRealtimeMs: Long,
    val locationAgeMs: Long?,
    /** Sunucu 422 ile reddettiyse true; tekrar gönderilmez. */
    val quarantined: Boolean = false,
)
