package com.emekli.robot.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import com.emekli.robot.core.DeviceName
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

    /** Ayar ekranından girilen cihaz adı; boşsa marka-model-kimlik'ten otomatik üretilen ad. */
    var deviceName: String
        get() = DeviceName.sanitize(prefs.getString(KEY_DEVICE, "") ?: "") ?: autoName
        set(value) = prefs.edit().putString(KEY_DEVICE, DeviceName.sanitize(value) ?: "").apply()

    /** "Son durumu göster" yoklaması şarjda değilken de yapılsın mı (internet varken). Varsayılan: evet. */
    var pollWhenUnplugged: Boolean
        get() = prefs.getBoolean(KEY_POLL_UNPLUGGED, true)
        set(value) = prefs.edit().putBoolean(KEY_POLL_UNPLUGGED, value).apply()

    /** Olası darbe algılama (yalnız telefonda veri toplar). Varsayılan: açık. */
    var impactEnabled: Boolean
        get() = prefs.getBoolean(KEY_IMPACT, true)
        set(value) = prefs.edit().putBoolean(KEY_IMPACT, value).apply()

    /** Ad girilmediyse kullanılacak otomatik ad (düzenleme kutusunda ipucu olarak gösterilir). */
    val autoName: String = DeviceName.auto(
        Build.MANUFACTURER, Build.MODEL,
        Settings.Secure.getString(context.applicationContext.contentResolver, Settings.Secure.ANDROID_ID),
    )

    companion object {
        @Volatile
        private var shared: SettingsStore? = null

        /** Şifreli depo oluşturmak pahalı (Keystore); arka plan gönderimi için tek örnek paylaşılır. */
        fun get(context: Context): SettingsStore =
            shared ?: synchronized(this) {
                shared ?: SettingsStore(context.applicationContext).also { shared = it }
            }

        const val DEFAULT_URL = "https://marmaraofisetiket.net/api/emekli/"
        private const val FILE_NAME = "emekli_settings"
        private const val KEY_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_DEVICE = "device_name"
        private const val KEY_POLL_UNPLUGGED = "poll_unplugged"
        private const val KEY_IMPACT = "impact_enabled"
    }
}
