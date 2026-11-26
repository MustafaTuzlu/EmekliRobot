package com.emekli.robot.sync

import android.content.Context
import android.os.SystemClock
import com.emekli.robot.BuildConfig
import com.emekli.robot.core.CrashLog
import com.emekli.robot.core.RuntimeState
import com.emekli.robot.core.ThermalState
import com.emekli.robot.core.TimeFmt
import com.emekli.robot.data.AppDatabase
import com.emekli.robot.net.Http
import com.emekli.robot.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Sitedeki "Bana son durumu göster" isteği. Sunucuda bekleyen komut var mı diye hafifçe sorar (GET komut.php),
 * varsa tüm anlık bilgiyi bir kez gönderir (POST anlik.php). Bu veri kuyruğa/log tablosuna girmez.
 * Hatalar sessizdir (ekrandaki gönderim durumunu bozmaz); sunucu henüz hazır değilse seyrek denenir.
 */
object CommandPoller {
    const val POLL_MS = 60_000L
    /** İki cevap arası en az bu kadar: ters giden bir komut art arda cevap yağdırmasın. */
    const val MIN_ANSWER_GAP_MS = 30_000L
    private const val ERROR_BACKOFF_MS = 5 * 60_000L

    /**
     * İnternet varken ve termal NORMAL iken yoklanır. Şarjda değilken yalnızca ayarda izin verilmişse
     * (`allowUnplugged`; pil durumu bilinmiyorsa da şarjda sayılmaz).
     */
    fun shouldPoll(online: Boolean, thermal: ThermalState, plugged: Boolean?, allowUnplugged: Boolean = false): Boolean =
        online && thermal == ThermalState.NORMAL && (plugged == true || allowUnplugged)

    /** Elle "Şimdi gönder": bekleme süresini sıfırlayıp hemen sorar. */
    fun resetBackoff() { nextAllowedElapsed = 0L }

    /** Ana ekranda gösterilen son yoklama sonucu (teşhis için). */
    val status = kotlinx.coroutines.flow.MutableStateFlow("henüz yoklanmadı")

    private fun note(msg: String) {
        status.value = TimeFmt.short(System.currentTimeMillis()) + " · " + msg
    }

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val JSON = "application/json; charset=utf-8".toMediaType()

    @Volatile private var nextAllowedElapsed = 0L
    @Volatile private var lastAnsweredElapsed = 0L
    @Volatile private var lastAnsweredId = -1L

    fun launch(context: Context) {
        val app = context.applicationContext
        scope.launch { run(app) }
    }

    suspend fun run(ctx: Context) = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) return@withContext
        try {
            val now = SystemClock.elapsedRealtime()
            if (now < nextAllowedElapsed) return@withContext
            val settings = SettingsStore.get(ctx)
            val base = settings.baseUrl
            val key = settings.apiKey
            if (key.isBlank() || !base.startsWith("https://")) return@withContext
            val deviceName = settings.deviceName
            try {
                val id = fetchCommandId(base, key, deviceName)
                if (id == null) return@withContext
                note("komut bulundu #$id")
                if (id == lastAnsweredId && now - lastAnsweredElapsed < MIN_ANSWER_GAP_MS) return@withContext
                if (now - lastAnsweredElapsed < MIN_ANSWER_GAP_MS) return@withContext
                val pending = AppDatabase.get(ctx).logDao().pendingNow()
                val anlik = AnlikBuilder.build(
                    rt = RuntimeState.flow.value,
                    version = BuildConfig.VERSION_NAME,
                    nowEpochMs = System.currentTimeMillis(),
                    nowElapsedMs = SystemClock.elapsedRealtime(),
                    pending = pending,
                    lastSyncEpochMs = SyncStatus.flow.value.lastSuccessEpochMs,
                    lastCrash = CrashLog.last(ctx),
                )
                if (postAnlik(base, key, deviceName, id, anlik)) {
                    lastAnsweredId = id
                    lastAnsweredElapsed = SystemClock.elapsedRealtime()
                    note("komut #$id cevaplandı ✔")
                } else {
                    note("anlik.php cevabı reddedildi (#$id)")
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                nextAllowedElapsed = SystemClock.elapsedRealtime() + ERROR_BACKOFF_MS
                note("hata: ${e.javaClass.simpleName} (5 dk sonra tekrar)")
            }
        } finally {
            mutex.unlock()
        }
    }

    /** Bekleyen komutun id'si; yoksa null. Beklenmeyen yanıtta (404/5xx vb.) seyrek denemeye geçer. */
    private fun fetchCommandId(base: String, key: String, deviceName: String): Long? {
        val url = (base.trimEnd('/') + "/komut.php").toHttpUrl().newBuilder()
            .addQueryParameter("cihazAdi", deviceName).build()
        val req = Request.Builder().url(url).header("X-Api-Key", key).get().build()
        Http.client.newCall(req).execute().use {
            if (it.code != 200) {
                nextAllowedElapsed = SystemClock.elapsedRealtime() + ERROR_BACKOFF_MS
                note("komut.php HTTP ${it.code} (5 dk sonra tekrar)")
                return null
            }
            val id = parseCommandId(it.body?.string().orEmpty())
            if (id == null) note("komut yok")
            return id
        }
    }

    /** `{"komut":null}` -> null; `{"komut":{"id":12,"tip":"DURUM_ISTE"}}` -> 12. Bilinmeyen tip yok sayılır. */
    fun parseCommandId(body: String): Long? = try {
        val k = JSONObject(body).optJSONObject("komut")
        if (k == null || k.optString("tip") != "DURUM_ISTE" || !k.has("id")) null else k.getLong("id")
    } catch (e: Exception) {
        null
    }

    private fun postAnlik(base: String, key: String, deviceName: String, id: Long, anlik: JSONObject): Boolean {
        val body = JSONObject()
            .put("komutId", id)
            .put("cihazAdi", deviceName)
            .put("gonderimElapsedRealtime", SystemClock.elapsedRealtime())
            .put("anlik", anlik)
        val req = Request.Builder()
            .url(base.trimEnd('/') + "/anlik.php")
            .header("X-Api-Key", key)
            .post(body.toString().toRequestBody(JSON))
            .build()
        Http.client.newCall(req).execute().use { return it.code == 200 }
    }
}
