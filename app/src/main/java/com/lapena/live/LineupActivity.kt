package com.lapena.live

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Editor de la formación (en el celular de control).
 * Guarda en el estado del control; al volver, el control lo envía al celular que transmite.
 */
class LineupActivity : AppCompatActivity() {

  companion object {
    const val PREFS = "lapena_control"
    const val KEY_STATE = "state"

    private val F7_FORMATIONS = listOf("2-3-1", "3-2-1", "3-1-2", "2-2-2", "3-3", "2-1-2-1")
    private val FUTSAL_FORMATIONS = listOf("1-2-1", "2-2", "1-1-2", "3-1")
  }

  private var state = MatchState()
  private var outfield = 6
  private val numberFields = ArrayList<EditText>()
  private val nameFields = ArrayList<EditText>()

  private var roster: List<Player> = emptyList()
  private lateinit var spinner: Spinner
  private lateinit var rows: LinearLayout
  private lateinit var etSubs: EditText

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STATE, null)
      ?.let { MatchState.fromJson(it) }?.let { state = it }
    outfield = if (state.starters.size == 5 || (state.starters.isEmpty() && state.periodMinutes == 20)) 4 else 6

    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(16), dp(16), dp(16), dp(16))
    }
    setContentView(ScrollView(this).apply {
      setBackgroundColor(Color.parseColor("#141414"))
      addView(root)
    })

    root.addView(label("Formación de La Peña", 22f, bold = true))

    roster = loadRoster()
    root.addView(Button(this).apply {
      text = "EDITAR PLANTEL (${roster.size})"
      setOnClickListener { editRoster(this) }
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    // Modalidad
    val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
    val rbF7 = radio("F7 (7 jugadores)")
    val rbFutsal = radio("Futsal (5)")
    rg.addView(rbF7); rg.addView(rbFutsal)
    (if (outfield == 6) rbF7 else rbFutsal).isChecked = true
    root.addView(rg)

    // Esquema
    root.addView(label("Esquema (de defensa a ataque)", 14f))
    spinner = Spinner(this)
    root.addView(spinner)

    rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(rows)

    root.addView(label("Suplentes (uno por línea, ej: 10 Juan)", 14f).apply { setPadding(0, dp(16), 0, 0) })
    root.addView(Button(this).apply {
      text = "ELEGIR SUPLENTES DEL PLANTEL"
      setOnClickListener { pickSubs() }
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    etSubs = EditText(this).apply {
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_WORDS
      minLines = 3
      gravity = Gravity.TOP
      setTextColor(Color.WHITE)
      setHintTextColor(Color.GRAY)
      hint = "7 Pedro\n12 Lucas"
      setText(state.subs.joinToString("\n") { "${it.number} ${it.name}".trim() })
    }
    root.addView(etSubs)

    root.addView(Button(this).apply {
      text = "GUARDAR"
      setTextColor(Color.WHITE)
      backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2E7D32"))
      setOnClickListener { save() }
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply { topMargin = dp(16) })

    // Precargar lo que ya había
    buildRows(keep = state.starters)
    setupSpinner(state.formation)

    rg.setOnCheckedChangeListener { _, id ->
      val newOutfield = if (id == rbF7.id) 6 else 4
      if (newOutfield != outfield) {
        val current = currentStarters()
        outfield = newOutfield
        buildRows(keep = current)
        setupSpinner(null)
      }
    }
  }

  private fun formations() = if (outfield == 6) F7_FORMATIONS else FUTSAL_FORMATIONS

  private fun setupSpinner(selected: String?) {
    val list = formations()
    spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, list)
    val idx = list.indexOf(selected).coerceAtLeast(0)
    spinner.setSelection(idx)
    spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
      override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        (view as? TextView)?.setTextColor(Color.WHITE)
        relabel()
      }
      override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
  }

  /** Arquero + jugadores de campo; conserva lo escrito si cambia la cantidad. */
  private fun buildRows(keep: List<Player>) {
    rows.removeAllViews()
    numberFields.clear(); nameFields.clear()
    for (i in 0..outfield) {
      val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, 0)
      }
      val tag = label("", 12f).apply { minWidth = dp(96) }
      val num = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        hint = "N°"
        setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        gravity = Gravity.CENTER
        setText(keep.getOrNull(i)?.number.orEmpty())
      }
      val name = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        hint = "Nombre"
        setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        isSingleLine = true
        setText(keep.getOrNull(i)?.name.orEmpty())
      }
      val pick = Button(this).apply {
        text = "▼"
        setOnClickListener { pickPlayer(num, name) }
      }
      row.addView(tag)
      row.addView(num, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT))
      row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
      row.addView(pick, LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT))
      rows.addView(row)
      numberFields.add(num); nameFields.add(name)
    }
    relabel()
  }

  /** Pone el puesto al lado de cada fila según el esquema elegido. */
  private fun relabel() {
    val formation = spinner.selectedItem as? String ?: formations().first()
    val lines = MatchState.parseFormation(formation, outfield)
    val labels = ArrayList<String>().apply { add("Arquero") }
    lines.forEachIndexed { li, count ->
      repeat(count) { k -> labels.add("${MatchState.lineLabel(li, lines.size)} ${k + 1}") }
    }
    for (i in 0 until rows.childCount) {
      val tag = (rows.getChildAt(i) as LinearLayout).getChildAt(0) as TextView
      tag.text = labels.getOrElse(i) { "" }
    }
  }

  // ---------------- Plantel ----------------

  private fun fmt(p: Player) = listOf(p.number, p.name).filter { it.isNotEmpty() }.joinToString(" ")

  private fun parsePlayer(line: String): Player {
    val parts = line.trim().split(Regex("\\s+"), limit = 2)
    return if (parts.size == 2 && parts[0].all { it.isDigit() }) Player(parts[0], parts[1]) else Player("", line.trim())
  }

  /** Plantel guardado; la primera vez se arma con los jugadores ya cargados. */
  private fun loadRoster(): List<Player> {
    val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val saved = prefs.getString("roster", null)
    if (saved != null) return saved.lines().filter { it.isNotBlank() }.map(::parsePlayer)
    val seeded = (state.starters + state.subs).filter { it.name.isNotEmpty() }.distinctBy { it.name.lowercase() }
    if (seeded.isNotEmpty()) saveRoster(seeded)
    return seeded
  }

  private fun saveRoster(list: List<Player>) {
    getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
      .putString("roster", list.joinToString("\n") { fmt(it) }).apply()
  }

  private fun sortedRoster() = roster.sortedWith(compareBy({ it.number.toIntOrNull() ?: 999 }, { it.name }))

  private fun editRoster(button: Button) {
    val et = EditText(this).apply {
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_WORDS
      minLines = 8
      gravity = Gravity.TOP
      setText(sortedRoster().joinToString("\n") { fmt(it) })
    }
    AlertDialog.Builder(this)
      .setTitle("Plantel (uno por línea: número y nombre)")
      .setView(et)
      .setPositiveButton("Guardar") { _, _ ->
        roster = et.text.toString().lines().filter { it.isNotBlank() }.map(::parsePlayer)
        saveRoster(roster)
        button.text = "EDITAR PLANTEL (${roster.size})"
      }
      .setNegativeButton("Cancelar", null)
      .show()
  }

  /** Elegir un jugador del plantel para una fila de titular. */
  private fun pickPlayer(num: EditText, name: EditText) {
    val list = sortedRoster()
    if (list.isEmpty()) {
      Toast.makeText(this, "Cargá el plantel con EDITAR PLANTEL", Toast.LENGTH_LONG).show()
      return
    }
    AlertDialog.Builder(this)
      .setItems(list.map { fmt(it) }.toTypedArray()) { _, i ->
        num.setText(list[i].number)
        name.setText(list[i].name)
      }
      .show()
  }

  /** Marcar suplentes del plantel; los escritos a mano que no están en el plantel se conservan. */
  private fun pickSubs() {
    val starters = currentStarters().map { it.name.lowercase() }.toSet()
    val options = sortedRoster().filter { it.name.lowercase() !in starters }
    if (options.isEmpty()) {
      Toast.makeText(this, "No hay jugadores del plantel disponibles", Toast.LENGTH_LONG).show()
      return
    }
    val currentLines = etSubs.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
    val currentNames = currentLines.map { parsePlayer(it).name.lowercase() }.toSet()
    val checked = options.map { it.name.lowercase() in currentNames }.toBooleanArray()
    AlertDialog.Builder(this)
      .setTitle("Suplentes")
      .setMultiChoiceItems(options.map { fmt(it) }.toTypedArray(), checked) { _, i, isChecked -> checked[i] = isChecked }
      .setPositiveButton("Listo") { _, _ ->
        val rosterNames = roster.map { it.name.lowercase() }.toSet()
        val extras = currentLines.filter { parsePlayer(it).name.lowercase() !in rosterNames }
        val chosen = options.filterIndexed { i, _ -> checked[i] }.map { fmt(it) }
        etSubs.setText((chosen + extras).joinToString("\n"))
      }
      .setNegativeButton("Cancelar", null)
      .show()
  }

  private fun currentStarters(): List<Player> = numberFields.indices.map {
    Player(numberFields[it].text.toString().trim(), nameFields[it].text.toString().trim())
  }

  private fun save() {
    val starters = currentStarters()
    if (starters.all { it.name.isEmpty() }) {
      Toast.makeText(this, "Escribí al menos algunos nombres", Toast.LENGTH_LONG).show()
      return
    }
    val subs = etSubs.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
      val parts = line.split(Regex("\\s+"), limit = 2)
      if (parts.size == 2 && parts[0].all { it.isDigit() }) Player(parts[0], parts[1]) else Player("", line)
    }
    // Releer el estado guardado (puede haber cambiado el reloj o los goles) y solo tocar la formación.
    val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val latest = prefs.getString(KEY_STATE, null)?.let { MatchState.fromJson(it) } ?: state
    val updated = latest.copy(
      formation = spinner.selectedItem as? String ?: formations().first(),
      starters = starters,
      subs = subs,
    )
    prefs.edit().putString(KEY_STATE, updated.toJson()).putBoolean("lineup_dirty", true).apply()
    Toast.makeText(this, "Formación guardada", Toast.LENGTH_SHORT).show()
    finish()
  }

  private fun label(text: String, size: Float, bold: Boolean = false) = TextView(this).apply {
    this.text = text
    textSize = size
    setTextColor(Color.WHITE)
    if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
  }

  private fun radio(text: String) = RadioButton(this).apply {
    this.text = text
    setTextColor(Color.WHITE)
    buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
  }

  private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
