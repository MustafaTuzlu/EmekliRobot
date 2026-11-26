package com.emekli.robot

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.emekli.robot.core.UsageBuckets
import com.emekli.robot.core.UsageBuckets.Range
import com.emekli.robot.data.AppDatabase
import com.emekli.robot.net.UsageMeter
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.launch

/** Veri kullanımı: saatlik/günlük/haftalık/aylık/yıllık grafik ve toplamlar. Veri yalnızca telefonda tutulur. */
class UsageActivity : AppCompatActivity() {

    private var range = Range.GUN
    private lateinit var chart: UsageChartView
    private lateinit var txtTotal: TextView
    private lateinit var txtPick: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_usage)
        chart = findViewById(R.id.chart)
        txtTotal = findViewById(R.id.txtTotal)
        txtPick = findViewById(R.id.txtPick)
        if (!UsageMeter.supported()) {
            findViewById<TextView>(R.id.txtUnsupported).apply {
                text = "Bu cihaz uygulama bazlı veri sayacını desteklemiyor; kullanım ölçülemez."
                visibility = android.view.View.VISIBLE
            }
        }

        val group = findViewById<MaterialButtonToggleGroup>(R.id.rangeGroup)
        val ids = mapOf(
            R.id.rSaat to Range.SAAT, R.id.rGun to Range.GUN, R.id.rHafta to Range.HAFTA,
            R.id.rAy to Range.AY, R.id.rYil to Range.YIL,
        )
        group.check(R.id.rGun)
        group.addOnButtonCheckedListener { _, id, checked ->
            if (checked) { range = ids.getValue(id); load() }
        }
        chart.onSelect = { b ->
            txtPick.text = if (b == null) "" else
                "${b.label}: gönderilen ${UsageBuckets.formatBytes(b.tx)} · alınan ${UsageBuckets.formatBytes(b.rx)} · ${b.requests} istek"
        }
        findViewById<Button>(R.id.btnReset).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Veri kullanımını sıfırla")
                .setMessage("Telefondaki tüm saatlik kullanım kayıtları silinir. Bu işlem geri alınamaz.")
                .setNegativeButton("İptal", null)
                .setPositiveButton("Sıfırla") { _, _ ->
                    lifecycleScope.launch {
                        AppDatabase.get(this@UsageActivity).usageDao().clear()
                        load()
                    }
                }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val r = range
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val rows = AppDatabase.get(this@UsageActivity).usageDao().since(UsageBuckets.windowStart(r, now))
                .map { UsageBuckets.Row(it.hourStart, it.txBytes, it.rxBytes, it.requests) }
            if (r != range) return@launch
            val buckets = UsageBuckets.aggregate(rows, r, now)
            val tx = buckets.sumOf { it.tx }; val rx = buckets.sumOf { it.rx }
            val req = buckets.sumOf { it.requests }
            val avg = (tx + rx) / buckets.size
            val unit = when (r) { Range.SAAT -> "saat"; Range.GUN -> "gün"; Range.HAFTA -> "hafta"; Range.AY -> "ay"; Range.YIL -> "yıl" }
            txtTotal.text = "Toplam: ${UsageBuckets.formatBytes(tx + rx)}\n" +
                "Gönderilen: ${UsageBuckets.formatBytes(tx)}   Alınan: ${UsageBuckets.formatBytes(rx)}\n" +
                "İstek sayısı: $req   Ortalama: ${UsageBuckets.formatBytes(avg)} / $unit"
            txtPick.text = "Çubuğa dokunarak ayrıntıyı görün."
            chart.setData(buckets)
        }
    }
}
