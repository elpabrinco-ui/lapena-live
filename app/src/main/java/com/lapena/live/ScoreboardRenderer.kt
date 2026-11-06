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
 * Dibuja el marcador como imagen: [escudo][LOCAL][2 - 1][VISITA][1T 12:34]
 * Se dibuja al doble de tamaño para que se vea nítido en el video.
 */
class ScoreboardRenderer(context: Context) {

  companion object {
    // Tamaño "lógico" en píxeles del video 1280x720
    const val WIDTH = 540
    const val HEIGHT = 64
    private const val S = 2f // factor de nitidez

    private val MAROON = Color.parseColor("#7A1F2B")
    private val DARK = Color.parseColor("#E6141414")
    private val SCORE_BG = Color.WHITE
  }

  private val crest: Bitmap? =
    BitmapFactory.decodeResource(context.resources, R.drawable.crest)

  private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE
    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    textSize = 24 * S
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
    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    textSize = 26 * S
    textAlign = Paint.Align.CENTER
  }
  private val periodPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#FFC9C9")
    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    textSize = 18 * S
    textAlign = Paint.Align.CENTER
  }
  private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

  fun render(state: MatchState, clockMs: Long): Bitmap {
    val w = (WIDTH * S).toInt()
    val h = (HEIGHT * S).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)

    val crestW = 64 * S
    val nameW = 135 * S
    val scoreW = 86 * S
    val clockW = w - crestW - nameW * 2 - scoreW

    // Fondo general redondeado
    fill.color = MAROON
    c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 10 * S, 10 * S, fill)

    // Escudo
    crest?.let {
      val pad = 4 * S
      c.drawBitmap(it, null, RectF(pad, pad, crestW - pad, h - pad), fill)
    }

    var x = crestW
    drawName(c, state.home, x, nameW, h)
    x += nameW

    // Goles (caja blanca)
    fill.color = SCORE_BG
    c.drawRect(RectF(x, 8 * S, x + scoreW, h - 8 * S), fill)
    drawCentered(c, "${state.homeGoals} - ${state.awayGoals}", x + scoreW / 2, h / 2f, scorePaint)
    x += scoreW

    drawName(c, state.away, x, nameW, h)
    x += nameW

    // Reloj (caja oscura)
    fill.color = DARK
    c.drawRoundRect(RectF(x, 0f, w.toFloat(), h.toFloat()), 10 * S, 10 * S, fill)
    c.drawRect(RectF(x, 0f, x + 12 * S, h.toFloat()), fill)
    val periodLabel = "${state.period}T"
    drawCentered(c, periodLabel, x + 26 * S, h / 2f, periodPaint)
    drawCentered(c, MatchState.formatClock(clockMs), x + 26 * S + (clockW - 26 * S) / 2, h / 2f, clockPaint)

    return bmp
  }

  private fun drawName(c: Canvas, name: String, x: Float, width: Float, h: Int) {
    val text = TextUtils.ellipsize(name.uppercase(), namePaint, width - 12 * S, TextUtils.TruncateAt.END).toString()
    drawCentered(c, text, x + width / 2, h / 2f, namePaint)
  }

  private fun drawCentered(c: Canvas, text: String, cx: Float, cy: Float, paint: Paint) {
    val bounds = Rect()
    paint.getTextBounds("0A", 0, 2, bounds)
    c.drawText(text, cx, cy - bounds.exactCenterY(), paint)
  }
}
