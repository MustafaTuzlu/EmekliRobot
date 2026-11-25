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
)

object RuntimeState {
    val flow = MutableStateFlow(RuntimeSnapshot())

    fun update(block: (RuntimeSnapshot) -> RuntimeSnapshot) = flow.update(block)
}
