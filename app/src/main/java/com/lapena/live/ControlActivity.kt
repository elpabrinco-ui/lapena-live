package com.lapena.live

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Modo control: maneja el marcador desde otro celular.
 * Cada cambio envía el estado completo al celular que transmite.
 */
class ControlActivity : AppCompatActivity() {

  companion object {
    private const val PREFS = "lapena_control"
    private const val KEY_STATE = "state"
  }

  private val handler = Handler(Looper.getMainLooper())
  private var state = MatchState()

  private lateinit var etCode: EditText
  private lateinit var tvSync: TextView
  private lateinit var etHome: EditText
  private lateinit var etAway: EditText
  private lateinit var tvHomeGoals: TextView
  private lateinit var tvAwayGoals: TextView
  private lateinit var tvClock: TextView
  private lateinit var tvTimeUp: TextView
  private lateinit var btnStartPause: Button
  private lateinit var btnF7: Button
  private lateinit var btnFutsal: Button
  private lateinit var btnP1: Button
  private lateinit var btnP2: Button
  private lateinit var cbVisible: CheckBox

  private val tick = object : Runnable {
    override fun run() {
      updateClockView()
      handler.postDelayed(this, 500)
    }
  }

  private val sendNamesLater = Runnable { send() }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_control)

    etCode = findViewById(R.id.etCode)
    tvSync = findViewById(R.id.tvSync)
    etHome = findViewById(R.id.etHome)
    etAway = findViewById(R.id.etAway)
    tvHomeGoals = findViewById(R.id.tvHomeGoals)
    tvAwayGoals = findViewById(R.id.tvAwayGoals)
    tvClock = findViewById(R.id.tvClock)
    tvTimeUp = findViewById(R.id.tvTimeUp)
    btnStartPause = findViewById(R.id.btnStartPause)
    btnF7 = findViewById(R.id.btnF7)
    btnFutsal = findViewById(R.id.btnFutsal)
    btnP1 = findViewById(R.id.btnP1)
    btnP2 = findViewById(R.id.btnP2)
    cbVisible = findViewById(R.id.cbVisible)

    // Restaurar el último estado (por si la app se cerró en medio del partido)
    getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STATE, null)
      ?.let { MatchState.fromJson(it) }?.let { state = it }

    etCode.setText(ScoreSync.savedCode(this, createIfMissing = false))
    etHome.setText(state.home)
    etAway.setText(state.away)
    cbVisible.isChecked = state.visible

    etCode.addTextChangedListener(simpleWatcher {
      val code = etCode.text.toString().trim().uppercase()
      if (code.length == 6) {
        ScoreSync.saveCode(this, code)
        send()
      } else {
        tvSync.text = "Código: 6 letras"
      }
    })

    val namesWatcher = simpleWatcher {
      state = state.copy(
        home = etHome.text.toString().trim().ifEmpty { "LOCAL" },
        away = etAway.text.toString().trim().ifEmpty { "VISITA" },
      )
      handler.removeCallbacks(sendNamesLater)
      handler.postDelayed(sendNamesLater, 1500)   // espera a que termines de escribir
    }
    etHome.addTextChangedListener(namesWatcher)
    etAway.addTextChangedListener(namesWatcher)

    findViewById<Button>(R.id.btnHomePlus).setOnClickListener { change { copy(homeGoals = homeGoals + 1) } }
    findViewById<Button>(R.id.btnHomeMinus).setOnClickListener { change { copy(homeGoals = (homeGoals - 1).coerceAtLeast(0)) } }
    findViewById<Button>(R.id.btnAwayPlus).setOnClickListener { change { copy(awayGoals = awayGoals + 1) } }
    findViewById<Button>(R.id.btnAwayMinus).setOnClickListener { change { copy(awayGoals = (awayGoals - 1).coerceAtLeast(0)) } }

    btnStartPause.setOnClickListener {
      val now = System.currentTimeMillis()
      change { copy(running = !running, elapsedMs = clockNow(now), sentAt = now) }
    }
    findViewById<Button>(R.id.btnPlusMin).setOnClickListener { adjustClock(60_000) }
    findViewById<Button>(R.id.btnMinusMin).setOnClickListener { adjustClock(-60_000) }
    findViewById<Button>(R.id.btnResetClock).setOnClickListener {
      confirm("¿Poner el reloj en 00:00?") { change { copy(running = false, elapsedMs = 0) } }
    }

    btnP1.setOnClickListener { setPeriod(1) }
    btnP2.setOnClickListener { setPeriod(2) }
    btnF7.setOnClickListener { change { copy(periodMinutes = 30) } }
    btnFutsal.setOnClickListener { change { copy(periodMinutes = 20) } }
    cbVisible.setOnCheckedChangeListener { _, checked -> change { copy(visible = checked) } }

    findViewById<Button>(R.id.btnNewMatch).setOnClickListener {
      confirm("¿Empezar un partido nuevo? Goles y reloj vuelven a cero.") {
        change { copy(homeGoals = 0, awayGoals = 0, period = 1, running = false, elapsedMs = 0) }
      }
    }

    refreshViews()
  }

  override fun onResume() {
    super.onResume()
    handler.post(tick)
  }

  override fun onPause() {
    super.onPause()
    handler.removeCallbacks(tick)
  }

  private fun setPeriod(p: Int) {
    if (p == state.period) return
    confirm("¿Pasar al ${p}T? El reloj vuelve a 00:00 y queda en pausa.") {
      change { copy(period = p, running = false, elapsedMs = 0) }
    }
  }

  private fun adjustClock(deltaMs: Long) {
    val now = System.currentTimeMillis()
    change { copy(elapsedMs = (clockNow(now) + deltaMs).coerceAtLeast(0), sentAt = now) }
  }

  /** Aplica un cambio, actualiza la pantalla y lo envía. */
  private fun change(update: MatchState.() -> MatchState) {
    val now = System.currentTimeMillis()
    // Fija el reloj al momento actual antes de cambiar, para no perder segundos.
    val base = state.copy(elapsedMs = state.clockNow(now), sentAt = now)
    state = base.update()
    refreshViews()
    send()
  }

  private fun send() {
    val now = System.currentTimeMillis()
    state = state.copy(elapsedMs = state.clockNow(now), sentAt = now)
    getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_STATE, state.toJson()).apply()

    val code = etCode.text.toString().trim().uppercase()
    if (code.length != 6) {
      tvSync.text = "Falta el código"
      return
    }
    tvSync.text = "Enviando…"
    ScoreSync.publish(code, state) { ok ->
      runOnUiThread { tvSync.text = if (ok) "✓ Enviado" else "✗ Sin conexión" }
    }
  }

  private fun refreshViews() {
    tvHomeGoals.text = state.homeGoals.toString()
    tvAwayGoals.text = state.awayGoals.toString()
    btnStartPause.text = if (state.running) "⏸  PAUSAR" else "▶  INICIAR"
    btnStartPause.backgroundTintList = android.content.res.ColorStateList.valueOf(
      android.graphics.Color.parseColor(if (state.running) "#C62828" else "#2E7D32")
    )
    highlight(btnF7, state.periodMinutes == 30)
    highlight(btnFutsal, state.periodMinutes == 20)
    highlight(btnP1, state.period == 1)
    highlight(btnP2, state.period == 2)
    updateClockView()
  }

  private fun updateClockView() {
    val ms = state.clockNow()
    tvClock.text = "${state.period}T  ${MatchState.formatClock(ms)}"
    val limit = state.periodMinutes * 60_000L
    tvTimeUp.text = if (ms >= limit) "⏱ Se cumplieron los ${state.periodMinutes}' del tiempo" else ""
  }

  private fun highlight(button: Button, selected: Boolean) {
    button.alpha = if (selected) 1f else 0.45f
  }

  private fun confirm(message: String, onYes: () -> Unit) {
    AlertDialog.Builder(this)
      .setMessage(message)
      .setPositiveButton("Sí") { _, _ -> onYes() }
      .setNegativeButton("No", null)
      .show()
  }

  private fun simpleWatcher(onChange: () -> Unit) = object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: Editable?) = onChange()
  }
}
