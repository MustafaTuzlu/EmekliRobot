package com.emekli.robot.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Darbe adayı. YALNIZ telefonda tutulur; gönderim kuyruğuna (logs) ve sunucuya girmez. */
@Entity(tableName = "impacts")
data class ImpactEntity(
    @PrimaryKey val uuid: String,
    val wallMs: Long,
    val stage: String,
    val label: String,
    val triggers: Int,
    val peakG: Double,
    val durationMs: Double,
    val deltaVKmh: Double,
    val singleSample: Boolean,
    val saturated: Boolean,
    val freeFallMs: Double,
    val maxGyroDps: Double?,
    val tiltDeg: Double?,
    val pressureDeltaHpa: Double?,
    val speedBeforeKmh: Double?,
    val speedAfter10Kmh: Double?,
    val speedAfter60Kmh: Double?,
    val speedAfter120Kmh: Double?,
    val speedLoss: Boolean?,
    /** Sensör tavanı, çözünürlük, gerçek örnekleme hızı. */
    val sensorInfo: String,
    val gpsJson: String,
    /** Dalga verisi dahil yaklaşık boyut (sınır hesabı için). */
    val sizeBytes: Long,
    val verdict: String? = null,
    val note: String = "",
    val keep: Boolean = false,
)

/** Dalga verisi ayrı tabloda: liste sorguları büyük BLOB okumasın. */
@Entity(tableName = "impact_waves")
data class ImpactWaveEntity(
    @PrimaryKey val uuid: String,
    val accel: ByteArray,
    val gyro: ByteArray?,
    val pressure: ByteArray?,
)

data class ImpactSize(val uuid: String, val wallMs: Long, val sizeBytes: Long, val keep: Boolean)
