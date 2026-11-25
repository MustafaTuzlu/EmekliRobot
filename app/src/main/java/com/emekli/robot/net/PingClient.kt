package com.emekli.robot.net

import android.os.SystemClock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Sunucu "Bağlantıyı test et" isteği (GET ping.php). Sözleşme: docs/emekli-robot-api.md §3.
 * Yönlendirmeler (3xx) takip edilmez ve başarı sayılmaz.
 */
object PingClient {

    data class Result(val ok: Boolean, val message: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** Sonuç `onResult` ile OkHttp iş parçacığında döner. Dönen Call iptal edilebilir. */
    fun ping(baseUrl: String, apiKey: String, onResult: (Result) -> Unit): Call? {
        val base = baseUrl.trim()
        if (!base.startsWith("https://")) {
            onResult(Result(false, "Adres https:// ile başlamalı (http 301 verir ve veri kaybolur)."))
            return null
        }
        if (apiKey.isBlank()) {
            onResult(Result(false, "API anahtarı girilmemiş."))
            return null
        }
        val request = try {
            Request.Builder()
                .url(base.trimEnd('/') + "/ping.php")
                .header("X-Api-Key", apiKey.trim())
                .get()
                .build()
        } catch (e: IllegalArgumentException) {
            onResult(Result(false, "Adres geçersiz."))
            return null
        }

        val startedAt = SystemClock.elapsedRealtime()
        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                onResult(Result(false, "Sunucuya ulaşılamıyor (internet yok ya da zaman aşımı): ${e.javaClass.simpleName}"))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val ms = SystemClock.elapsedRealtime() - startedAt
                    val body = it.body?.string().orEmpty()
                    onResult(describe(it.code, body, ms))
                }
            }
        })
        return call
    }

    private fun describe(code: Int, body: String, ms: Long): Result {
        if (code in 300..399) {
            return Result(false, "HTTP $code yönlendirme (${ms} ms). Adres yanlış, https:// ve www'suz kullanın.")
        }
        val json = try { JSONObject(body) } catch (e: Exception) { null }
        val errCode = json?.optJSONObject("hata")?.optString("kod").orEmpty()

        return when {
            code == 200 && json?.optBoolean("ok") == true -> {
                val db = json.optString("veritabani")
                val serverMs = json.optLong("sunucuUnixMs", 0L)
                val drift = if (serverMs > 0) {
                    val diffSec = (System.currentTimeMillis() - serverMs) / 1000
                    if (abs(diffSec) <= 2) "Telefon saati sunucuyla uyumlu."
                    else "Telefon saati sunucudan ${abs(diffSec)} sn ${if (diffSec > 0) "ileride" else "geride"}."
                } else ""
                Result(db == "ok", "Bağlantı başarılı (HTTP 200, ${ms} ms). Veritabanı: $db. $drift".trim())
            }
            code == 401 -> Result(false, "API anahtarı yanlış (HTTP 401).")
            code == 405 -> Result(false, "Yöntem desteklenmiyor (HTTP 405).")
            code == 503 && errCode == "veritabani_hatasi" ->
                Result(false, "Sunucu var ama veritabanına ulaşamıyor (HTTP 503).")
            code == 503 -> Result(false, "Sunucuda anahtar tanımlı değil (HTTP 503 $errCode).")
            else -> Result(false, "Beklenmeyen yanıt: HTTP $code $errCode (${ms} ms).")
        }
    }
}
