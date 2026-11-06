package com.lapena.live

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Partidos en YouTube: conectar la cuenta, programar un partido nuevo
 * y elegir cuál transmitir.
 */
class BroadcastsActivity : AppCompatActivity() {

  companion object {
    /** true si se abrió desde la pantalla de transmisión (vuelve a ella al elegir). */
    const val EXTRA_FROM_STREAM = "from_stream"
  }

  private var token: String? = null
  private var afterLogin: ((String) -> Unit)? = null

  private lateinit var tvAccount: TextView
  private lateinit var btnConnect: Button
  private lateinit var form: View
  private lateinit var etRival: EditText
  private lateinit var etTitle: EditText
  private lateinit var etDescription: EditText
  private lateinit var btnDate: Button
  private lateinit var btnTime: Button
  private lateinit var rgPrivacy: RadioGroup
  private lateinit var tvListStatus: TextView
  private lateinit var list: LinearLayout

  private val start: Calendar = Calendar.getInstance().apply {
    // Por defecto: mañana a las 20:00
    add(Calendar.DAY_OF_YEAR, 1)
    set(Calendar.HOUR_OF_DAY, 20); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
  }
  private var titleEditedByHand = false

  private val loginLauncher =
    registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
      val t = YouTubeAuth.tokenFromIntent(this, res.data)
      if (t != null) {
        onLoggedIn(t)
      } else {
        tvAccount.text = "No se pudo conectar la cuenta"
        afterLogin = null
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_broadcasts)

    tvAccount = findViewById(R.id.tvAccount)
    btnConnect = findViewById(R.id.btnConnect)
    form = findViewById(R.id.form)
    etRival = findViewById(R.id.etRival)
    etTitle = findViewById(R.id.etTitle)
    etDescription = findViewById(R.id.etDescription)
    btnDate = findViewById(R.id.btnDate)
    btnTime = findViewById(R.id.btnTime)
    rgPrivacy = findViewById(R.id.rgPrivacy)
    tvListStatus = findViewById(R.id.tvListStatus)
    list = findViewById(R.id.list)

    btnConnect.setOnClickListener { withToken { loadList() } }
    findViewById<Button>(R.id.btnRefresh).setOnClickListener { withToken { loadList() } }

    findViewById<Button>(R.id.btnNew).setOnClickListener {
      form.visibility = if (form.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    etRival.addTextChangedListener(watcher {
      if (!titleEditedByHand) {
        val rival = etRival.text.toString().trim()
        etTitle.setText(if (rival.isEmpty()) "" else "La Peña FC vs $rival")
        titleEditedByHand = false
      }
    })
    etTitle.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) titleEditedByHand = true }

    updateDateButtons()
    btnDate.setOnClickListener {
      DatePickerDialog(this, { _, y, m, d ->
        start.set(y, m, d); updateDateButtons()
      }, start.get(Calendar.YEAR), start.get(Calendar.MONTH), start.get(Calendar.DAY_OF_MONTH)).show()
    }
    btnTime.setOnClickListener {
      TimePickerDialog(this, { _, h, min ->
        start.set(Calendar.HOUR_OF_DAY, h); start.set(Calendar.MINUTE, min); updateDateButtons()
      }, start.get(Calendar.HOUR_OF_DAY), start.get(Calendar.MINUTE), true).show()
    }

    findViewById<Button>(R.id.btnCreate).setOnClickListener { createBroadcast() }

    // Si ya se conectó antes, entra directo sin pantallas.
    withToken { loadList() }
  }

  // ---------------- Cuenta ----------------

  private fun withToken(action: (String) -> Unit) {
    afterLogin = action
    tvAccount.text = "Conectando con YouTube…"
    YouTubeAuth.getToken(this, loginLauncher,
      onToken = { onLoggedIn(it) },
      onError = { msg ->
        tvAccount.text = "Cuenta sin conectar ($msg)"
        afterLogin = null
      })
  }

  private fun onLoggedIn(t: String) {
    token = t
    tvAccount.text = "✓ Cuenta de YouTube conectada"
    btnConnect.visibility = View.GONE
    val action = afterLogin
    afterLogin = null
    action?.invoke(t)
  }

  // ---------------- Lista ----------------

  private fun loadList() {
    val t = token ?: return
    tvListStatus.text = "Cargando…"
    thread {
      try {
        val items = YouTubeApi.listBroadcasts(t)
        runOnUiThread { showList(items) }
      } catch (e: Exception) {
        runOnUiThread { tvListStatus.text = "Error: ${e.message}" }
      }
    }
  }

  private fun showList(items: List<YouTubeApi.Broadcast>) {
    list.removeAllViews()
    val selectedId = Selection.load(this)?.broadcastId
    tvListStatus.text = if (items.isEmpty()) "No hay partidos programados. Creá uno con ＋ PROGRAMAR PARTIDO." else ""
    for (b in items) {
      val row = layoutInflater.inflate(android.R.layout.simple_list_item_2, list, false)
      val t1 = row.findViewById<TextView>(android.R.id.text1)
      val t2 = row.findViewById<TextView>(android.R.id.text2)
      val selectedMark = if (b.id == selectedId) "✓ " else ""
      t1.text = selectedMark + b.title
      t1.setTextColor(Color.WHITE)
      t2.text = listOf(
        if (b.isLive) "🔴 EN VIVO" else "📅 " + formatLocal(b.scheduledStart),
        privacyLabel(b.privacy),
      ).joinToString("  ·  ")
      t2.setTextColor(Color.parseColor("#BBBBBB"))
      row.setPadding(8, 24, 8, 24)
      row.setOnClickListener { confirmSelect(b) }
      list.addView(row)
    }
  }

  private fun confirmSelect(b: YouTubeApi.Broadcast) {
    AlertDialog.Builder(this)
      .setTitle(b.title)
      .setMessage("¿Transmitir este partido con la app?")
      .setPositiveButton("Transmitir este") { _, _ -> select(b) }
      .setNegativeButton("Cancelar", null)
      .show()
  }

  private fun select(b: YouTubeApi.Broadcast) {
    val t = token ?: return
    tvListStatus.text = "Preparando el partido…"
    thread {
      try {
        val (ready, key) = YouTubeApi.prepareForStreaming(t, b)
        Selection.save(this, Selection(ready.id, ready.title, ready.liveChatId ?: b.liveChatId), key)
        runOnUiThread {
          Toast.makeText(this, "Listo: ${ready.title}", Toast.LENGTH_LONG).show()
          if (!intent.getBooleanExtra(EXTRA_FROM_STREAM, false)) {
            startActivity(Intent(this, MainActivity::class.java))
          }
          finish()
        }
      } catch (e: Exception) {
        runOnUiThread { tvListStatus.text = "Error: ${e.message}" }
      }
    }
  }

  // ---------------- Programar ----------------

  private fun createBroadcast() {
    val t = token
    if (t == null) {
      withToken { createBroadcast() }
      return
    }
    val title = etTitle.text.toString().trim()
    if (title.isEmpty()) {
      Toast.makeText(this, "Poné el rival o un título", Toast.LENGTH_LONG).show()
      return
    }
    if (start.timeInMillis < System.currentTimeMillis() + 60_000) {
      Toast.makeText(this, "La fecha y hora tienen que ser en el futuro", Toast.LENGTH_LONG).show()
      return
    }
    val privacy = when (rgPrivacy.checkedRadioButtonId) {
      R.id.rbUnlisted -> "unlisted"
      R.id.rbPrivate -> "private"
      else -> "public"
    }
    val description = etDescription.text.toString().trim()
    tvListStatus.text = "Creando en YouTube…"
    thread {
      try {
        YouTubeApi.schedule(t, title, description, start.timeInMillis, privacy)
        runOnUiThread {
          Toast.makeText(this, "Partido programado en YouTube ✓", Toast.LENGTH_LONG).show()
          form.visibility = View.GONE
          etRival.setText(""); etTitle.setText(""); etDescription.setText("")
          titleEditedByHand = false
          loadList()
        }
      } catch (e: Exception) {
        runOnUiThread { tvListStatus.text = "Error: ${e.message}" }
      }
    }
  }

  // ---------------- Utilidades ----------------

  private fun updateDateButtons() {
    btnDate.text = SimpleDateFormat("EEE d MMM", Locale("es")).format(start.time)
    btnTime.text = SimpleDateFormat("HH:mm", Locale("es")).format(start.time)
  }

  private fun formatLocal(iso: String): String {
    val ms = YouTubeApi.parseIso(iso) ?: return "sin fecha"
    return SimpleDateFormat("EEE d MMM · HH:mm", Locale("es")).format(ms)
  }

  private fun privacyLabel(p: String) = when (p) {
    "public" -> "Público"
    "unlisted" -> "No listado"
    "private" -> "Privado"
    else -> p
  }

  private fun watcher(onChange: () -> Unit) = object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: Editable?) = onChange()
  }
}
