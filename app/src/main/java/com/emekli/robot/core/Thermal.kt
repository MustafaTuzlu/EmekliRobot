package com.emekli.robot.core

enum class ThermalState { NORMAL, ISINMIS, KRITIK }

/** Pil sıcaklığına göre durum kararı (histerezisli). Debounce ayrıca StableValue ile yapılır. */
object ThermalGovernor {
    fun target(tempC: Double, current: ThermalState): ThermalState = when (current) {
        ThermalState.NORMAL -> when {
            tempC >= Config.CRIT_ON_C -> ThermalState.KRITIK
            tempC >= Config.HOT_ON_C -> ThermalState.ISINMIS
            else -> ThermalState.NORMAL
        }
        ThermalState.ISINMIS -> when {
            tempC >= Config.CRIT_ON_C -> ThermalState.KRITIK
            tempC <= Config.HOT_OFF_C -> ThermalState.NORMAL
            else -> ThermalState.ISINMIS
        }
        ThermalState.KRITIK -> when {
            tempC >= Config.CRIT_OFF_C -> ThermalState.KRITIK
            tempC <= Config.HOT_OFF_C -> ThermalState.NORMAL
            else -> ThermalState.ISINMIS
        }
    }

    /** Geçişe karşılık gelen log tipi; log gerektirmeyen geçişte null (örn. KRITIK -> ISINMIS). */
    fun logTypeFor(from: ThermalState, to: ThermalState): String? = when {
        to == ThermalState.KRITIK -> LogTypes.CIHAZ_KRITIK_ISINDI
        to == ThermalState.ISINMIS && from == ThermalState.NORMAL -> LogTypes.CIHAZ_ISINDI
        to == ThermalState.NORMAL -> LogTypes.CIHAZ_SOGUDU
        else -> null
    }
}
