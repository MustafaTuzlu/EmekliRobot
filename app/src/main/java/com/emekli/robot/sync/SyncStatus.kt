package com.emekli.robot.sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class SyncSnapshot(
    val syncing: Boolean = false,
    val lastSuccessEpochMs: Long? = null,
    val lastAttemptEpochMs: Long? = null,
    /** Son denemenin sonucu, kullanıcıya gösterilir. */
    val message: String = "Henüz gönderim denenmedi",
    val ok: Boolean? = null,
)

/** Gönderim durumu. Son başarı zamanı ve mesaj yeniden başlatmada kaybolmasın diye SharedPreferences'ta da durur. */
object SyncStatus {
    val flow = MutableStateFlow(SyncSnapshot())
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        val p = prefs() ?: return
        flow.value = SyncSnapshot(
            lastSuccessEpochMs = p.getLong(KEY_SUCCESS, 0L).takeIf { it > 0 },
            lastAttemptEpochMs = p.getLong(KEY_ATTEMPT, 0L).takeIf { it > 0 },
            message = p.getString(KEY_MSG, null) ?: "Henüz gönderim denenmedi",
            ok = if (p.contains(KEY_OK)) p.getBoolean(KEY_OK, false) else null,
        )
    }

    fun syncing(on: Boolean) = flow.update { it.copy(syncing = on) }

    fun success(message: String) = record(message, true)

    fun failure(message: String) = record(message, false)

    private fun record(message: String, ok: Boolean) {
        val now = System.currentTimeMillis()
        flow.update {
            it.copy(
                lastAttemptEpochMs = now,
                lastSuccessEpochMs = if (ok) now else it.lastSuccessEpochMs,
                message = message,
                ok = ok,
            )
        }
        prefs()?.edit()
            ?.putLong(KEY_ATTEMPT, now)
            ?.apply { if (ok) putLong(KEY_SUCCESS, now) }
            ?.putString(KEY_MSG, message)
            ?.putBoolean(KEY_OK, ok)
            ?.apply()
    }

    private fun prefs() = appContext?.getSharedPreferences("sync_status", Context.MODE_PRIVATE)

    private const val KEY_SUCCESS = "last_success"
    private const val KEY_ATTEMPT = "last_attempt"
    private const val KEY_MSG = "message"
    private const val KEY_OK = "ok"
}
