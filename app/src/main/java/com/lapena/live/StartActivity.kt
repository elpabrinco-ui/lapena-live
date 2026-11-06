package com.lapena.live

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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
    btnUpdate.setOnClickListener { startUpdate(btnUpdate) }
  }

  private var pendingUpdate = false

  private fun startUpdate(btn: Button) {
    if (AppUpdater.busy) return
    if (!AppUpdater.canInstall(this)) {
      // La primera vez Android pide permiso para que la app instale actualizaciones.
      pendingUpdate = true
      AlertDialog.Builder(this)
        .setTitle("Permitir actualizaciones")
        .setMessage("Android va a abrir un ajuste. Activá \"Permitir de esta fuente\" " +
          "y volvé con la flecha atrás: la descarga empieza sola.")
        .setPositiveButton("OK") { _, _ -> AppUpdater.openInstallPermission(this) }
        .setNegativeButton("Cancelar") { _, _ -> pendingUpdate = false }
        .show()
      return
    }
    btn.isEnabled = false
    btn.text = "DESCARGANDO…"
    AppUpdater.downloadAndInstall(this,
      onProgress = { pct ->
        runOnUiThread { btn.text = if (pct >= 0) "DESCARGANDO… $pct%" else "DESCARGANDO…" }
      },
      onError = { msg ->
        runOnUiThread {
          btn.isEnabled = true
          btn.text = "⬇  REINTENTAR ACTUALIZACIÓN"
          AlertDialog.Builder(this)
            .setTitle("No se pudo descargar")
            .setMessage("$msg\n\n¿Descargar desde el navegador?")
            .setPositiveButton("Abrir navegador") { _, _ ->
              startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.DOWNLOAD_URL)))
            }
            .setNegativeButton("Cancelar", null)
            .show()
        }
      })
    // Cuando termina la descarga, Android muestra "¿Querés actualizar esta app?".
    btn.postDelayed({ if (!AppUpdater.busy) { btn.isEnabled = true } }, 60_000)
  }

  override fun onResume() {
    super.onResume()
    // Volvió del ajuste de permiso: seguir con la actualización.
    if (pendingUpdate && AppUpdater.canInstall(this)) {
      pendingUpdate = false
      startUpdate(findViewById(R.id.btnUpdate))
    }
    UpdateChecker.check(this) { latest ->
      runOnUiThread {
        val btnUpdate = findViewById<Button>(R.id.btnUpdate)
        if (AppUpdater.busy) return@runOnUiThread
        btnUpdate.text = "⬇  NUEVA VERSIÓN ($latest) · ACTUALIZAR"
        btnUpdate.visibility = View.VISIBLE
      }
    }
  }
}
