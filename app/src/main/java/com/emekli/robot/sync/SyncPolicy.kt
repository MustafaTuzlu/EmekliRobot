package com.emekli.robot.sync

import com.emekli.robot.core.Config
import com.emekli.robot.core.ThermalState

/** Bir gönderim isteğinin hemen mi, sonra mı, hiç mi işleneceğine karar verir (saf mantık, testli). */
object SyncPolicy {
    enum class Decision { SKIP, NOW, LATER }

    /**
     * @param urgent sıcaklık uyarı kaydı (CIHAZ_ISINDI / CIHAZ_KRITIK_ISINDI): cihaz ısınmış olsa da
     *   ve son gönderimden 60 sn geçmemiş olsa da bir kez hemen gönderilir.
     * @param sinceLastSyncMs son gönderim denemesinden bu yana geçen süre
     */
    fun decide(thermal: ThermalState, online: Boolean, urgent: Boolean, sinceLastSyncMs: Long): Decision = when {
        !online -> Decision.SKIP
        urgent -> Decision.NOW
        thermal != ThermalState.NORMAL -> Decision.SKIP
        sinceLastSyncMs < Config.SYNC_MIN_GAP_MS -> Decision.LATER
        else -> Decision.NOW
    }

    /** Hemen gönderilmeyen istek için kalan bekleme süresi. */
    fun delayMs(sinceLastSyncMs: Long): Long = (Config.SYNC_MIN_GAP_MS - sinceLastSyncMs).coerceAtLeast(0)

    /** Bu log tipleri sıcaklık uyarısıdır ve gönderim durmuş olsa da hemen gönderilir. */
    fun isUrgentType(logType: String): Boolean =
        logType == com.emekli.robot.core.LogTypes.CIHAZ_ISINDI ||
            logType == com.emekli.robot.core.LogTypes.CIHAZ_KRITIK_ISINDI
}
