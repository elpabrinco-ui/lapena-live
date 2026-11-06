package com.lapena.live

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.common.ConnectChecker
import com.pedro.common.StreamingStatsReport
import com.pedro.common.Throughput
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.library.rtmp.RtmpStream
import com.pedro.library.util.QueueAwareBitrateAdapter
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Modo transmitir: cámara → YouTube por RTMP, con el marcador dibujado encima.
 * El marcador lo maneja otro celular (modo control) usando el código de partido.
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

    // Posición del marcador en el video, en % (arriba a la izquierda)
    private const val BOARD_X = 2f
    private const val BOARD_Y = 3f

    private const val END_CARD_SECONDS = 30

    // Chat: cada cuánto consultar (cuida el cupo diario de la API) y cuánto tiempo se ve cada mensaje
    private const val CHAT_POLL_MIN_MS = 8000L
    private const val CHAT_SHOW_MS = 20_000L
    private const val CHAT_MAX_VISIBLE = 3
  }

  private lateinit var stream: RtmpStream
  private lateinit var surfaceView: SurfaceView
  private lateinit var etKey: EditText
  private lateinit var tvStatus: TextView
  private lateinit var tvCode: TextView
  private lateinit var btnStartStop: Button
  private lateinit var saverCover: View
  private lateinit var btnPaste: Button
  private var prepared = false

  // Pantalla de cierre
  private var endCardFilter: ImageObjectFilterRender? = null
  private var endCardBitmap: android.graphics.Bitmap? = null
  private var endCardRemaining = 0

  // Partido de YouTube elegido y chat
  private var selection: Selection? = null
  private lateinit var tvMatch: TextView
  private lateinit var btnMatch: Button
  private var chatFilter: ImageObjectFilterRender? = null
  private var chatPoller: ChatPoller? = null
  private data class ChatItem(val msg: YouTubeApi.ChatMessage, val at: Long, val n: Long)
  private val chatItems = ArrayList<ChatItem>()
  private var chatCounter = 0L
  private var lastChatKey = 

  // --- Marcador ---
  private val handler = Handler(Looper.getMainLooper())
  private lateinit var renderer: ScoreboardRenderer
  private var boardFilter: ImageObjectFilterRender? = null
  @Volatile private var matchState: MatchState? = null
  private var lastDrawnKey = ""
  private var subscriber: ScoreSync.Subscriber? = null
  private lateinit var matchCode: String
  @Volatile private var syncOnline = false

  private val boardTick = object : Runnable {
    override fun run() {
      redrawBoard(force = false)
      redrawChat(force = false)
      handler.postDelayed(this, 250)
    }
  }

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
    tvCode = findViewById(R.id.tvCode)
    btnStartStop = findViewById(R.id.btnStartStop)
    saverCover = findViewById(R.id.saverCover)
    val btnSwitch = findViewById<Button>(R.id.btnSwitch)
    tvMatch = findViewById(R.id.tvMatch)
    btnMatch = findViewById(R.id.btnMatch)
    btnMatch.setOnClickListener {
      startActivity(Intent(this, BroadcastsActivity::class.java)
        .putExtra(BroadcastsActivity.EXTRA_FROM_STREAM, true))
    }
    tvMatch.setOnLongClickListener {
      if (!stream.isStreaming) {
        Selection.clear(this)
        loadSelection()
        toast("Partido quitado. Se usa la clave manual.")
      }
      true
    }

    renderer = ScoreboardRenderer(this)
    matchCode = ScoreSync.savedCode(this, createIfMissing = true)
    updateCodeLabel()

    val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
    etKey.setText(prefs.getString(KEY_STREAM, ""))

    stream = RtmpStream(this, this).apply {
      getGlInterface().autoHandleOrientation = true
      getStreamClient().setReTries(10)
    }

    surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
      override fun surfaceCreated(holder: SurfaceHolder) {
        if (prepared && !stream.isOnPreview && saverCover.visibility != View.VISIBLE) startPreviewWithBoard()
      }

      override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (prepared) stream.getGlInterface().setPreviewResolution(width, height)
      }

      override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (stream.isOnPreview) stream.stopPreview()
      }
    })

    // "PEGAR": toma la clave del portapapeles, sin usar el teclado.
    btnPaste = findViewById(R.id.btnPaste)
    btnPaste.setOnClickListener {
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
        updateLiveUi()
        startChat()
      } else if (endCardFilter != null) {
        // Ya está mostrando la pantalla de cierre: cortar ya.
        stopStreaming("Transmisión finalizada")
      } else {
        AlertDialog.Builder(this)
          .setTitle("Terminar transmisión")
          .setItems(arrayOf(
            "Cerrar con pantalla final ($END_CARD_SECONDS s)",
            "Cortar ahora",
          )) { _, which ->
            if (which == 0) startEndCard() else stopStreaming("Transmisión detenida")
          }
          .setNegativeButton("Seguir en vivo", null)
          .show()
      }
    }

    btnSwitch.setOnClickListener {
      (stream.videoSource as? Camera2Source)?.switchCamera()
    }

    // Modo ahorro: apaga la vista previa y oscurece la pantalla (menos calor y batería).
    findViewById<Button>(R.id.btnSaver).setOnClickListener {
      if (!stream.isStreaming) {
        toast("El modo ahorro se usa mientras estás EN VIVO")
      } else {
        enterSaver()
      }
    }
    saverCover.setOnClickListener { exitSaver() }

    // Tocar el código permite cambiarlo por uno nuevo.
    tvCode.setOnLongClickListener {
      ScoreSync.saveCode(this, "")
      matchCode = ScoreSync.savedCode(this, createIfMissing = true)
      restartSubscriber()
      toast("Código nuevo: $matchCode")
      true
    }

    restartSubscriber()
    requestPermissionsIfNeeded()
  }

  override fun onResume() {
    super.onResume()
    if (!stream.isStreaming) loadSelection()
    handler.post(boardTick)
  }

  override fun onPause() {
    super.onPause()
    handler.removeCallbacks(boardTick)
  }

  // ---------------- Marcador ----------------

  private fun restartSubscriber() {
    subscriber?.stop()
    updateCodeLabel()
    subscriber = ScoreSync.Subscriber(
      code = matchCode,
      onState = { newState ->
        val current = matchState
        // Ignora mensajes viejos (por ejemplo, al reconectar)
        if (current == null || newState.sentAt >= current.sentAt) {
          matchState = newState
          handler.post { redrawBoard(force = true) }
        }
      },
      onConnection = { online ->
        syncOnline = online
        handler.post { updateCodeLabel() }
      },
    ).also { it.start() }
  }

  private fun updateCodeLabel() {
    val dot = if (syncOnline) "🟢" else "⚪"
    tvCode.text = "$dot Código: $matchCode"
  }

  /** Inicia la vista previa y vuelve a poner los gráficos (la librería borra los filtros al parar). */
  private fun startPreviewWithBoard() {
    stream.startPreview(surfaceView)
    attachOverlays()
  }

  /** Marcador, chat y (si está activa) pantalla final, en ese orden: lo último queda arriba. */
  private fun attachOverlays() {
    val gl = stream.getGlInterface()
    gl.clearFilters()
    val board = ImageObjectFilterRender().also { boardFilter = it }
    val chat = ImageObjectFilterRender().also { it.alpha = 0f; chatFilter = it }
    gl.addFilter(board)
    gl.addFilter(chat)
    endCardBitmap?.let { bmp ->
      val card = newEndCardFilter(bmp)
      endCardFilter = card
      gl.addFilter(card)
    }
    redrawBoard(force = true)
    redrawChat(force = true)
  }

  private fun redrawBoard(force: Boolean) {
    val filter = boardFilter ?: return
    val state = matchState
    if (state == null || !state.visible) {
      if (force || lastDrawnKey != "hidden") {
        filter.alpha = 0f
        lastDrawnKey = "hidden"
      }
      return
    }
    val clock = state.clockNow()
    // Solo redibuja si algo cambió (el reloj cambia una vez por segundo)
    val key = state.toJson() + "|" + (clock / 1000)
    if (!force && key == lastDrawnKey) return
    lastDrawnKey = key

    val bmp = renderer.render(state, clock)
    filter.setImage(bmp)
    // El ancho cambia según los nombres: escalar según el tamaño real de la imagen.
    filter.setScale(
      bmp.width / ScoreboardRenderer.S * 100f / WIDTH,
      ScoreboardRenderer.HEIGHT * 100f / HEIGHT,
    )
    filter.setPosition(BOARD_X, BOARD_Y)
    filter.alpha = 1f
  }

  // ---------------- Pantalla de cierre ----------------

  private fun startEndCard() {
    exitSaver()
    val bmp = renderer.renderEndCard(matchState, WIDTH, HEIGHT)
    endCardBitmap = bmp
    val filter = newEndCardFilter(bmp)
    endCardFilter = filter
    stream.getGlInterface().addFilter(filter)   // va encima del video y del marcador
    (stream.audioSource as? MicrophoneSource)?.mute()
    endCardRemaining = END_CARD_SECONDS
    handler.post(endCardTick)
  }

  private fun newEndCardFilter(bmp: android.graphics.Bitmap) = ImageObjectFilterRender().apply {
    setImage(bmp.copy(bmp.config ?: android.graphics.Bitmap.Config.ARGB_8888, false))
    setScale(100f, 100f)
    setPosition(0f, 0f)
  }

  private val endCardTick = object : Runnable {
    override fun run() {
      if (endCardFilter == null) return
      if (endCardRemaining <= 0) {
        stopStreaming("Transmisión finalizada")
        finishOnYouTube()
        return
      }
      tvStatus.text = "Pantalla final · se corta en $endCardRemaining s"
      btnStartStop.text = "CORTAR YA ■"
      endCardRemaining--
      handler.postDelayed(this, 1000)
    }
  }

  private fun removeEndCard() {
    handler.removeCallbacks(endCardTick)
    endCardFilter?.let { stream.getGlInterface().removeFilter(it) }
    endCardFilter = null
    endCardBitmap = null
    (stream.audioSource as? MicrophoneSource)?.unMute()
  }

  private fun updateLiveUi() {
    // En vivo: ocultar la clave para que no tape el marcador en la vista previa.
    val live = stream.isStreaming
    etKey.visibility = if (live) View.GONE else View.VISIBLE
    btnPaste.visibility = if (live) View.GONE else View.VISIBLE
    btnMatch.visibility = if (live) View.GONE else View.VISIBLE
    tvMatch.visibility = if (live || selection == null) View.GONE else View.VISIBLE
  }

  // ---------------- Partido de YouTube ----------------

  private fun loadSelection() {
    selection = Selection.load(this)
    val sel = selection
    if (sel != null) {
      etKey.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_STREAM, ""))
      val chat = if (sel.liveChatId != null) " · 💬 chat" else ""
      tvMatch.text = "📅 ${sel.title}$chat   (mantené apretado para quitar)"
    }
    updateLiveUi()
  }

  /** Al terminar con la pantalla final: cierra el vivo en YouTube (como "Finalizar transmisión"). */
  private fun finishOnYouTube() {
    val sel = selection ?: return
    thread {
      val token = YouTubeAuth.getTokenSilently(this)
      val msg = if (token == null) {
        "Finalizá el vivo en YouTube Studio (falta iniciar sesión)"
      } else try {
        YouTubeApi.complete(token, sel.broadcastId)
        "Vivo finalizado en YouTube ✓"
      } catch (e: YouTubeException) {
        if (e.reason == "redundantTransition") "Vivo finalizado en YouTube ✓" else "No se pudo finalizar en YouTube: ${e.message}"
      } catch (e: Exception) {
        "No se pudo finalizar en YouTube: ${e.message}"
      }
      Selection.clear(this)
      runOnUiThread {
        toast(msg)
        loadSelection()
      }
    }
  }

  // ---------------- Chat ----------------

  private fun startChat() {
    stopChat()
    val chatId = selection?.liveChatId ?: return
    chatPoller = ChatPoller(chatId).also { it.start() }
  }

  private fun stopChat() {
    chatPoller?.running = false
    chatPoller = null
    chatItems.clear()
    redrawChat(force = true)
  }

  private inner class ChatPoller(private val chatId: String) {
    @Volatile var running = true

    fun start() = thread(name = "chat-poller", isDaemon = true) {
      var page: String? = null
      var first = true
      var warnedLogin = false
      while (running) {
        var wait = CHAT_POLL_MIN_MS
        try {
          val token = YouTubeAuth.getTokenSilently(this@MainActivity)
          if (token == null) {
            if (!warnedLogin) toast("Chat: conectá la cuenta en 📅 PARTIDO")
            warnedLogin = true
            wait = 30_000
          } else {
            val res = YouTubeApi.chatMessages(token, chatId, page)
            page = res.nextPageToken
            // La primera vez llegan los mensajes viejos: mostrar solo los 2 últimos.
            val msgs = if (first) res.messages.takeLast(2) else res.messages
            first = false
            if (msgs.isNotEmpty()) handler.post { addChat(msgs) }
            wait = max(res.pollMs, CHAT_POLL_MIN_MS)
          }
        } catch (e: YouTubeException) {
          if (e.reason == "liveChatEnded" || e.reason == "liveChatNotFound" ||
            e.reason == "liveChatDisabled" || e.reason == "quotaExceeded") {
            toast("Chat: ${e.message}")
            running = false
          }
          wait = 15_000
        } catch (e: Exception) {
          wait = 10_000   // sin señal: reintenta
        }
        if (running) try { Thread.sleep(wait) } catch (e: InterruptedException) { }
      }
    }
  }

  private fun addChat(msgs: List<YouTubeApi.ChatMessage>) {
    if (chatPoller == null) return
    val now = System.currentTimeMillis()
    // Si llegan varios juntos, se escalonan un poco para que se puedan leer.
    msgs.takeLast(CHAT_MAX_VISIBLE).forEachIndexed { i, m ->
      chatItems.add(ChatItem(m, now + i * 1500L, chatCounter++))
    }
    while (chatItems.size > 10) chatItems.removeAt(0)
    redrawChat(force = false)
  }

  private fun redrawChat(force: Boolean) {
    val filter = chatFilter ?: return
    val now = System.currentTimeMillis()
    val enabled = matchState?.chat ?: true
    val visible = if (!enabled) emptyList() else chatItems
      .filter { it.at <= now && now - it.at < CHAT_SHOW_MS }
      .takeLast(CHAT_MAX_VISIBLE)
    val key = visible.joinToString(",") { it.n.toString() }
    if (!force && key == lastChatKey) return
    lastChatKey = key

    val bmp = renderer.renderChat(visible.map { it.msg })
    if (bmp == null) {
      filter.alpha = 0f
      return
    }
    val hPct = bmp.height / ScoreboardRenderer.S * 100f / HEIGHT
    filter.setImage(bmp)
    filter.setScale(ScoreboardRenderer.CHAT_W * 100f / WIDTH, hPct)
    filter.setPosition(2f, 100f - hPct - 4f)
    filter.alpha = 1f
  }

  // ---------------- Modo ahorro ----------------

  private fun enterSaver() {
    saverCover.visibility = View.VISIBLE
    if (stream.isOnPreview) stream.stopPreview()
    window.attributes = window.attributes.apply { screenBrightness = 0.01f }
  }

  private fun exitSaver() {
    if (saverCover.visibility != View.VISIBLE) return
    saverCover.visibility = View.GONE
    window.attributes = window.attributes.apply {
      screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
    if (prepared && !stream.isOnPreview && surfaceView.holder.surface.isValid) startPreviewWithBoard()
  }

  // ---------------- Transmisión ----------------

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
      startPreviewWithBoard()
    }
  }

  private fun stopStreaming(message: String) {
    runOnUiThread {
      exitSaver()
      removeEndCard()
      stopChat()
      if (stream.isStreaming) stream.stopStream()
      btnStartStop.setText(R.string.start)
      tvStatus.text = message
      updateLiveUi()
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    handler.removeCallbacksAndMessages(null)
    subscriber?.stop()
    chatPoller?.running = false
    if (stream.isStreaming) stream.stopStream()
    stream.release()
  }

  // --- Eventos de conexión (llegan desde otro hilo) ---

  override fun onConnectionStarted(url: String) {
    bitrateAdapter.reset()
  }

  override fun onConnectionSuccess() {
    runOnUiThread { if (endCardFilter == null) tvStatus.text = "● EN VIVO" }
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
      if (stream.isStreaming && endCardFilter == null && report.throughput != Throughput.UNKNOWN) {
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
