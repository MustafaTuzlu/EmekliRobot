package com.emekli.robot.core

import android.content.Context

/** Beklenmeyen çökmeyi diske yazar; ekranda "Son çökme" olarak gösterilir. Servisi sistem/alarm/watchdog yeniden başlatır. */
object CrashLog {
    private const val PREFS = "crash"
    private const val KEY = "last"

    fun install(app: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val text = "${TimeFmt.short(System.currentTimeMillis())} · ${error.javaClass.simpleName}: " +
                    "${error.message.orEmpty().take(120)} @ ${error.stackTrace.firstOrNull()}"
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, text).commit()
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun last(app: Context): String? =
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
}
