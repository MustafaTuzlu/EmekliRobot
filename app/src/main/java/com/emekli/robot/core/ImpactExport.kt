package com.emekli.robot.core

import org.json.JSONArray
import org.json.JSONObject

/** Telefonda saklama ve elle paylaşma biçimi. Sunucuya gönderilmez. */
object ImpactExport {
    fun gpsJson(points: List<GpsPoint>, peakNanos: Long): JSONArray {
        val out = JSONArray()
        for (p in points) {
            out.put(
                JSONObject()
                    .put("tMs", (p.tNanos - peakNanos) / 1_000_000)
                    .put("enlem", p.lat)
                    .put("boylam", p.lon)
                    .put("hizKmh", p.speedKmh ?: JSONObject.NULL)
                    .put("yon", p.bearingDeg ?: JSONObject.NULL)
                    .put("dogruluk", p.accuracyM ?: JSONObject.NULL)
                    .put("uydu", p.satellites ?: JSONObject.NULL)
                    .put("reddedildi", p.rejected),
            )
        }
        return out
    }

    /** Sütunlar adlandırılır, her örnek bir dizi: dosya küçük kalır. tMs en yüksek tepeye göredir. */
    fun series(bytes: ByteArray?, columns: List<String>): Any {
        if (bytes == null) return JSONObject.NULL
        val rows = JSONArray()
        for (r in Slice.decode(bytes, columns.size)) {
            val row = JSONArray()
            r.forEach { row.put(Math.round(it * 1000.0) / 1000.0) }
            rows.put(row)
        }
        return JSONObject()
            .put("sutunlar", JSONArray(listOf("tMs") + columns))
            .put("ornekler", rows)
    }

    val ACCEL_COLUMNS = listOf("hamX", "hamY", "hamZ", "dogrusalX", "dogrusalY", "dogrusalZ")
    val GYRO_COLUMNS = listOf("x", "y", "z")
    val PRESSURE_COLUMNS = listOf("hPa")
}
