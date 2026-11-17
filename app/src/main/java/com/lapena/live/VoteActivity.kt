package com.lapena.live

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import kotlin.concurrent.thread

/**
 * Votación de la figura del partido (en el celular del marcador).
 *
 * - Abre la votación: crea un par de llaves RSA; la pública va en el mensaje de apertura,
 *   la privada queda solo en este celular. Los jugadores votan desde una página web
 *   (docs/votar.html en GitHub Pages) que cifra el voto con la llave pública.
 * - Acá se ve QUIÉN votó (para saber cuándo votaron todos), nunca a quién.
 * - Se cierra a los 30 minutos o a mano; recién ahí se descifran los votos y se cuenta.
 */
class VoteActivity : AppCompatActivity() {

  companion object {
    private const val PREFS = "lapena_vote"
    private const val VOTE_MINUTES = 30
    const val PAGE_URL = "https://elpabrinco-ui.github.io/lapena-live/votar.html"
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    fun topic(id: String) = "lapenavoto-$id"
  }

  private val handler = Handler(Looper.getMainLooper())
  private lateinit var root: LinearLayout

  // Votación activa
  private var voteId = ""
  private var closesAt = 0L
  private var cands: List<String> = emptyList()
  private var privKey = ""
  @Volatile private var votes: Map<String, String> = emptyMap()   // votante -> voto cifrado (último)
  private var closing = false

  private val loginLauncher =
    registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
      YouTubeAuth.tokenFromIntent(this, res.data)?.let { addToYouTube(it) }
        ?: toast("No se pudo conectar la cuenta de YouTube")
    }

  private val tick = object : Runnable {
    override fun run() {
      if (voteId.isNotEmpty() && !closing) {
        if (System.currentTimeMillis() >= closesAt) closeVote() else updateActive()
      }
      handler.postDelayed(this, 1000)
    }
  }

  private val pollVotes = object : Runnable {
    override fun run() {
      if (voteId.isNotEmpty() && !closing) refreshVotes()
      handler.postDelayed(this, 8000)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(16), dp(16), dp(16), dp(16))
    }
    setContentView(ScrollView(this).apply {
      setBackgroundColor(Color.parseColor("#141414"))
      addView(root)
    })
    loadSaved()
    show()
  }

  override fun onResume() {
    super.onResume()
    handler.post(tick)
    handler.post(pollVotes)
  }

  override fun onPause() {
    super.onPause()
    handler.removeCallbacks(tick)
    handler.removeCallbacks(pollVotes)
  }

  // ---------------- Pantallas ----------------

  private fun show() {
    val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
    val result = prefs.getString("result", null)
    when {
      result != null -> showResult(JSONObject(result))
      voteId.isNotEmpty() -> showActive()
      else -> showStart()
    }
  }

  private fun showStart() {
    root.removeAllViews()
    title("⭐ Figura del partido")
    val state = controlState()
    val played = playedLabels(state)
    if (played.isEmpty()) {
      text("Primero cargá la formación (titulares y suplentes) en FORMACIÓN: los candidatos son los que jugaron.")
      return
    }
    text("Candidatos (los que jugaron):", muted = true)
    text(played.joinToString("  ·  "))
    text("Votan todos los presentes (cualquiera del plantel). Nadie se puede votar a sí mismo. " +
      "El voto es secreto: acá solo vas a ver quién votó, no a quién.", muted = true)
    button("ABRIR VOTACIÓN ($VOTE_MINUTES MIN)", "#2E7D32") { openVote(state, played) }
  }

  private fun showActive() {
    root.removeAllViews()
    title("⭐ Votación abierta")
    text("Mandá este link al grupo de WhatsApp:", muted = true)
    text(link()).apply { setTextIsSelectable(true); setTextColor(Color.parseColor("#9ecbff")) }
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    row.addView(smallButton("COMPARTIR") { share() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    row.addView(smallButton("COPIAR") { copy() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    root.addView(row)
    text("", big = true).apply { id = View.generateViewId(); tag = "left" }
    text("", muted = false).apply { tag = "voters" }
    button("CERRAR Y VER RESULTADO", "#C62828") {
      AlertDialog.Builder(this)
        .setMessage("¿Cerrar la votación ahora? Ya no se va a poder votar.")
        .setPositiveButton("Cerrar") { _, _ -> closeVote() }
        .setNegativeButton("Seguir", null)
        .show()
    }
    updateActive()
    refreshVotes()
  }

  private fun updateActive() {
    root.findViewWithTag<TextView>("left")?.text = "⏱ Cierra en " + remaining()
    val names = votes.keys.sorted()
    root.findViewWithTag<TextView>("voters")?.text =
      if (names.isEmpty()) "Todavía no votó nadie." else "Votaron (${names.size}): ${names.joinToString(", ")}"
  }

  private fun showResult(r: JSONObject) {
    root.removeAllViews()
    val winners = r.optJSONArray("winner")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
    title(if (winners.size > 1) "⭐ Empate · figuras del partido" else "⭐ Figura del partido")
    text(if (winners.isEmpty()) "Sin votos" else winners.joinToString(" y "), big = true)
    text("Votaron ${r.optInt("voters")}", muted = true)
    val counts = r.optJSONObject("counts") ?: JSONObject()
    counts.keys().asSequence().map { it to counts.getInt(it) }.sortedByDescending { it.second }
      .forEach { (c, n) -> text("$c — $n") }
    if (winners.isNotEmpty()) {
      button("AGREGAR A LA DESCRIPCIÓN EN YOUTUBE", "#C62828") {
        YouTubeAuth.getToken(this, loginLauncher, onToken = { addToYouTube(it) }, onError = { toast(it) })
      }
    }
    button("NUEVA VOTACIÓN", "#444444") {
      AlertDialog.Builder(this)
        .setMessage("¿Borrar este resultado y preparar una votación nueva?")
        .setPositiveButton("Sí") { _, _ ->
          getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply()
          voteId = ""; votes = emptyMap(); closing = false
          showStart()
        }
        .setNegativeButton("No", null)
        .show()
    }
  }

  // ---------------- Abrir / leer / cerrar ----------------

  private fun openVote(state: MatchState, played: List<String>) {
    val id = (1..6).map { ALPHABET.random() }.joinToString("")
    val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
    val kp = kpg.generateKeyPair()
    val pub = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)      // X.509 (SPKI): lo entiende la web
    val priv = Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP)    // PKCS#8: solo en este celular
    val closes = System.currentTimeMillis() + VOTE_MINUTES * 60_000L
    val voters = (rosterLabels() + played).distinct().sortedWith(compareBy({ numOf(it) }, { it }))
    val open = JSONObject()
      .put("t", "open").put("v", 1)
      .put("title", "${state.home.uppercase()} vs ${state.away.uppercase()}")
      .put("cands", JSONArray(played))
      .put("voters", JSONArray(voters))
      .put("pub", pub)
      .put("closes", closes)
    toast("Abriendo votación…")
    thread {
      val ok = ScoreSync.publishRaw(topic(id), open.toString())
      runOnUiThread {
        if (!ok) {
          toast("Sin conexión: no se pudo abrir la votación")
          return@runOnUiThread
        }
        voteId = id; closesAt = closes; cands = played; privKey = priv; votes = emptyMap()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
          .putString("id", id).putLong("closes", closes)
          .putString("cands", JSONArray(played).toString()).putString("priv", priv)
          .apply()
        showActive()
        share()
      }
    }
  }

  /** Lee los votos del tópico: se queda con el último voto de cada votante. */
  private fun refreshVotes() {
    val id = voteId
    thread {
      val msgs = try { ScoreSync.pollAll(topic(id)) } catch (e: Exception) { return@thread }
      val latest = LinkedHashMap<String, String>()
      for ((_, text) in msgs.sortedBy { it.first }) {
        val m = try { JSONObject(text) } catch (e: Exception) { continue }
        if (m.optString("t") == "vote") {
          val voter = m.optString("voter")
          if (voter.isNotBlank()) latest[voter] = m.optString("enc")
        }
      }
      runOnUiThread {
        if (id == voteId) {
          votes = latest
          if (!closing) updateActive()
        }
      }
    }
  }

  private fun closeVote() {
    if (closing) return
    closing = true
    toast("Cerrando la votación…")
    val id = voteId
    thread {
      // Última lectura de votos antes de contar
      val msgs = try { ScoreSync.pollAll(topic(id)) } catch (e: Exception) { emptyList() }
      val latest = LinkedHashMap<String, String>(votes)
      for ((_, text) in msgs.sortedBy { it.first }) {
        val m = try { JSONObject(text) } catch (e: Exception) { continue }
        if (m.optString("t") == "vote" && m.optString("voter").isNotBlank()) latest[m.optString("voter")] = m.optString("enc")
      }
      // Descifrar y contar (votos inválidos o a uno mismo no cuentan)
      val counts = LinkedHashMap<String, Int>()
      cands.forEach { counts[it] = 0 }
      var valid = 0
      for ((voter, enc) in latest) {
        val choice = decrypt(enc) ?: continue
        if (choice !in cands || choice == voter) continue
        counts[choice] = counts.getValue(choice) + 1
        valid++
      }
      val max = counts.values.maxOrNull() ?: 0
      val winners = if (max > 0) counts.filter { it.value == max }.keys.toList() else emptyList()
      val shown = counts.filter { it.value > 0 }
      val result = JSONObject()
        .put("t", "closed")
        .put("winner", JSONArray(winners))
        .put("counts", JSONObject(shown as Map<*, *>))
        .put("voters", valid)
      ScoreSync.publishRaw(topic(id), result.toString())
      runOnUiThread {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("result", result.toString()).remove("priv").apply()
        privKey = ""
        showResult(result)
      }
    }
  }

  private fun decrypt(b64: String): String? = try {
    val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privKey, Base64.NO_WRAP)))
    val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
    cipher.init(Cipher.DECRYPT_MODE, key,
      OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
    String(cipher.doFinal(Base64.decode(b64, Base64.NO_WRAP)), Charsets.UTF_8)
  } catch (e: Exception) {
    null
  }

  // ---------------- YouTube ----------------

  private fun addToYouTube(token: String) {
    val r = getSharedPreferences(PREFS, MODE_PRIVATE).getString("result", null)?.let { JSONObject(it) } ?: return
    val a = r.optJSONArray("winner") ?: return
    val figure = (0 until a.length()).joinToString(" y ") { a.getString(it) }
    toast("Buscando el último partido en YouTube…")
    thread {
      try {
        val last = YouTubeApi.listCompleted(token).firstOrNull()
        runOnUiThread {
          if (last == null) {
            toast("No encontré partidos terminados en el canal")
            return@runOnUiThread
          }
          AlertDialog.Builder(this)
            .setTitle("Agregar a YouTube")
            .setMessage("¿Agregar \"⭐ Figura del partido: $figure\" a la descripción de:\n\n${last.title}?")
            .setPositiveButton("Agregar") { _, _ ->
              thread {
                val msg = try { YouTubeApi.setFigure(token, last.id, figure); "Agregado a la descripción ✓" }
                catch (e: Exception) { "No se pudo: ${e.message}" }
                runOnUiThread { toast(msg) }
              }
            }
            .setNegativeButton("Cancelar", null)
            .show()
        }
      } catch (e: Exception) {
        runOnUiThread { toast("Error de YouTube: ${e.message}") }
      }
    }
  }

  // ---------------- Datos ----------------

  private fun loadSaved() {
    val p = getSharedPreferences(PREFS, MODE_PRIVATE)
    voteId = p.getString("id", "") ?: ""
    closesAt = p.getLong("closes", 0)
    privKey = p.getString("priv", "") ?: ""
    cands = p.getString("cands", null)?.let { s -> JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) } } } ?: emptyList()
  }

  private fun controlState(): MatchState =
    getSharedPreferences(LineupActivity.PREFS, Context.MODE_PRIVATE).getString(LineupActivity.KEY_STATE, null)
      ?.let { MatchState.fromJson(it) } ?: MatchState()

  private fun label(p: Player) = "${p.number} ${p.name}".trim().uppercase()

  private fun playedLabels(state: MatchState) =
    (state.starters + state.subs).filter { it.name.isNotBlank() }.map(::label).distinct()

  private fun rosterLabels(): List<String> {
    val saved = getSharedPreferences(LineupActivity.PREFS, Context.MODE_PRIVATE).getString("roster", null)
    val list = saved?.lines()?.filter { it.isNotBlank() }?.map { line ->
      val parts = line.trim().split(Regex("\\s+"), limit = 2)
      if (parts.size == 2 && parts[0].all { it.isDigit() }) Player(parts[0], parts[1]) else Player("", line.trim())
    } ?: LineupActivity.OFFICIAL_ROSTER
    return list.map(::label)
  }

  private fun numOf(label: String) = label.substringBefore(' ').toIntOrNull() ?: 999

  private fun link() = "$PAGE_URL#$voteId"

  private fun remaining(): String {
    val s = ((closesAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
  }

  private fun share() {
    val text = "⭐ Votá la figura del partido de La Peña (cierra en $VOTE_MINUTES min):\n${link()}"
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
      .putExtra(Intent.EXTRA_TEXT, text), "Compartir votación"))
  }

  private fun copy() {
    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
      .setPrimaryClip(ClipData.newPlainText("Votación", link()))
    toast("Link copiado")
  }

  // ---------------- Vistas ----------------

  private fun title(t: String) = text(t, big = true).apply { setTypeface(typeface, Typeface.BOLD) }

  private fun text(t: String, big: Boolean = false, muted: Boolean = false) = TextView(this).apply {
    text = t
    textSize = if (big) 24f else 16f
    setTextColor(if (muted) Color.parseColor("#BBBBBB") else Color.WHITE)
    setPadding(0, dp(8), 0, dp(4))
    root.addView(this)
  }

  private fun button(t: String, color: String, onClick: () -> Unit) {
    root.addView(Button(this).apply {
      text = t
      setTextColor(Color.WHITE)
      setTypeface(typeface, Typeface.BOLD)
      backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(color))
      setOnClickListener { onClick() }
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply { topMargin = dp(16) })
  }

  private fun smallButton(t: String, onClick: () -> Unit) = Button(this).apply {
    text = t
    gravity = Gravity.CENTER
    setOnClickListener { onClick() }
  }

  private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

  private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
