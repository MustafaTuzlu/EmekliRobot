package com.emekli.robot

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.emekli.robot.settings.PinGate

/** Ana ekran. Durum ve bekleyen loglar ekranları sonraki adımlarda eklenecek. */
class MainActivity : AppCompatActivity() {

    private var pinDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.btnSettings).setOnClickListener { askPin() }
    }

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
