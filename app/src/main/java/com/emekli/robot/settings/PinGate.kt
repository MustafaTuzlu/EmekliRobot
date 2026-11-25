package com.emekli.robot.settings

import android.os.SystemClock
import com.emekli.robot.BuildConfig
import java.security.MessageDigest

/**
 * Ayar ekranı şifre kontrolü. Şifrenin kendisi uygulamada yok, yalnızca tuzlu SHA-256 özeti var.
 * Bu bir kolaylık kilididir; APK'yı söken biri aşabilir.
 * Art arda 5 yanlış denemeden sonra 30 sn beklenir (yalnızca bellekte, süreç ölünce sıfırlanır).
 */
object PinGate {
    private const val MAX_ATTEMPTS = 5
    private const val LOCK_MS = 30_000L

    private var failed = 0
    private var lockedUntil = 0L

    /** Kilitliyse kalan saniyeyi, değilse 0 döner. */
    fun lockedSeconds(): Int {
        val left = lockedUntil - SystemClock.elapsedRealtime()
        return if (left > 0) ((left + 999) / 1000).toInt() else 0
    }

    fun check(pin: String): Boolean {
        if (lockedSeconds() > 0) return false
        val ok = MessageDigest.isEqual(
            hash(pin).toByteArray(),
            BuildConfig.SETTINGS_PIN_HASH.toByteArray(),
        )
        if (ok) {
            failed = 0
        } else if (++failed >= MAX_ATTEMPTS) {
            failed = 0
            lockedUntil = SystemClock.elapsedRealtime() + LOCK_MS
        }
        return ok
    }

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest((BuildConfig.SETTINGS_PIN_SALT + pin).toByteArray())
            .joinToString("") { "%02x".format(it) }
}
