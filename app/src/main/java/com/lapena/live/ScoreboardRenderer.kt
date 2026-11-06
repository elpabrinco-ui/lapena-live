package com.lapena.live

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.text.TextPaint

/**
 * Dibuja el marcador como imagen: [escudo][LOCAL][2 - 1][VISITA][2T | 12:34]
 * Cada nombre ocupa el ancho que necesita (hasta un máximo). Si es muy largo,
 * la letra se achica un poco; solo si no alcanza, se corta con "…".
 * Se dibuja al doble de tamaño para que se vea nítido en el video.
 */
class ScoreboardRenderer(context: Context) {

  companion object {
    // Tamaños "lógicos" en píxeles del video 1280x720
    const val HEIGHT = 64
    const val S = 2f // factor de nitidez

    private const val CREST_W = 64f
    private const val NAME_MIN_W = 100f
    private const val NAME_MAX_W = 230f
    private const val NAME_PAD = 14f
    private const val NAME_SIZE = 24f
    private const val NAME_MIN_SIZE = 18f
    private const val SCORE_W = 86f
    private const val PERIOD_W = 48f
    private const val CLOCK_W = 100f

    private val MAROON = Color.parseColor("#7A1F2B")
    private val DARK = Color.parseColor("#E6141414")
    private val SEPARATOR = Color.parseColor("#55FFFFFF")
  }

  private val crest: Bitmap? =
    BitmapFactory.decodeResource(context.resources, R.drawable.crest)

  private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE
    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    textAlign = Paint.Align.CENTER
  }
  private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = MAROON
    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    textSize = 32 * S
    textAlign = Paint.Align.CENTER
  }
  private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE
    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    textSize = 26 * S
    textAlign = Paint.Align.CENTER
  }
  private val periodPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#FFC9C9")
    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    textSize = 26 * S
    textAlign = Paint.Align.CENTER
  }
  private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

  /** Nombre ya preparado: texto, tamaño de letra y ancho de su caja (en px reales). */
  private data class NameLayout(val text: String, val size: Float, val boxW: Float)

  private fun layoutName(raw: String): NameLayout {
    val text = raw.trim().uppercase().ifEmpty { "-" }
    val maxText = (NAME_MAX_W - NAME_PAD * 2) * S
    // 1) Probar con la letra normal; 2) achicar hasta el mínimo; 3) cortar con "…"
    var size = NAME_SIZE
    namePaint.textSize = size * S
    while (namePaint.measureText(text) > maxText && size > NAME_MIN_SIZE) {
      size -= 1f
      namePaint.textSize = size * S
    }
    val finalText =
      TextUtils.ellipsize(text, namePaint, maxText, TextUtils.TruncateAt.END).toString()
    val textW = namePaint.measureText(finalText)
    val boxW = (textW + NAME_PAD * 2 * S).coerceIn(NAME_MIN_W * S, NAME_MAX_W * S)
    return NameLayout(finalText, size, boxW)
  }

  fun render(state: MatchState, clockMs: Long): Bitmap {
    val home = layoutName(state.home)
    val away = layoutName(state.away)

    val h = (HEIGHT * S).toInt()
    val crestW = CREST_W * S
    val scoreW = SCORE_W * S
    val periodW = PERIOD_W * S
    val clockW = CLOCK_W * S
    val w = (crestW + home.boxW + scoreW + away.boxW + periodW + clockW).toInt()

    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val radius = 10 * S

    // Fondo bordó
    fill.color = MAROON
    c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, fill)

    // Escudo
    crest?.let {
      val pad = 4 * S
      c.drawBitmap(it, null, RectF(pad, pad, crestW - pad, h - pad), fill)
    }

    var x = crestW
    drawName(c, home, x, h)
    x += home.boxW

    // Goles (caja blanca)
    fill.color = Color.WHITE
    c.drawRect(RectF(x, 8 * S, x + scoreW, h - 8 * S), fill)
    drawCentered(c, "${state.homeGoals} - ${state.awayGoals}", x + scoreW / 2, h / 2f, scorePaint)
    x += scoreW

    drawName(c, away, x, h)
    x += away.boxW

    // Tiempo + reloj (caja oscura), separados por una línea fina
    fill.color = DARK
    c.drawRoundRect(RectF(x, 0f, w.toFloat(), h.toFloat()), radius, radius, fill)
    c.drawRect(RectF(x, 0f, x + radius, h.toFloat()), fill) // esquina izquierda recta
    drawCentered(c, "${state.period}T", x + periodW / 2, h / 2f, periodPaint)
    fill.color = SEPARATOR
    c.drawRect(RectF(x + periodW - 1 * S, 14 * S, x + periodW + 1 * S, h - 14 * S), fill)
    drawCentered(c, MatchState.formatClock(clockMs), x + periodW + clockW / 2, h / 2f, clockPaint)

    return bmp
  }

  /**
   * Pantalla de cierre a tamaño completo del video (1280x720):
   * escudo, "¡Gracias por acompañar a La Peña!" y el resultado final.
   */
  fun renderEndCard(state: MatchState?, videoW: Int, videoH: Int): Bitmap {
    val bmp = Bitmap.createBitmap(videoW, videoH, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(MAROON)

    // Franja oscura abajo para el resultado
    fill.color = Color.parseColor("#33000000")
    c.drawRect(0f, videoH * 0.70f, videoW.toFloat(), videoH.toFloat(), fill)

    val crestSize = videoH * 0.36f
    crest?.let {
      val left = (videoW - crestSize) / 2
      val top = videoH * 0.07f
      c.drawBitmap(it, null, RectF(left, top, left + crestSize, top + crestSize), fill)
    }

    val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
      textAlign = Paint.Align.CENTER
      textSize = videoH * 0.065f
    }
    drawCentered(c, "¡GRACIAS POR ACOMPAÑAR A LA PEÑA!", videoW / 2f, videoH * 0.55f, title)

    val sub = Paint(title).apply {
      color = Color.parseColor("#FFC9C9")
      typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
      textSize = videoH * 0.04f
    }
    drawCentered(c, "Nos vemos en el próximo partido", videoW / 2f, videoH * 0.63f, sub)

    if (state != null) {
      val score = Paint(title).apply { textSize = videoH * 0.065f }
      val line = "${state.home.uppercase()}   ${state.homeGoals} - ${state.awayGoals}   ${state.away.uppercase()}"
      val fitted = TextUtils.ellipsize(line, TextPaint(score), videoW * 0.92f, TextUtils.TruncateAt.END).toString()
      drawCentered(c, "FINAL", videoW / 2f, videoH * 0.77f, sub)
      drawCentered(c, fitted, videoW / 2f, videoH * 0.87f, score)
    }
    return bmp
  }

  private fun drawName(c: Canvas, name: NameLayout, x: Float, h: Int) {
    namePaint.textSize = name.size * S
    drawCentered(c, name.text, x + name.boxW / 2, h / 2f, namePaint)
  }

  private fun drawCentered(c: Canvas, text: String, cx: Float, cy: Float, paint: Paint) {
    val bounds = Rect()
    paint.getTextBounds("0A", 0, 2, bounds)
    c.drawText(text, cx, cy - bounds.exactCenterY(), paint)
  }
}
