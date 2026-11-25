package com.emekli.robot

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.emekli.robot.core.TimeFmt
import com.emekli.robot.data.LogEntity
import java.util.Locale

/** Bekleyen log listesi satırı. DiffUtil ile yalnızca değişen satırlar güncellenir. */
class LogAdapter : ListAdapter<LogEntity, LogAdapter.Holder>(Diff) {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.txtTitle)
        val detail: TextView = view.findViewById(R.id.txtDetail)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val e = getItem(position)
        val warn = if (e.quarantined) "  ⚠ reddedildi" else ""
        holder.title.text = "${e.logType} · ${TimeFmt.short(e.eventEpochMs)}$warn"

        val pos = if (e.lat != null && e.lon != null) {
            String.format(Locale.US, "%.5f, %.5f ±%.0fm", e.lat, e.lon, e.accuracy ?: 0.0)
        } else {
            "konum yok"
        }
        val pil = e.batteryPct?.let { "pil %$it" } ?: "pil ?"
        val sicak = e.batteryTempC?.let { String.format(Locale.US, "%.1f°C", it) } ?: ""
        val sarj = if (e.charging == true) "şarjda" else "şarjda değil"
        val hareket = if (e.moving == true) "hareket" else "durağan"
        holder.detail.text = "$pos\n$pil · $sicak · $sarj · $hareket"
    }

    private object Diff : DiffUtil.ItemCallback<LogEntity>() {
        override fun areItemsTheSame(a: LogEntity, b: LogEntity) = a.id == b.id
        override fun areContentsTheSame(a: LogEntity, b: LogEntity) = a == b
    }
}
