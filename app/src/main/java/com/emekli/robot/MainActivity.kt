package com.emekli.robot

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Geçici iskelet ekranı. Durum / bekleyen loglar / sunucu ayarları ekranları sonra eklenecek. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "EmekliRobot"
            textSize = 24f
            setPadding(48, 96, 48, 48)
        })
    }
}
