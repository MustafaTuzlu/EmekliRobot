package com.emekli.robot.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Sunucu adresi ve API anahtarı. Değerler şifreli SharedPreferences'ta durur, koda yazılmaz. */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val app = context.applicationContext
        val masterKey = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            app,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var baseUrl: String
        get() = prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(value) = prefs.edit().putString(KEY_URL, value.trim()).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    companion object {
        const val DEFAULT_URL = "https://marmaraofisetiket.net/api/emekli/"
        private const val FILE_NAME = "emekli_settings"
        private const val KEY_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
    }
}
