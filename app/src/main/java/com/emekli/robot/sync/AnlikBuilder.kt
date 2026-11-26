package com.emekli.robot.sync

import com.emekli.robot.core.Config
import com.emekli.robot.core.RuntimeSnapshot
import com.emekli.robot.core.ThermalState
import com.emekli.robot.core.TimeFmt
import org.json.JSONObject

/**
 * "Bana son durumu göster" cevabı (POST anlik.php `anlik` nesnesi). Kuyruğa/Room'a YAZILMAZ, log değildir.
 * Saf mantık, testlidir.
 */
object AnlikBuilder {
    /** Hız/yön yalnızca taze fix'te anlamlıdır (kayıtlardaki kuralla aynı). */
    private const val FRESH_MS = 5_000L

    fun build(
        rt: RuntimeSnapshot,
        version: String,
        nowEpochMs: Long,
        nowElapsedMs: Long,
        pending: Int,
        lastSyncEpochMs: Long?,
        lastCrash: String?,
    ): JSONObject {
        val o = JSONObject()
        o.put("surum", version)
        o.put("anlikZamani", TimeFmt.iso(nowEpochMs))

        val hasPos = rt.lat != null && rt.lon != null
        val age = if (hasPos) rt.fixElapsedMs?.let { (nowElapsedMs - it).coerceAtLeast(0) } else null
        o.put("enlem", if (hasPos) rt.lat else JSONObject.NULL)
        o.put("boylam", if (hasPos) rt.lon else JSONObject.NULL)
        o.put("dogruluk", if (hasPos) rt.accuracyM ?: JSONObject.NULL else JSONObject.NULL)
        o.put("konumYasiMs", age ?: JSONObject.NULL)

        val fresh = age != null && age <= FRESH_MS
        val speedKmh = if (fresh) rt.speedMps?.times(3.6) else null
        o.put("hizKmh", speedKmh?.let { Math.round(it * 10) / 10.0 } ?: JSONObject.NULL)
        val bearing = if (speedKmh != null && speedKmh >= Config.MIN_BEARING_SPEED_KMH) rt.bearingDeg else null
        o.put("yon", bearing?.let { Math.round(it * 10) / 10.0 } ?: JSONObject.NULL)

        o.put("pilYuzdesi", rt.batteryPct ?: JSONObject.NULL)
        o.put("sarjdaMi", rt.plugged ?: JSONObject.NULL)
        o.put("pilSicakligi", rt.batteryTempC ?: JSONObject.NULL)
        o.put("aracHareketli", rt.moving)
        o.put("termal", when (rt.thermal) {
            ThermalState.NORMAL -> "NORMAL"
            ThermalState.ISINMIS -> "ISINMIS"
            ThermalState.KRITIK -> "KRITIK"
        })
        o.put("gpsModu", rt.gpsMode)
        o.put("bekleyenKayit", pending)
        o.put("sonGonderimZamani", lastSyncEpochMs?.let { TimeFmt.iso(it) } ?: JSONObject.NULL)
        o.put("hareketSensoru", rt.motionSensor ?: JSONObject.NULL)
        o.put("sensorTetiklenme", rt.motionTriggers)
        o.put("reddedilenKonum", rt.rejectedFixes)
        o.put("sonCokme", lastCrash?.take(300) ?: JSONObject.NULL)
        return o
    }
}
