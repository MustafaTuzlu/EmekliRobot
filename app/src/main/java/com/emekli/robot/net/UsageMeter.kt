package com.emekli.robot.net

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import com.emekli.robot.core.UsageBuckets
import com.emekli.robot.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.EventListener
import java.io.IOException

/**
 * Uygulamanın gerçek veri kullanımı (TLS/TCP dahil): Android'in bu uygulamaya (UID) ait sayaçları her sunucu isteği
 * bitince okunur, önceki okumayla farkı saatlik kovaya yazılır. Ek uyanma yok. Siteye gönderilmez.
 */
object UsageMeter {
    private const val PREFS = "usage_meter"
    private const val KEY_TX = "last_tx"
    private const val KEY_RX = "last_rx"

    private lateinit var app: Context
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Cihaz UID sayacını desteklemiyorsa false (ekranda uyarı). */
    fun supported(): Boolean {
        val uid = Process.myUid()
        return TrafficStats.getUidTxBytes(uid) != TrafficStats.UNSUPPORTED.toLong() &&
            TrafficStats.getUidRxBytes(uid) != TrafficStats.UNSUPPORTED.toLong()
    }

    fun init(context: Context) {
        app = context.applicationContext
        // İlk açılışta taban çizgisi: önceki trafik bu uygulamaya yazılmaz.
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains(KEY_TX) && supported()) {
            val uid = Process.myUid()
            p.edit().putLong(KEY_TX, TrafficStats.getUidTxBytes(uid)).putLong(KEY_RX, TrafficStats.getUidRxBytes(uid)).apply()
        }
    }

    /** Her OkHttp çağrısı bitince (başarı ya da hata) sayaçları örnekler. */
    val listener = object : EventListener() {
        override fun callEnd(call: Call) = sample()
        override fun callFailed(call: Call, ioe: IOException) = sample()
    }

    private fun sample() {
        if (!::app.isInitialized) return
        scope.launch {
            mutex.withLock {
                try {
                    if (!supported()) return@withLock
                    val uid = Process.myUid()
                    val tx = TrafficStats.getUidTxBytes(uid)
                    val rx = TrafficStats.getUidRxBytes(uid)
                    val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    val prevTx = if (p.contains(KEY_TX)) p.getLong(KEY_TX, 0L) else null
                    val prevRx = if (p.contains(KEY_RX)) p.getLong(KEY_RX, 0L) else null
                    val dTx = UsageBuckets.delta(prevTx, tx)
                    val dRx = UsageBuckets.delta(prevRx, rx)
                    p.edit().putLong(KEY_TX, tx).putLong(KEY_RX, rx).apply()
                    AppDatabase.get(app).usageDao().add(UsageBuckets.hourStart(System.currentTimeMillis()), dTx, dRx, 1)
                } catch (e: Exception) {
                    // ölçüm hatası uygulamayı etkilemesin
                }
            }
        }
    }
}
