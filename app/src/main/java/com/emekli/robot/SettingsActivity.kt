package com.emekli.robot

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.emekli.robot.net.PingClient
import com.emekli.robot.settings.SettingsStore
import okhttp3.Call

/** Sunucu adresi ve API anahtarı düzenleme + bağlantı testi. Şifre kapısı MainActivity'de. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var store: SettingsStore
    private lateinit var editUrl: EditText
    private lateinit var editKey: EditText
    private lateinit var editDevice: EditText
    private lateinit var txtResult: TextView
    private lateinit var btnTest: Button
    private var pending: Call? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        store = SettingsStore(this)
        editUrl = findViewById(R.id.editUrl)
        editKey = findViewById(R.id.editKey)
        editDevice = findViewById(R.id.editDevice)
        txtResult = findViewById(R.id.txtResult)
        btnTest = findViewById(R.id.btnTest)

        editUrl.setText(store.baseUrl)
        editKey.setText(store.apiKey)
        editDevice.setText(store.deviceName)
        findViewById<TextView>(R.id.txtDeviceHint).text =
            "Yalnızca harf, rakam, _ ve -. Boş bırakırsanız otomatik ad kullanılır: ${store.autoName}"

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            save()
            txtResult.text = "Kaydedildi."
        }
        btnTest.setOnClickListener { test() }
    }

    private fun save() {
        store.baseUrl = editUrl.text.toString()
        store.apiKey = editKey.text.toString()
        store.deviceName = editDevice.text.toString()
        editDevice.setText(store.deviceName) // temizlenmiş hali göster
    }

    private fun test() {
        save()
        pending?.cancel()
        btnTest.isEnabled = false
        txtResult.text = "Test ediliyor..."
        pending = PingClient.ping(store.baseUrl, store.apiKey) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                btnTest.isEnabled = true
                txtResult.text = (if (result.ok) "✔ " else "✘ ") + result.message
            }
        }
    }

    override fun onDestroy() {
        pending?.cancel()
        pending = null
        super.onDestroy()
    }
}
