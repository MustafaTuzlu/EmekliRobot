package com.emekli.robot.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Servisin anlık durumu; ekranlar canlı olarak buradan okur (aynı süreç, diske yazılmaz). */
data class RuntimeSnapshot(
    val running: Boolean = false,
    val moving: Boolean = false,
    val gpsMode: String = "KAPALI",
    val thermal: ThermalState = ThermalState.NORMAL,
    val plugged: Boolean? = null,
    val batteryPct: Int? = null,
    val batteryTempC: Double? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracyM: Double? = null,
    /** Son konumun alındığı an (SystemClock.elapsedRealtime, ms). */
    val fixElapsedMs: Long? = null,
    val noFix: Boolean = false,
    /** Hareket sensörü (significant motion) cihazda var mı; null = henüz bilinmiyor. */
    val motionSensor: Boolean? = null,
    val motionTriggers: Int = 0,
    /** Sensörün son tetiklenme anı (SystemClock.elapsedRealtime, ms). */
    val lastMotionElapsedMs: Long? = null,
    /** Kalite filtresinin reddettiği zayıf/sıçrama konum sayısı ve sonuncunun doğruluğu. */
    val rejectedFixes: Int = 0,
    val lastRejectedAccM: Double? = null,
    /** Son fix'in ham hız (m/s) ve yönü; yaş kuralı anlık durum kurulurken uygulanır. */
    val speedMps: Double? = null,
    val bearingDeg: Double? = null,
    /** Olası darbe algılamanın durumu: sensör tavanı ve gerçek örnekleme hızı dahil. Sunucuya gitmez. */
    val impactStatus: String = "?",
)

object RuntimeState {
    val flow = MutableStateFlow(RuntimeSnapshot())

    fun update(block: (RuntimeSnapshot) -> RuntimeSnapshot) = flow.update(block)
}
