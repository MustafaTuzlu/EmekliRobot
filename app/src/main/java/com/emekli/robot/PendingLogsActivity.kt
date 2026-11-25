package com.emekli.robot

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.emekli.robot.core.LogTypes
import com.emekli.robot.data.AppDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/** Sunucuya henüz gönderilmemiş kayıtlar, yeniden eskiye. Ekran açıkken canlı güncellenir. */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingLogsActivity : AppCompatActivity() {

    private val typeFilter = MutableStateFlow<String?>(null)
    private val limit = MutableStateFlow(PAGE)
    private val adapter = LogAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pending)

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        val spinner = findViewById<Spinner>(R.id.spinnerType)
        val items = listOf("Tümü") + LogTypes.ALL
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                typeFilter.value = if (pos == 0) null else items[pos]
                limit.value = PAGE
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btnMore).setOnClickListener { limit.value += PAGE }

        val dao = AppDatabase.get(this).logDao()
        val header = findViewById<TextView>(R.id.txtHeader)
        lifecycleScope.launch {
            // Dinleme yalnızca ekran görünürken sürer; arka planda veritabanı izlenmez.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    dao.pendingCount().collect { header.text = "Bekleyen loglar: $it kayıt (yeniden eskiye)" }
                }
                launch {
                    combine(typeFilter, limit) { t, l -> t to l }
                        .flatMapLatest { (t, l) -> dao.newestFirst(t, l) }
                        .collect { adapter.submitList(it) }
                }
            }
        }
    }

    private companion object {
        const val PAGE = 200
    }
}
