package com.emekli.robot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.emekli.robot.core.CrashLog
import com.emekli.robot.core.RuntimeSnapshot
import com.emekli.robot.core.RuntimeState
import com.emekli.robot.core.ThermalState
import com.emekli.robot.core.TimeFmt
import com.emekli.robot.data.AppDatabase
import com.emekli.robot.data.LogEntity
import com.emekli.robot.service.TrackerService
import com.emekli.robot.settings.PinGate
import com.emekli.robot.sync.SyncEngine
import com.emekli.robot.sync.SyncSnapshot
import com.emekli.robot.sync.SyncStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.util.Locale

/** Canlı durum ekranı + izin akışı. Servis arka planda ayrı çalışır; bu ekran yalnızca gösterir. */
class MainActivity : AppCompatActivity() {

    private var pinDialog: AlertDialog? = null
    private var askedThisSession = false
    private lateinit var txtStatus: TextView
    private lateinit var txtPerm: TextView
    private lateinit var btnPerm: Button
    private lateinit var btnBattery: Button

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPermissions()
        if (hasFineLocation()) TrackerService.start(this)
        // API 30+ arka plan konumu ayrı istenir: ön plan verildiyse sıradakini iste
        if (hasFineLocation() && needsBackgroundStep()) requestNextPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtStatus = findViewById(R.id.txtStatus)
        txtPerm = findViewById(R.id.txtPerm)
        btnPerm = findViewById(R.id.btnPerm)
        btnBattery = findViewById(R.id.btnBattery)

        btnPerm.setOnClickListener { requestNextPermission() }
        btnBattery.setOnClickListener { requestIgnoreBatteryOptimizations() }
        findViewById<Button>(R.id.btnSyncNow).setOnClickListener {
            SyncEngine.resetBackoff()
            SyncEngine.launch(this, force = true)
            Toast.makeText(this, "Gönderim başlatıldı", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnPending).setOnClickListener {
            startActivity(Intent(this, PendingLogsActivity::class.java))
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { askPin() }

        val dao = AppDatabase.get(this).logDao()
        val ticker = flow { while (true) { emit(SystemClock.elapsedRealtime()); delay(1000) } }
        lifecycleScope.launch {
            // Yalnızca ekran görünürken dinlenir; arka planda arayüz işi yapılmaz.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(RuntimeState.flow, SyncStatus.flow, dao.pendingCount(), dao.lastRecord(), ticker) {
                    rt, sync, pending, last, now -> render(rt, sync, pending, last, now)
                }.collect { txtStatus.text = it }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        refreshPermissions()
        if (hasFineLocation()) {
            TrackerService.start(this)
        } else if (!askedThisSession) {
            askedThisSession = true
            requestNextPermission()
        }
    }

    // ---- durum metni ---------------------------------------------------------------------------

    private fun render(rt: RuntimeSnapshot, sync: SyncSnapshot, pending: Int, last: LogEntity?, now: Long): String {
        val b = StringBuilder()
        b.append("Servis      : ").append(if (rt.running) "ÇALIŞIYOR ✔" else "DURMUŞ ✘").append('\n')
        b.append("Araç        : ").append(if (rt.moving) "HAREKET" else "DURAĞAN").append('\n')
        b.append("GPS         : ").append(rt.gpsMode).append('\n')

        val sensor = when (rt.motionSensor) {
            null -> "?"
            false -> "YOK (yedek yoklama devrede)"
            true -> {
                val last = rt.lastMotionElapsedMs?.let { TimeFmt.ago(now - it) } ?: "hiç"
                "VAR · ${rt.motionTriggers} tetiklenme · son: $last"
            }
        }
        b.append("Hareket sens: ").append(sensor).append('\n')
        b.append("Yoklama     : ").append(
            if (rt.plugged == true) "kontak açık: her 60 sn 20 sn GPS + sensör" else "kontak kapalı: sensör + 15 dk",
        ).append('\n')

        val konum = if (rt.lat != null && rt.lon != null) {
            val age = rt.fixElapsedMs?.let { TimeFmt.ago(now - it) } ?: "?"
            String.format(Locale.US, "%.5f, %.5f ±%.0fm (%s)", rt.lat, rt.lon, rt.accuracyM ?: 0.0, age)
        } else {
            "yok"
        }
        b.append("Son konum   : ").append(konum).append(if (rt.noFix) "  ⚠ alınamıyor" else "").append('\n')

        val pil = rt.batteryPct?.let { "%$it" } ?: "?"
        val sicak = rt.batteryTempC?.let { String.format(Locale.US, "%.1f°C", it) } ?: "?"
        val sarj = when (rt.plugged) { true -> "VAR (kontak açık)"; false -> "YOK (kontak kapalı)"; null -> "?" }
        b.append("Pil         : ").append(pil).append(" · ").append(sicak).append('\n')
        b.append("Şarj        : ").append(sarj).append('\n')
        b.append("Termal      : ").append(
            when (rt.thermal) {
                ThermalState.NORMAL -> "NORMAL"
                ThermalState.ISINMIS -> "ISINDI (yavaşlatıldı, gönderim durdu)"
                ThermalState.KRITIK -> "KRİTİK (GPS ve gönderim durdu)"
            },
        ).append('\n').append('\n')

        b.append("Son kayıt   : ").append(
            last?.let { "${it.logType} · ${TimeFmt.short(it.eventEpochMs)}" } ?: "henüz yok",
        ).append('\n')
        b.append("Bekleyen    : ").append(pending).append(" kayıt\n").append('\n')

        val gonder = when {
            sync.syncing -> "gönderiliyor..."
            sync.lastSuccessEpochMs != null -> TimeFmt.short(sync.lastSuccessEpochMs)
            else -> "henüz yok"
        }
        b.append("Son gönderim: ").append(gonder).append('\n')
        b.append("Durum       : ").append(if (sync.ok == false) "✘ " else if (sync.ok == true) "✔ " else "").append(sync.message).append('\n')
        b.append("Son çökme   : ").append(CrashLog.last(this) ?: "yok")
        return b.toString()
    }

    // ---- izinler -------------------------------------------------------------------------------

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun hasFineLocation() = has(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun hasBackground() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun needsBackgroundStep() = !hasBackground()

    private fun ignoringBattery() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun refreshPermissions() {
        txtPerm.text = buildString {
            append(if (hasFineLocation()) "✔" else "✘").append(" Konum (uygulama açıkken)\n")
            append(if (hasBackground()) "✔" else "✘").append(" Konum: 'Her zaman izin ver'\n")
            append(if (ignoringBattery()) "✔" else "✘").append(" Pil optimizasyonundan muaf")
        }
        btnPerm.isEnabled = !hasFineLocation() || !hasBackground()
        btnPerm.text = when {
            !hasFineLocation() -> "Konum iznini ver"
            !hasBackground() -> "Konum: 'Her zaman izin ver'i seç"
            else -> "Konum izinleri tamam ✔"
        }
        btnBattery.isEnabled = !ignoringBattery()
        btnBattery.text = if (ignoringBattery()) "Pil optimizasyonu kapalı ✔" else "Pil optimizasyonunu kapat"
    }

    private fun requestNextPermission() {
        when {
            !hasFineLocation() -> {
                val perms = mutableListOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
                // Android 10'da arka plan konumu ön planla birlikte istenebilir; 11+ ayrı istenir.
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) perms += Manifest.permission.ACCESS_BACKGROUND_LOCATION
                permLauncher.launch(perms.toTypedArray())
            }
            !hasBackground() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Android 11+: sistem ayarları ekranından 'Her zaman izin ver' seçilir.
                    permLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                } else {
                    permLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                }
            }
            else -> refreshPermissions()
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ---- ayar ekranı şifresi -------------------------------------------------------------------

    private fun askPin() {
        val wait = PinGate.lockedSeconds()
        if (wait > 0) {
            Toast.makeText(this, "Çok fazla yanlış deneme. $wait sn bekleyin.", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Şifre"
        }
        pinDialog = AlertDialog.Builder(this)
            .setTitle("Ayarlar için şifre")
            .setView(input)
            .setNegativeButton("İptal", null)
            .setPositiveButton("Giriş") { _, _ ->
                if (PinGate.check(input.text.toString())) {
                    startActivity(Intent(this, SettingsActivity::class.java))
                } else {
                    val lock = PinGate.lockedSeconds()
                    val msg = if (lock > 0) "Çok fazla yanlış deneme. $lock sn bekleyin." else "Şifre yanlış."
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    override fun onDestroy() {
        pinDialog?.dismiss()
        pinDialog = null
        super.onDestroy()
    }
}
