package com.lapena.live

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

/** Pantalla inicial: elegir si este celular transmite o maneja el marcador. */
class StartActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_start)
    findViewById<Button>(R.id.btnModeStream).setOnClickListener {
      startActivity(Intent(this, MainActivity::class.java))
    }
    findViewById<Button>(R.id.btnModeControl).setOnClickListener {
      startActivity(Intent(this, ControlActivity::class.java))
    }
    findViewById<Button>(R.id.btnModeMatches).setOnClickListener {
      startActivity(Intent(this, BroadcastsActivity::class.java))
    }
  }
}
