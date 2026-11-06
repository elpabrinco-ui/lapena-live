package com.lapena.live

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Conecta los dos celulares por internet usando ntfy.sh (servicio gratuito, sin cuenta).
 * Cada partido usa un "canal" privado según el código que muestra la app.
 * Funciona con datos móviles: los celulares no necesitan estar en el mismo wifi.
 */
object ScoreSync {
  private const val SERVER = "https://ntfy.sh/"
  private const val PREFS = "lapena_live"
  private const val KEY_CODE = "match_code"
  private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

  fun topic(code: String) = "lapenalive-" + code.trim().uppercase()

  /** Código guardado; si no existe, crea uno nuevo de 6 caracteres. */
  fun savedCode(context: Context, createIfMissing: Boolean): String {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    var code = prefs.getString(KEY_CODE, "") ?: ""
    if (code.isEmpty() && createIfMissing) {
      code = (1..6).map { ALPHABET.random() }.joinToString("")
      prefs.edit().putString(KEY_CODE, code).apply()
    }
    return code
  }

  fun saveCode(context: Context, code: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
      .putString(KEY_CODE, code.trim().uppercase()).apply()
  }

  /** Envía el estado. El resultado (true/false) llega en otro hilo. */
  fun publish(code: String, state: MatchState, onResult: (Boolean) -> Unit) {
    thread(name = "ntfy-publish") {
      val ok = try {
        val conn = URL(SERVER + topic(code)).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        conn.outputStream.use { it.write(state.toJson().toByteArray(Charsets.UTF_8)) }
        val code200 = conn.responseCode in 200..299
        conn.disconnect()
        code200
      } catch (e: Exception) {
        false
      }
      onResult(ok)
    }
  }

  /**
   * Escucha el canal sin parar (se reconecta solo). Al conectarse recibe el último
   * estado de las últimas horas, así el marcador aparece aunque el control haya
   * empezado antes.
   */
  class Subscriber(
    private val code: String,
    private val onState: (MatchState) -> Unit,
    private val onConnection: (Boolean) -> Unit,
  ) {
    @Volatile private var running = true
    @Volatile private var conn: HttpURLConnection? = null

    fun start() {
      thread(name = "ntfy-subscribe", isDaemon = true) {
        var first = true
        while (running) {
          try {
            val since = if (first) "6h" else "30s"
            val c = URL("${SERVER}${topic(code)}/json?since=$since").openConnection() as HttpURLConnection
            conn = c
            c.connectTimeout = 10000
            c.readTimeout = 90000   // ntfy manda "keepalive" cada ~45 s
            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { reader ->
              onConnection(true)
              first = false
              while (running) {
                val line = reader.readLine() ?: break
                val event = try { JSONObject(line) } catch (e: Exception) { continue }
                if (event.optString("event") != "message") continue
                MatchState.fromJson(event.optString("message"))?.let(onState)
              }
            }
          } catch (e: Exception) {
            // sin señal o se cortó: reintenta
          }
          if (running) {
            onConnection(false)
            try { Thread.sleep(3000) } catch (e: InterruptedException) { }
          }
        }
      }
    }

    fun stop() {
      running = false
      try { conn?.disconnect() } catch (e: Exception) { }
    }
  }
}
