package com.emekli.robot.net

import com.emekli.robot.core.UpdateLogic
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Sunucudan sürüm bilgisi (GET surum.php) ve APK indirme (GET indir.php). Sözleşme: docs/emekli-robot-api.md v1.2. */
object UpdateClient {
    class Failure(message: String) : IOException(message)

    /** Sunucudaki sürüm bilgisi; yoksa/bozuksa Failure. */
    fun fetchInfo(baseUrl: String, apiKey: String): UpdateLogic.Info {
        val req = Request.Builder().url(baseUrl.trimEnd('/') + "/surum.php").header("X-Api-Key", apiKey).get().build()
        Http.client.newCall(req).execute().use {
            val body = it.body?.string().orEmpty()
            return when (it.code) {
                200 -> UpdateLogic.parse(body) ?: throw Failure("Sunucudan gelen sürüm bilgisi geçersiz.")
                401 -> throw Failure("API anahtarı hatalı (401). Sunucu ayarlarından düzeltin.")
                404 -> throw Failure("Sunucuda yayınlanmış bir sürüm yok.")
                else -> throw Failure("Sunucu yanıtı: HTTP ${it.code}")
            }
        }
    }

    /**
     * APK'yı `dest`'e indirir; boyut ve SHA-256 uyuşmazsa dosyayı siler ve Failure fırlatır.
     * `holder` ile çağrı dışarıdan iptal edilebilir.
     */
    fun download(
        baseUrl: String, apiKey: String, info: UpdateLogic.Info, dest: File,
        holder: (Call) -> Unit, onProgress: (Long, Long) -> Unit,
    ) {
        dest.parentFile?.mkdirs()
        dest.delete()
        val req = Request.Builder().url(baseUrl.trimEnd('/') + "/indir.php").header("X-Api-Key", apiKey).get().build()
        val call = Http.client.newCall(req)
        holder(call)
        try {
            call.execute().use { resp ->
                if (resp.code != 200) throw Failure("İndirme başarısız: HTTP ${resp.code}")
                val body = resp.body ?: throw Failure("İndirme boş geldi.")
                var total = 0L
                dest.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                            if (total > info.size) throw Failure("Dosya beklenenden büyük; iptal edildi.")
                            onProgress(total, info.size)
                        }
                    }
                }
                if (total != info.size) throw Failure("Dosya eksik indi ($total / ${info.size} bayt).")
            }
            if (UpdateLogic.sha256Hex(dest) != info.sha256) throw Failure("Dosya doğrulaması (SHA-256) başarısız.")
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
    }
}
