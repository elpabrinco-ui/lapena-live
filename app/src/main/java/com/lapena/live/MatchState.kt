package com.lapena.live

import org.json.JSONObject

/**
 * Estado del marcador. El celular de control lo envía completo en cada cambio,
 * así el celular que transmite siempre queda igual aunque se pierda un mensaje.
 */
data class MatchState(
  val home: String = "LA PEÑA",
  val away: String = "RIVAL",
  val homeGoals: Int = 0,
  val awayGoals: Int = 0,
  val period: Int = 1,              // 1 = 1T, 2 = 2T
  val periodMinutes: Int = 30,      // 30 F7, 20 futsal
  val running: Boolean = false,
  val elapsedMs: Long = 0,          // tiempo del reloj al momento de enviar
  val sentAt: Long = 0,             // hora (epoch ms) del envío
  val visible: Boolean = true,
  val chat: Boolean = true,         // mostrar comentarios de YouTube en el video
) {
  /** Tiempo del reloj ahora, usando la hora del celular (ambos celulares usan hora de red). */
  fun clockNow(now: Long = System.currentTimeMillis()): Long {
    if (!running) return elapsedMs
    return elapsedMs + (now - sentAt).coerceAtLeast(0)
  }

  fun toJson(): String = JSONObject().apply {
    put("v", 1)
    put("home", home); put("away", away)
    put("hg", homeGoals); put("ag", awayGoals)
    put("p", period); put("pm", periodMinutes)
    put("run", running); put("el", elapsedMs); put("at", sentAt)
    put("vis", visible)
    put("chat", chat)
  }.toString()

  companion object {
    fun fromJson(text: String): MatchState? = try {
      val o = JSONObject(text)
      if (o.optInt("v") != 1) null else MatchState(
        home = o.optString("home", "LOCAL"),
        away = o.optString("away", "VISITA"),
        homeGoals = o.optInt("hg"),
        awayGoals = o.optInt("ag"),
        period = o.optInt("p", 1),
        periodMinutes = o.optInt("pm", 30),
        running = o.optBoolean("run"),
        elapsedMs = o.optLong("el"),
        sentAt = o.optLong("at"),
        visible = o.optBoolean("vis", true),
        chat = o.optBoolean("chat", true),
      )
    } catch (e: Exception) {
      null
    }

    fun formatClock(ms: Long): String {
      val totalSec = (ms / 1000).coerceAtLeast(0)
      return String.format("%02d:%02d", totalSec / 60, totalSec % 60)
    }
  }
}
