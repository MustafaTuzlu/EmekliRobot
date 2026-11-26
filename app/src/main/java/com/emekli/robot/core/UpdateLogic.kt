package com.emekli.robot.core

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Uygulama içi güncelleme için saf mantık (testli): sunucu yanıtı, sürüm karşılaştırma, güvenlik kapıları. */
object UpdateLogic {
    data class Info(val versionCode: Int, val versionName: String, val sha256: String, val size: Long, val notes: String)

    private val HEX64 = Regex("^[0-9a-f]{64}$")

    /** `surum.php` yanıtı; eksik/bozuk alan varsa null (güvenli tarafta kal). */
    fun parse(body: String): Info? = try {
        val o = JSONObject(body)
        val code = o.getInt("versionCode")
        val name = o.getString("versionName")
        val sha = o.getString("sha256").lowercase()
        val size = o.getLong("boyut")
        if (code <= 0 || name.isBlank() || name.length > 20 || !HEX64.matches(sha) || size <= 0) null
        else Info(code, name, sha, size, o.optString("notlar", "").take(500))
    } catch (e: Exception) {
        null
    }

    /** Yalnızca daha büyük sürüm kodu güncelleme sayılır (eski sürüme geri düşülmez). */
    fun isNewer(info: Info, currentCode: Int): Boolean = info.versionCode > currentCode

    /** Güncelleme başlatılmamalıysa nedeni, uygunsa null. Kurulum uygulamayı yeniden başlatır. */
    fun blockReason(moving: Boolean, batteryPct: Int?, plugged: Boolean?): String? = when {
        moving -> "Araç hareket halinde. Durunca güncelleyin."
        plugged != true && batteryPct != null && batteryPct < Config.LOW_BATTERY_PCT * 3 ->
            "Pil düşük (%$batteryPct) ve şarjda değil. Şarja takıp deneyin."
        else -> null
    }

    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
