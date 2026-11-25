package com.emekli.robot.sync

import android.content.Context
import android.os.SystemClock
import com.emekli.robot.core.Config
import com.emekli.robot.core.TimeFmt
import com.emekli.robot.data.AppDatabase
import com.emekli.robot.data.LogEntity
import com.emekli.robot.net.Http
import com.emekli.robot.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Kuyruğu sunucuya boşaltır. Sözleşme: docs/emekli-robot-api.md §4.
 * 200 = partideki tüm kayıtlar sunucuda kalıcı => yalnızca o zaman silinir.
 */
object SyncEngine {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val JSON = "application/json; charset=utf-8".toMediaType()

    @Volatile private var nextAllowedElapsed = 0L
    @Volatile private var backoffIndex = 0
    @Volatile private var batchSize = Config.BATCH_SIZE
    /** 401 alınan anahtar; anahtar değişene kadar (elle gönder hariç) denenmez. */
    @Volatile private var authBlockedFor: String? = null

    /** Ekrandan/servisten ateşle-unut. */
    fun launch(context: Context, force: Boolean) {
        val app = context.applicationContext
        scope.launch { run(app, force) }
    }

    /** Ağ geri geldiğinde beklemeden denemek için. */
    fun resetBackoff() {
        backoffIndex = 0
        nextAllowedElapsed = 0L
    }

    suspend fun run(context: Context, force: Boolean) = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) return@withContext
        try {
            doRun(context.applicationContext, force)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun doRun(ctx: Context, force: Boolean) {
        val settings = SettingsStore.get(ctx)
        val baseUrl = settings.baseUrl
        val apiKey = settings.apiKey
        val deviceName = settings.deviceName
        if (apiKey.isBlank()) return fail("API anahtarı girilmemiş (Sunucu ayarları)", backoff = false)
        if (!baseUrl.startsWith("https://")) return fail("Sunucu adresi https:// ile başlamalı", backoff = false)
        if (!force) {
            if (authBlockedFor == apiKey) return
            if (SystemClock.elapsedRealtime() < nextAllowedElapsed) return
        }

        val dao = AppDatabase.get(ctx).logDao()
        var sent = 0
        var rejected = 0
        SyncStatus.syncing(true)
        try {
            repeat(MAX_BATCHES_PER_RUN) {
                val batch = dao.nextBatch(batchSize)
                if (batch.isEmpty()) {
                    val rejectedNote = if (rejected > 0) " ($rejected kayıt sunucuca reddedildi, listede ⚠)" else ""
                    if (sent > 0) succeed("$sent kayıt gönderildi$rejectedNote")
                    else if (rejected > 0) fail("$rejected kayıt sunucuca reddedildi (422)", backoff = false)
                    return
                }
                val resp = try {
                    post(baseUrl, apiKey, deviceName, batch)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    return fail("Sunucuya ulaşılamıyor (${e.javaClass.simpleName}); kayıtlar kuyrukta bekliyor")
                } catch (e: Exception) {
                    return fail("Gönderim hatası: ${e.javaClass.simpleName}")
                }

                when (val c = resp.code) {
                    200 -> {
                        dao.deleteByIds(batch.map { it.id })
                        sent += batch.size
                        backoffIndex = 0
                        authBlockedFor = null
                    }
                    401 -> {
                        authBlockedFor = apiKey
                        return fail("API anahtarı hatalı (401). Ayarlardan düzeltin", backoff = false)
                    }
                    413 -> {
                        if (batchSize <= Config.MIN_BATCH_SIZE) return fail("Parti çok büyük (413) ama küçültülemiyor")
                        batchSize = (batchSize / 2).coerceAtLeast(Config.MIN_BATCH_SIZE)
                    }
                    422 -> {
                        val bad = invalidRecords(resp.body, batch)
                        if (bad.isEmpty()) return fail("Sunucu 422 verdi ama hatalı kayıt belirtmedi")
                        dao.quarantine(bad)
                        rejected += bad.size
                        // kalan geçerli kayıtlar döngünün sonraki turunda yeniden gönderilir
                    }
                    400, 405, 415 -> return fail("İstek biçimi hatası (HTTP $c): ${errorCode(resp.body)}")
                    else -> return fail("Sunucu yanıtı HTTP $c ${errorCode(resp.body)}".trim())
                }
            }
            if (sent > 0) succeed("$sent kayıt gönderildi (kuyrukta devam ediyor)")
        } finally {
            SyncStatus.syncing(false)
        }
    }

    private class Resp(val code: Int, val body: String)

    private fun post(baseUrl: String, apiKey: String, deviceName: String, batch: List<LogEntity>): Resp {
        // gonderimElapsedRealtime her denemede o an okunur (sunucu saat sapmasını bununla düzeltir)
        val payload = PayloadBuilder.build(
            batch,
            gonderimElapsedRealtime = SystemClock.elapsedRealtime(),
            gonderimZamani = TimeFmt.iso(System.currentTimeMillis()),
            deviceName = deviceName,
        )
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/kayitlar.php")
            .header("X-Api-Key", apiKey)
            .post(payload.toString().toRequestBody(JSON))
            .build()
        Http.client.newCall(request).execute().use {
            return Resp(it.code, it.body?.string().orEmpty())
        }
    }

    /** 422 yanıtındaki hatalı kayıtların UUID'leri (`kayit` null ise `sira` ile partiden bulunur). */
    private fun invalidRecords(body: String, batch: List<LogEntity>): List<String> = try {
        val arr = JSONObject(body).optJSONArray("hatalar")
        val out = LinkedHashSet<String>()
        if (arr != null) for (i in 0 until arr.length()) {
            val h = arr.getJSONObject(i)
            val uuid = if (h.isNull("kayit")) null else h.optString("kayit").lowercase()
            if (uuid != null) {
                out += uuid
            } else {
                batch.getOrNull(h.optInt("sira", -1))?.let { out += it.uuid }
            }
        }
        out.toList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun errorCode(body: String): String =
        try { JSONObject(body).optJSONObject("hata")?.optString("kod").orEmpty() } catch (e: Exception) { "" }

    private fun succeed(message: String) {
        SyncStatus.success(message)
    }

    private fun fail(message: String, backoff: Boolean = true) {
        SyncStatus.failure(message)
        if (backoff) {
            val steps = Config.BACKOFF_SECONDS
            nextAllowedElapsed = SystemClock.elapsedRealtime() + steps[backoffIndex.coerceAtMost(steps.lastIndex)] * 1000
            backoffIndex = (backoffIndex + 1).coerceAtMost(steps.lastIndex)
        }
    }

    private const val MAX_BATCHES_PER_RUN = 500
}
