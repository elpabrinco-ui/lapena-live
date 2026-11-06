package com.lapena.live

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
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

    val tvVersion = findViewById<TextView>(R.id.tvVersion)
    tvVersion.text = "Versión ${UpdateChecker.installedVersion(this)}"

    val btnUpdate = findViewById<Button>(R.id.btnUpdate)
    btnUpdate.setOnClickListener {
      // Abre la descarga directa del APK en el navegador.
      startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.DOWNLOAD_URL)))
    }
  }

  override fun onResume() {
    super.onResume()
    UpdateChecker.check(this) { latest ->
      runOnUiThread {
        val btnUpdate = findViewById<Button>(R.id.btnUpdate)
        btnUpdate.text = "⬇  NUEVA VERSIÓN ($latest) · DESCARGAR"
        btnUpdate.visibility = View.VISIBLE
      }
    }
  }
}
