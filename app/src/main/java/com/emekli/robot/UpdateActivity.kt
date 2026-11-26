package com.emekli.robot

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.emekli.robot.core.RuntimeState
import com.emekli.robot.core.UpdateLogic
import com.emekli.robot.core.UsageBuckets
import com.emekli.robot.net.UpdateClient
import com.emekli.robot.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import java.io.File

/**
 * Elle "Güncellemeyi kontrol et": sürüm sorulur, yeniyse indirilir, doğrulanır ve Android'in kurulum ekranı açılır.
 * Android 10'da kurulum her seferinde kullanıcı onayı ister (sessiz kurulum yok).
 */
class UpdateActivity : AppCompatActivity() {

    private lateinit var txtInfo: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnAction: Button
    private lateinit var btnRecheck: Button
    private var call: Call? = null
    private var info: UpdateLogic.Info? = null
    private var busy = false

    private fun apkFile() = File(File(cacheDir, "updates"), "emekli.apk")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update)
        txtInfo = findViewById(R.id.txtInfo)
        progress = findViewById(R.id.progress)
        btnAction = findViewById(R.id.btnAction)
        btnRecheck = findViewById(R.id.btnRecheck)
        findViewById<TextView>(R.id.txtCurrent).text = "Kurulu sürüm: ${BuildConfig.VERSION_NAME}"
        apkFile().delete() // önceki denemeden kalan dosya
        btnRecheck.setOnClickListener { check() }
        btnAction.setOnClickListener { download() }
        check()
    }

    private fun show(text: String, showAction: Boolean = false, actionText: String = "Güncelle") {
        txtInfo.text = text
        btnAction.visibility = if (showAction) android.view.View.VISIBLE else android.view.View.GONE
        btnAction.text = actionText
    }

    private fun check() {
        if (busy) return
        val store = SettingsStore.get(this)
        if (store.apiKey.isBlank()) { show("API anahtarı girilmemiş (Sunucu ayarları)."); return }
        busy = true
        btnRecheck.isEnabled = false
        btnAction.setOnClickListener { download() }
        progress.visibility = android.view.View.GONE
        show("Sürüm kontrol ediliyor...")
        lifecycleScope.launch {
            try {
                val i = withContext(Dispatchers.IO) { UpdateClient.fetchInfo(store.baseUrl, store.apiKey) }
                info = i
                if (UpdateLogic.isNewer(i, BuildConfig.VERSION_CODE)) {
                    val notes = if (i.notes.isBlank()) "" else "\n\n${i.notes}"
                    show("Yeni sürüm var: ${i.versionName} (${UsageBuckets.formatBytes(i.size)})$notes", true)
                } else {
                    show("Uygulama güncel (sunucudaki sürüm: ${i.versionName}).")
                }
            } catch (e: Exception) {
                show(e.message ?: "Sürüm kontrol edilemedi (${e.javaClass.simpleName}).")
            } finally {
                busy = false
                btnRecheck.isEnabled = true
            }
        }
    }

    private fun download() {
        val i = info ?: return
        if (busy) return
        val rt = RuntimeState.flow.value
        UpdateLogic.blockReason(rt.moving, rt.batteryPct, rt.plugged)?.let { show(it, true); return }
        if (!packageManager.canRequestPackageInstalls()) {
            show("Önce bu uygulamaya \"bu kaynaktan uygulama yükleme\" izni verin; açılan ekranda izni açıp geri dönün.", true)
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
            )
            return
        }
        val store = SettingsStore.get(this)
        busy = true
        btnRecheck.isEnabled = false
        progress.visibility = android.view.View.VISIBLE
        progress.progress = 0
        show("İndiriliyor...")
        lifecycleScope.launch {
            try {
                val file = apkFile()
                withContext(Dispatchers.IO) {
                    UpdateClient.download(store.baseUrl, store.apiKey, i, file, { call = it }) { done, total ->
                        val pct = (done * 100 / total).toInt()
                        if (pct != progress.progress) runOnUiThread { progress.progress = pct }
                    }
                }
                verifyPackage(file, i)
                show("İndirildi ve doğrulandı. Android'in kurulum ekranında \"Yükle\"ye basın.", true, "Kurulumu aç")
                btnAction.setOnClickListener { install(file) }
                install(file)
            } catch (e: Exception) {
                if (call?.isCanceled() != true) show(e.message ?: "İndirme başarısız (${e.javaClass.simpleName}).", true)
            } finally {
                progress.visibility = android.view.View.GONE
                busy = false
                btnRecheck.isEnabled = true
            }
        }
    }

    /** İndirilen dosyanın gerçekten bu uygulamanın daha yeni sürümü olduğunu paket bilgisinden doğrular. */
    private fun verifyPackage(file: File, i: UpdateLogic.Info) {
        val pi = packageManager.getPackageArchiveInfo(file.path, 0)
            ?: throw UpdateClient.Failure("İndirilen dosya geçerli bir APK değil.")
        if (pi.packageName != packageName) throw UpdateClient.Failure("İndirilen APK başka bir uygulamaya ait.")
        if (pi.longVersionCode.toInt() != i.versionCode) throw UpdateClient.Failure("APK sürümü sunucu bilgisiyle uyuşmuyor.")
    }

    private fun install(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.update", file)
        startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun onDestroy() {
        call?.cancel()
        super.onDestroy()
    }
}
