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
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
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

    // Medidas de referencia para ubicar los gráficos (en % del video, sirven para cualquier calidad).
    private const val WIDTH = 1280
    private const val HEIGHT = 720
    private const val FPS = 30
    private const val KEY_QUALITY = "quality"
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

  /** Calidades disponibles: resolución y bitrate máximo. */
  private data class Quality(val label: String, val w: Int, val h: Int, val bitrate: Int)
  private val qualities = listOf(
    Quality("480p", 854, 480, 1200 * 1000),
    Quality("720p", 1280, 720, 2500 * 1000),
    Quality("1080p", 1920, 1080, 5000 * 1000),
  )
  private var quality = qualities[1]
  private lateinit var btnQuality: Button

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
  private var welcomeFilter: ImageObjectFilterRender? = null
  private var lastWelcomeKey = ""
  private var chatPoller: ChatPoller? = null
  private data class ChatItem(val msg: YouTubeApi.ChatMessage, val at: Long, val n: Long)
  private val chatItems = ArrayList<ChatItem>()
  private var chatCounter = 0L
  private var lastChatKey = ""

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
      redrawWelcome(force = false)
      handler.postDelayed(this, 250)
    }
  }

  // Baja la calidad sola si la conexión se pone lenta, para que no se corte.
  private var bitrateAdapter: QueueAwareBitrateAdapter? = null

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
    btnQuality = findViewById(R.id.btnQuality)
    val savedQuality = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_QUALITY, "720p")
    setQuality(qualities.firstOrNull { it.label == savedQuality } ?: qualities[1], save = false)
    btnQuality.setOnClickListener { chooseQuality() }
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
        if (selection == null) {
          offerQuickLive()
          return@setOnClickListener
        }
        val key = etKey.text.toString().trim()
        if (key.isEmpty()) {
          toast("Elegí el partido de nuevo con 📅 PARTIDO")
          return@setOnClickListener
        }
        goLive(key)
      } else if (endCardFilter != null) {
        // Ya está mostrando la pantalla de cierre: terminar ya (también en YouTube).
        finishEndCard()
      } else {
        AlertDialog.Builder(this)
          .setTitle("Terminar transmisión")
          .setItems(arrayOf(
            "Cerrar con pantalla final ($END_CARD_SECONDS s) y finalizar el vivo",
            "Cortar ahora (el vivo queda abierto por si volvés)",
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
    val welcome = ImageObjectFilterRender().also { it.alpha = 0f; welcomeFilter = it }
    // El chat va encima de la bienvenida para que se vea también en la previa.
    gl.addFilter(board)
    gl.addFilter(welcome)
    gl.addFilter(chat)
    endCardBitmap?.let { bmp ->
      val card = newEndCardFilter(bmp)
      endCardFilter = card
      gl.addFilter(card)
    }
    redrawBoard(force = true)
    redrawChat(force = true)
    redrawWelcome(force = true)
  }

  /** Pantalla de bienvenida con la formación: se muestra/oculta desde el celular de control. */
  private fun redrawWelcome(force: Boolean) {
    val filter = welcomeFilter ?: return
    val state = matchState
    if (state == null || !state.welcome || state.starters.isEmpty()) {
      if (force || lastWelcomeKey != "off") {
        filter.alpha = 0f
        lastWelcomeKey = "off"
      }
      return
    }
    val compact = welcomeWithChat()
    val key = listOf(state.home, state.away, state.formation, state.starters, state.subs, compact).toString()
    if (!force && key == lastWelcomeKey) return
    lastWelcomeKey = key
    filter.setImage(renderer.renderWelcome(state, quality.w, quality.h, compact))
    filter.setScale(100f, 100f)
    filter.setPosition(0f, 0f)
    filter.alpha = 1f
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
    val bmp = renderer.renderEndCard(matchState, quality.w, quality.h)
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
        finishEndCard()
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
    btnQuality.visibility = if (live) View.GONE else View.VISIBLE
    tvMatch.visibility = if (live || selection == null) View.GONE else View.VISIBLE
  }

  // ---------------- Calidad ----------------

  private fun setQuality(q: Quality, save: Boolean) {
    quality = q
    if (::btnQuality.isInitialized) btnQuality.text = q.label
    if (save) getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_QUALITY, q.label).apply()
  }

  private fun chooseQuality() {
    if (stream.isStreaming) {
      toast("La calidad se cambia antes de salir en vivo")
      return
    }
    val labels = arrayOf(
      "480p · ~0,5 GB por hora · para señal floja",
      "720p · ~1,1 GB por hora · recomendado",
      "1080p · ~2,3 GB por hora · necesita buena señal",
    )
    AlertDialog.Builder(this)
      .setTitle("Calidad del video")
      .setSingleChoiceItems(labels, qualities.indexOf(quality)) { dialog, which ->
        dialog.dismiss()
        val q = qualities[which]
        if (q == quality) return@setSingleChoiceItems
        setQuality(q, save = true)
        // Reiniciar cámara y codificador con la nueva calidad.
        if (prepared) {
          if (stream.isOnPreview) stream.stopPreview()
          stream.release()
          prepared = false
          prepareStream()
        }
        toast("Calidad: ${quality.label}")
      }
      .show()
  }

  // ---------------- Salir en vivo ----------------

  private fun goLive(key: String) {
    saveKey(key)
    etKey.setText(key)
    stream.startStream(YOUTUBE_RTMP + key)
    btnStartStop.setText(R.string.stop)
    tvStatus.text = "Conectando…"
    updateLiveUi()
    startChat()
  }

  /**
   * Sin partido elegido: si la cuenta está conectada, ofrece crear el vivo ahora
   * (con la privacidad elegida). Si no, usa la clave manual.
   */
  private fun offerQuickLive() {
    tvStatus.text = "Revisando cuenta…"
    btnStartStop.isEnabled = false
    thread {
      val token = YouTubeAuth.getTokenSilently(this)
      runOnUiThread {
        btnStartStop.isEnabled = true
        tvStatus.text = "Sin transmitir"
        if (token != null) showQuickLiveDialog(token) else manualKeyFallback()
      }
    }
  }

  private fun manualKeyFallback() {
    val key = etKey.text.toString().trim()
    if (key.isEmpty()) {
      showInfo("Falta el partido",
        "Elegí o creá un partido con 📅 PARTIDO (conectando la cuenta de YouTube), " +
          "o pegá una clave de transmisión.")
      return
    }
    AlertDialog.Builder(this)
      .setTitle("Transmitir con clave manual")
      .setMessage("No hay partido elegido ni cuenta conectada. YouTube va a crear el vivo " +
        "con su configuración por defecto (puede quedar privado) y no se verá el chat. ¿Seguir?")
      .setPositiveButton("Seguir") { _, _ -> goLive(key) }
      .setNegativeButton("Cancelar", null)
      .show()
  }

  private fun showQuickLiveDialog(token: String) {
    val pad = (16 * resources.displayMetrics.density).toInt()
    val box = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(pad, pad / 2, pad, 0)
    }
    val away = matchState?.away?.takeIf { it.isNotBlank() && it != "RIVAL" && it != "VISITA" }
    val etTitle = EditText(this).apply {
      setText(if (away != null) "La Peña FC vs ${away.split(" ").joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }}" else "La Peña FC en vivo")
      isSingleLine = true
    }
    val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
    val options = listOf("public" to "Público", "unlisted" to "No listado", "private" to "Privado")
    options.forEachIndexed { i, (_, label) ->
      rg.addView(RadioButton(this).apply { id = 1000 + i; text = label; isChecked = i == 0 })
    }
    box.addView(TextView(this).apply { text = "Título" })
    box.addView(etTitle)
    box.addView(rg)

    val builder = AlertDialog.Builder(this)
      .setTitle("No elegiste partido. ¿Crear un vivo ahora?")
      .setView(box)
      .setPositiveButton("CREAR Y TRANSMITIR") { _, _ ->
        val title = etTitle.text.toString().trim().ifEmpty { "La Peña FC en vivo" }
        val privacy = options[(rg.checkedRadioButtonId - 1000).coerceIn(0, 2)].first
        createAndGoLive(token, title, privacy)
      }
      .setNegativeButton("Cancelar", null)
    if (etKey.text.isNotBlank()) {
      builder.setNeutralButton("Clave manual") { _, _ -> goLive(etKey.text.toString().trim()) }
    }
    builder.show()
  }

  private fun createAndGoLive(token: String, title: String, privacy: String) {
    tvStatus.text = "Creando vivo en YouTube…"
    btnStartStop.isEnabled = false
    thread {
      try {
        val created = YouTubeApi.schedule(token, title, "", System.currentTimeMillis() + 60_000, privacy)
        val (ready, key) = YouTubeApi.prepareForStreaming(token, created)
        Selection.save(this, Selection(ready.id, ready.title, ready.liveChatId ?: created.liveChatId), key)
        runOnUiThread {
          btnStartStop.isEnabled = true
          loadSelection()
          goLive(key)
        }
      } catch (e: Exception) {
        runOnUiThread {
          btnStartStop.isEnabled = true
          tvStatus.text = "Sin transmitir"
          showInfo("No se pudo crear el vivo", e.message ?: "Error de YouTube")
        }
      }
    }
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

  private var finishing = false

  /**
   * Termina desde la pantalla final: primero finaliza el vivo en YouTube
   * (así el video termina en la pantalla final) y después corta la señal.
   */
  private fun finishEndCard() {
    if (finishing) return
    finishing = true
    handler.removeCallbacks(endCardTick)
    val sel = selection
    if (sel == null) {
      // Clave manual: la app no puede cerrar el vivo; hay que hacerlo en YouTube Studio.
      stopStreaming("Transmisión finalizada")
      finishing = false
      showInfo("Transmisión terminada",
        "Como usaste una clave manual, finalizá el vivo en YouTube Studio. " +
          "Si elegís el partido con 📅 PARTIDO, la app lo cierra sola.")
      return
    }
    tvStatus.text = "Finalizando en YouTube…"
    btnStartStop.isEnabled = false
    thread {
      val error: String? = try {
        val token = YouTubeAuth.getTokenSilently(this)
        if (token == null) {
          "Falta volver a conectar la cuenta de YouTube."
        } else {
          YouTubeApi.complete(token, sel.broadcastId)
          null
        }
      } catch (e: YouTubeException) {
        if (e.reason == "redundantTransition") null else e.message
      } catch (e: Exception) {
        e.message ?: "Sin conexión"
      }
      runOnUiThread {
        // Unos segundos más de pantalla final para que YouTube la guarde en el video.
        handler.postDelayed({
          stopStreaming(if (error == null) "Vivo finalizado en YouTube ✓" else "Transmisión detenida")
          btnStartStop.isEnabled = true
          finishing = false
          if (error == null) {
            Selection.clear(this)
            loadSelection()
            toast("Vivo finalizado en YouTube ✓")
          } else {
            showInfo("No se pudo finalizar en YouTube",
              "$error\n\nEntrá a YouTube Studio y tocá \"Finalizar transmisión\".")
          }
        }, 3000)
      }
    }
  }

  private fun showInfo(title: String, message: String) {
    AlertDialog.Builder(this).setTitle(title).setMessage(message)
      .setPositiveButton("OK", null).show()
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

  /** Si hay chat activo, la bienvenida deja lugar abajo a la izquierda. */
  private fun welcomeWithChat(): Boolean =
    chatPoller != null && (matchState?.chat ?: true)

  private fun redrawChat(force: Boolean) {
    val filter = chatFilter ?: return
    val now = System.currentTimeMillis()
    val enabled = matchState?.chat ?: true
    val visible = if (!enabled) emptyList() else chatItems
      .filter { it.at <= now && now - it.at < CHAT_SHOW_MS }
      .takeLast(if (matchState?.welcome == true) 2 else CHAT_MAX_VISIBLE)
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

  private fun tryPrepare(q: Quality): Boolean = try {
    stream.prepareVideo(q.w, q.h, q.bitrate, fps = FPS, iFrameInterval = 2) &&
      stream.prepareAudio(SAMPLE_RATE, true, AUDIO_BITRATE)
  } catch (e: Exception) {
    false
  }

  private fun prepareStream() {
    if (prepared) return
    prepared = tryPrepare(quality)
    if (!prepared && quality != qualities[1]) {
      toast("Este celular no soporta ${quality.label}. Se usa 720p.")
      setQuality(qualities[1], save = true)
      prepared = tryPrepare(quality)
    }
    if (!prepared) {
      toast("Este celular no soporta la configuración de video/audio")
      return
    }
    // Baja la calidad sola si la conexión se pone lenta, para que no se corte.
    bitrateAdapter = QueueAwareBitrateAdapter(maxBitrate = quality.bitrate + AUDIO_BITRATE) {
      stream.setVideoBitrateOnFly(it - AUDIO_BITRATE)
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
    bitrateAdapter?.reset()
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
      bitrateAdapter?.onStreamingStats(report)
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
