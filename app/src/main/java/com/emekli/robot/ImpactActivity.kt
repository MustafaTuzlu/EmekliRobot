package com.emekli.robot

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.emekli.robot.core.ImpactExport
import com.emekli.robot.core.ImpactLabel
import com.emekli.robot.core.ImpactStage
import com.emekli.robot.core.ImpactVerdict
import com.emekli.robot.core.TimeFmt
import com.emekli.robot.data.ImpactDatabase
import com.emekli.robot.data.ImpactEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * "Darbe adayları": telefonda toplanan olası darbe kayıtları. Kaza tespiti değildir.
 * Dışa aktarma yalnız kullanıcı paylaş düğmesine basınca yapılır; hiçbir şey kendiliğinden gönderilmez.
 */
class ImpactActivity : AppCompatActivity() {

    private lateinit var box: LinearLayout
    private lateinit var txtEmpty: TextView
    private lateinit var btnShare: Button
    private val selected = LinkedHashSet<String>()
    private val dao by lazy { ImpactDatabase.get(this).impactDao() }
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_impact)
        box = findViewById(R.id.items)
        txtEmpty = findViewById(R.id.txtEmpty)
        btnShare = findViewById(R.id.btnShare)
        btnShare.setOnClickListener { share() }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { load() }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    private fun load() {
        lifecycleScope.launch {
            val rows = dao.all()
            selected.retainAll(rows.map { it.uuid }.toSet())
            box.removeAllViews()
            txtEmpty.text = if (rows.isEmpty()) "Henüz darbe adayı yok." else "${rows.size} kayıt. Ayrıntı ve etiketleme için satıra dokunun."
            rows.forEach { box.addView(row(it)) }
            updateShare()
        }
    }

    private fun row(e: ImpactEntity) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(10), 0, dp(10))
        addView(CheckBox(context).apply {
            isChecked = e.uuid in selected
            contentDescription = "Dışa aktarmak için seç"
            setOnCheckedChangeListener { _, on ->
                if (on) selected += e.uuid else selected -= e.uuid
                updateShare()
            }
        })
        addView(TextView(context).apply {
            text = summary(e)
            textSize = 13f
            setOnClickListener { edit(e) }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun updateShare() {
        btnShare.isEnabled = selected.isNotEmpty()
        btnShare.text = if (selected.isEmpty()) "Paylaşmak için kayıt seçin" else "Seçilen ${selected.size} kaydı paylaş (JSON)"
    }

    private fun summary(e: ImpactEntity): String {
        fun v(x: Double?, f: String = "%.0f") = x?.let { String.format(Locale.US, f, it) } ?: "?"
        val flags = listOfNotNull(
            "doymuş".takeIf { e.saturated },
            "tek örnek".takeIf { e.singleSample },
            "yarım kayıt".takeIf { e.stage != ImpactStage.TAMAM.name },
            "saklanıyor".takeIf { e.keep },
        ).joinToString()
        val verdict = e.verdict?.let { name -> ImpactVerdict.values().firstOrNull { it.name == name }?.title }
        return buildString {
            append(TimeFmt.short(e.wallMs)).append(" · ").append(labelTitle(e.label)).append('\n')
            append("Tepe ").append(v(e.peakG, "%.1f")).append(" G · süre ").append(v(e.durationMs)).append(" ms · Δv ")
            append(v(e.deltaVKmh, "%.1f")).append(" km/sa\n")
            append("Hız: önce ").append(v(e.speedBeforeKmh)).append(" · +10 sn ").append(v(e.speedAfter10Kmh))
            append(" · +60 sn ").append(v(e.speedAfter60Kmh)).append(" · +120 sn ").append(v(e.speedAfter120Kmh))
            if (flags.isNotEmpty()) append('\n').append(flags)
            if (verdict != null) append("\nGerçek durum: ").append(verdict)
            if (e.note.isNotBlank()) append("\nNot: ").append(e.note)
        }
    }

    private fun labelTitle(name: String) = when (name) {
        ImpactLabel.OLASI_DARBE.name -> "Olası darbe"
        ImpactLabel.TELEFON_DUSMESI_SUPHESI.name -> "Telefon düşmesi şüphesi"
        else -> "Belirlenemedi"
    }

    private fun edit(e: ImpactEntity) {
        val spinner = Spinner(this)
        val options = listOf("Seçilmedi") + ImpactVerdict.values().map { it.title }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        spinner.setSelection(ImpactVerdict.values().indexOfFirst { it.name == e.verdict } + 1)
        val note = EditText(this).apply {
            hint = "Not"
            setText(e.note)
        }
        val keep = CheckBox(this).apply {
            text = "Sakla (sınır dolunca silinmesin)"
            isChecked = e.keep
        }
        val detail = TextView(this).apply {
            textSize = 12f
            text = buildString {
                append(summary(e)).append("\n\n")
                append(String.format(Locale.US, "Tetikleme: %d · serbest düşüş %.0f ms", e.triggers, e.freeFallMs))
                append("\nEn yüksek açısal hız: ").append(e.maxGyroDps?.let { String.format(Locale.US, "%.0f °/sn", it) } ?: "?")
                append("\nYön değişimi: ").append(e.tiltDeg?.let { String.format(Locale.US, "%.0f°", it) } ?: "?")
                append("\nBasınç değişimi: ").append(e.pressureDeltaHpa?.let { String.format(Locale.US, "%.2f hPa", it) } ?: "?")
                append("\n").append(e.sensorInfo)
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(detail)
            addView(spinner)
            addView(note)
            addView(keep)
        }
        dialog = AlertDialog.Builder(this)
            .setTitle("Darbe adayı")
            .setView(android.widget.ScrollView(this).apply { addView(content) })
            .setNegativeButton("İptal", null)
            .setNeutralButton("Sil") { _, _ -> confirmDelete(e) }
            .setPositiveButton("Kaydet") { _, _ ->
                val verdict = ImpactVerdict.values().getOrNull(spinner.selectedItemPosition - 1)?.name
                lifecycleScope.launch {
                    dao.annotate(e.uuid, verdict, note.text.toString().trim(), keep.isChecked)
                    load()
                }
            }
            .show()
    }

    private fun confirmDelete(e: ImpactEntity) {
        dialog = AlertDialog.Builder(this)
            .setTitle("Kaydı sil")
            .setMessage("${TimeFmt.short(e.wallMs)} tarihli darbe adayı telefondan silinir. Geri alınamaz.")
            .setNegativeButton("İptal", null)
            .setPositiveButton("Sil") { _, _ ->
                lifecycleScope.launch {
                    dao.delete(listOf(e.uuid))
                    load()
                }
            }
            .show()
    }

    private fun share() {
        val ids = selected.toList()
        btnShare.isEnabled = false
        lifecycleScope.launch {
            val file = runCatching { withContext(Dispatchers.IO) { export(ids) } }.getOrNull()
            updateShare()
            if (file == null) {
                Toast.makeText(this@ImpactActivity, "Dosya oluşturulamadı", Toast.LENGTH_LONG).show()
                return@launch
            }
            val uri = FileProvider.getUriForFile(this@ImpactActivity, "$packageName.update", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("application/json")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(file.name, uri)
            startActivity(Intent.createChooser(send, "Dosya konum bilgisi içerir"))
        }
    }

    /** Seçilen olayları tek JSON dosyasına yazar (önbellekte; eskileri silinir). */
    private suspend fun export(ids: List<String>): File {
        val dir = File(cacheDir, "impacts")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "darbe-adaylari-${System.currentTimeMillis()}.json")
        file.bufferedWriter().use { out ->
            out.write("{\"uygulama\":\"${BuildConfig.VERSION_NAME}\",\"olaylar\":[")
            var first = true
            for (id in ids) {
                val e = dao.byId(id) ?: continue
                val w = dao.wave(id)
                val o = JSONObject()
                    .put("id", e.uuid)
                    .put("zaman", TimeFmt.iso(e.wallMs))
                    .put("asama", e.stage)
                    .put("etiket", e.label)
                    .put("tetikleme", e.triggers)
                    .put("tepeG", e.peakG)
                    .put("sureMs", e.durationMs)
                    .put("deltaVKmh", e.deltaVKmh)
                    .put("tekOrnek", e.singleSample)
                    .put("doymus", e.saturated)
                    .put("serbestDususMs", e.freeFallMs)
                    .put("enYuksekAcisalHizDps", e.maxGyroDps ?: JSONObject.NULL)
                    .put("yonDegisimiDerece", e.tiltDeg ?: JSONObject.NULL)
                    .put("basincDegisimiHpa", e.pressureDeltaHpa ?: JSONObject.NULL)
                    .put("hizOnceKmh", e.speedBeforeKmh ?: JSONObject.NULL)
                    .put("hiz10Kmh", e.speedAfter10Kmh ?: JSONObject.NULL)
                    .put("hiz60Kmh", e.speedAfter60Kmh ?: JSONObject.NULL)
                    .put("hiz120Kmh", e.speedAfter120Kmh ?: JSONObject.NULL)
                    .put("belirginHizKaybi", e.speedLoss ?: JSONObject.NULL)
                    .put("sensor", e.sensorInfo)
                    .put("gercekDurum", e.verdict ?: JSONObject.NULL)
                    .put("not", e.note)
                    .put("gps", JSONArray(e.gpsJson))
                    .put("ivme", ImpactExport.series(w?.accel, ImpactExport.ACCEL_COLUMNS))
                    .put("jiroskop", ImpactExport.series(w?.gyro, ImpactExport.GYRO_COLUMNS))
                    .put("basinc", ImpactExport.series(w?.pressure, ImpactExport.PRESSURE_COLUMNS))
                if (!first) out.write(",")
                first = false
                out.write(o.toString())
            }
            out.write("]}")
        }
        return file
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
