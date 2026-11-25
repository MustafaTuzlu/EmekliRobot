package com.emekli.robot.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/** Pil durumu. `plugged` = herhangi bir kaynaktan şarj bağlı (kontak açık kabul edilir). */
data class BatterySnapshot(val pct: Int, val tempC: Double, val plugged: Boolean) {
    companion object {
        fun from(intent: Intent): BatterySnapshot {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            return BatterySnapshot(pct, temp, plugged)
        }

        /** Yapışkan (sticky) yayından anlık okuma. */
        fun read(context: Context): BatterySnapshot {
            val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            return if (i != null) from(i) else BatterySnapshot(-1, 0.0, false)
        }
    }
}
