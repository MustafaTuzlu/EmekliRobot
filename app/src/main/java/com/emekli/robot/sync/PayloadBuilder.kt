package com.emekli.robot.sync

import com.emekli.robot.core.Config
import com.emekli.robot.data.LogEntity
import org.json.JSONArray
import org.json.JSONObject

/** POST kayitlar.php gövdesi. Alan adları docs/emekli-robot-api.md §4 ile birebir. */
object PayloadBuilder {

    fun build(batch: List<LogEntity>, gonderimElapsedRealtime: Long, gonderimZamani: String?): JSONObject {
        val arr = JSONArray()
        batch.forEach { arr.put(record(it)) }
        return JSONObject().apply {
            put("gonderimElapsedRealtime", gonderimElapsedRealtime)
            if (gonderimZamani != null) put("gonderimZamani", gonderimZamani)
            put("kayitlar", arr)
        }
    }

    fun record(e: LogEntity): JSONObject = JSONObject().apply {
        put("kayit", e.uuid)
        put("cihazAdi", Config.DEVICE_NAME)
        put("logTipi", e.logType)
        // enlem/boylam birlikte dolu ya da birlikte null olmalı
        val hasCoords = e.lat != null && e.lon != null && e.lat.isFinite() && e.lon.isFinite()
        putNullable("enlem", if (hasCoords) e.lat else null)
        putNullable("boylam", if (hasCoords) e.lon else null)
        putNullable("dogruluk", if (hasCoords) e.accuracy else null)
        putNullable("pilYuzdesi", e.batteryPct)
        putNullable("sarjdaMi", e.charging)
        putNullable("pilSicakligi", e.batteryTempC?.let { Math.round(it * 10) / 10.0 })
        putNullable("aracHareketli", e.moving)
        put("konumZamani", e.eventTime)
        put("elapsedRealtime", e.elapsedRealtimeMs)
        putNullable("konumYasiMs", if (hasCoords) e.locationAgeMs else null)
        if (Config.SEND_MOTION_FIELDS) {
            putNullable("hizKmh", if (hasCoords) e.speedKmh?.let { Math.round(it * 10) / 10.0 } else null)
            putNullable("yon", if (hasCoords) e.bearingDeg?.let { Math.round(it * 10) / 10.0 } else null)
        }
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        val v = if (value is Double && !value.isFinite()) null else value
        put(key, v ?: JSONObject.NULL)
    }
}
