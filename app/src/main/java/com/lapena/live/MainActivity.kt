package com.lapena.live

import android.Manifest
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker
import com.pedro.common.StreamingStatsReport
import com.pedro.common.Throughput
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.library.rtmp.RtmpStream
import com.pedro.library.util.QueueAwareBitrateAdapter
import java.util.Locale

/**
 * Etapa 1: transmite la cámara del celular a YouTube por RTMP, sin marca de agua.
 * Las siguientes etapas (escudo, marcador, chat) se dibujan encima del video
 * con los filtros de RootEncoder.
 */
class MainActivity : AppCompatActivity(), ConnectChecker {

  companion object {
    private const val YOUTUBE_RTMP = "rtmp://a.rtmp.youtube.com/live2/"
    private const val PREFS = "lapena_live"
    private const val KEY_STREAM = "stream_key"

    // 720p 30fps: buen equilibrio entre calidad y datos móviles.
    private const val WIDTH = 1280
    private const val HEIGHT = 720
    private const val FPS = 30
    private const val VIDEO_BITRATE = 2500 * 1000
    private const val AUDIO_BITRATE = 128 * 1000
    private const val SAMPLE_RATE = 44100
  }

  private lateinit var stream: RtmpStream
  private lateinit var surfaceView: SurfaceView
  private lateinit var etKey: EditText
  private lateinit var tvStatus: TextView
  private lateinit var btnStartStop: Button
  private var prepared = false

  // Baja la calidad sola si la conexión se pone lenta, para que no se corte.
  private val bitrateAdapter = QueueAwareBitrateAdapter(maxBitrate = VIDEO_BITRATE + AUDIO_BITRATE) {
    stream.setVideoBitrateOnFly(it - AUDIO_BITRATE)
  }

  private val permissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
      if (result.values.all { it }) {
        prepareStream()
      } else {
        toast("La app necesita permiso de cámara y micrófono")
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    surfaceView = findViewById(R.id.surfaceView)
    etKey = findViewById(R.id.etKey)
    tvStatus = findViewById(R.id.tvStatus)
    btnStartStop = findViewById(R.id.btnStartStop)
    val btnSwitch = findViewById<Button>(R.id.btnSwitch)

    val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
    etKey.setText(prefs.getString(KEY_STREAM, ""))

    stream = RtmpStream(this, this).apply {
      getGlInterface().autoHandleOrientation = true
      getStreamClient().setReTries(10)
    }

    surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
      override fun surfaceCreated(holder: SurfaceHolder) {
        if (prepared && !stream.isOnPreview) stream.startPreview(surfaceView)
      }

      override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (prepared) stream.getGlInterface().setPreviewResolution(width, height)
      }

      override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (stream.isOnPreview) stream.stopPreview()
      }
    })

    // "PEGAR": toma la clave del portapapeles, sin usar el teclado.
    findViewById<Button>(R.id.btnPaste).setOnClickListener {
      val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
      val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
      if (text.isEmpty()) {
        toast("No hay nada copiado. Copiá la clave en YouTube Studio primero.")
      } else {
        etKey.setText(text)
        saveKey(text)
        hideKeyboard()
        toast("Clave guardada. Tocá EN VIVO ▶")
      }
    }

    // Tecla "Listo" del teclado: guarda la clave y cierra el teclado.
    etKey.setOnEditorActionListener { _, actionId, event ->
      val isDone = actionId == EditorInfo.IME_ACTION_DONE ||
        (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
      if (isDone) {
        saveKey(etKey.text.toString().trim())
        hideKeyboard()
        toast("Clave guardada. Tocá EN VIVO ▶")
      }
      isDone
    }

    btnStartStop.setOnClickListener {
      hideKeyboard()
      if (!prepared) {
        requestPermissionsIfNeeded()
        return@setOnClickListener
      }
      if (!stream.isStreaming) {
        val key = etKey.text.toString().trim()
        if (key.isEmpty()) {
          toast("Pegá la clave de transmisión de YouTube")
          return@setOnClickListener
        }
        saveKey(key)
        stream.startStream(YOUTUBE_RTMP + key)
        btnStartStop.setText(R.string.stop)
        tvStatus.text = "Conectando…"
      } else {
        stopStreaming("Transmisión detenida")
      }
    }

    btnSwitch.setOnClickListener {
      (stream.videoSource as? Camera2Source)?.switchCamera()
    }

    requestPermissionsIfNeeded()
  }

  private fun saveKey(key: String) {
    if (key.isNotEmpty()) getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_STREAM, key).apply()
  }

  private fun hideKeyboard() {
    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
    imm.hideSoftInputFromWindow(etKey.windowToken, 0)
    etKey.clearFocus()
  }

  private fun requestPermissionsIfNeeded() {
    val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    val missing = needed.filter {
      ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }
    if (missing.isEmpty()) prepareStream() else permissionLauncher.launch(missing.toTypedArray())
  }

  private fun prepareStream() {
    if (prepared) return
    prepared = try {
      stream.prepareVideo(WIDTH, HEIGHT, VIDEO_BITRATE, fps = FPS, iFrameInterval = 2) &&
        stream.prepareAudio(SAMPLE_RATE, true, AUDIO_BITRATE)
    } catch (e: IllegalArgumentException) {
      false
    }
    if (!prepared) {
      toast("Este celular no soporta la configuración de video/audio")
      return
    }
    if (surfaceView.holder.surface.isValid && !stream.isOnPreview) {
      stream.startPreview(surfaceView)
    }
  }

  private fun stopStreaming(message: String) {
    if (stream.isStreaming) stream.stopStream()
    runOnUiThread {
      btnStartStop.setText(R.string.start)
      tvStatus.text = message
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    if (stream.isStreaming) stream.stopStream()
    stream.release()
  }

  // --- Eventos de conexión (llegan desde otro hilo) ---

  override fun onConnectionStarted(url: String) {
    bitrateAdapter.reset()
  }

  override fun onConnectionSuccess() {
    runOnUiThread { tvStatus.text = "● EN VIVO" }
  }

  override fun onConnectionFailed(reason: String) {
    if (stream.getStreamClient().reTry(5000, reason, null)) {
      runOnUiThread { tvStatus.text = "Reconectando…" }
    } else {
      stopStreaming("Error: $reason")
      toast("No se pudo conectar: $reason")
    }
  }

  override fun onDisconnect() {
    runOnUiThread { if (!stream.isStreaming) tvStatus.text = "Desconectado" }
  }

  override fun onAuthError() {
    stopStreaming("Clave incorrecta")
    toast("YouTube rechazó la clave de transmisión")
  }

  override fun onAuthSuccess() {}

  override fun onStreamingStats(report: StreamingStatsReport) {
    runOnUiThread {
      bitrateAdapter.onStreamingStats(report)
      if (stream.isStreaming && report.throughput != Throughput.UNKNOWN) {
        tvStatus.text = String.format(
          Locale.getDefault(), "● EN VIVO  %.1f Mb/s", report.smoothedBitrate / 1_000_000f
        )
      }
    }
  }

  private fun toast(msg: String) {
    runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
  }
}
