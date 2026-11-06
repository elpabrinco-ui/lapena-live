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
    const val CHAT_W = 400

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
  private val crestPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

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
      c.drawBitmap(it, null, RectF(pad, pad, crestW - pad, h - pad), crestPaint)
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
      // Pincel propio: sin la transparencia de la franja oscura
      c.drawBitmap(it, null, RectF(left, top, left + crestSize, top + crestSize), crestPaint)
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
      // "2 - 1" fijo en el centro; cada nombre pegado a su lado.
      val score = Paint(title).apply { textSize = videoH * 0.065f }
      val cx = videoW / 2f
      val cy = videoH * 0.87f
      val result = "${state.homeGoals} - ${state.awayGoals}"
      val gap = videoH * 0.04f
      val resultHalf = score.measureText(result) / 2
      val nameMax = cx - resultHalf - gap - videoW * 0.04f
      val namePaintEnd = TextPaint(score)
      val home = TextUtils.ellipsize(state.home.uppercase(), namePaintEnd, nameMax, TextUtils.TruncateAt.END).toString()
      val away = TextUtils.ellipsize(state.away.uppercase(), namePaintEnd, nameMax, TextUtils.TruncateAt.END).toString()

      drawCentered(c, "FINAL", cx, videoH * 0.77f, sub)
      drawCentered(c, result, cx, cy, score)
      drawCentered(c, home, cx - resultHalf - gap - score.measureText(home) / 2, cy, score)
      drawCentered(c, away, cx + resultHalf + gap + score.measureText(away) / 2, cy, score)
    }
    return bmp
  }

  // ---------------- Bienvenida ----------------

  /**
   * Pantalla de bienvenida (1280x720): a la izquierda escudo, partido y suplentes;
   * a la derecha media cancha con los titulares en su posición (atacando hacia arriba).
   */
  fun renderWelcome(state: MatchState, videoW: Int, videoH: Int): Bitmap {
    val bmp = Bitmap.createBitmap(videoW, videoH, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(MAROON)
    val W = videoW.toFloat()
    val H = videoH.toFloat()

    // ----- Columna izquierda -----
    val leftCx = W * 0.25f
    val crestSize = H * 0.30f
    crest?.let {
      c.drawBitmap(it, null,
        RectF(leftCx - crestSize / 2, H * 0.05f, leftCx + crestSize / 2, H * 0.05f + crestSize), crestPaint)
    }
    val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
      textAlign = Paint.Align.CENTER
      textSize = H * 0.055f
    }
    val small = TextPaint(big).apply {
      color = Color.parseColor("#FFC9C9")
      typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
      textSize = H * 0.033f
    }
    val colW = W * 0.44f
    fun fit(text: String, p: TextPaint) =
      TextUtils.ellipsize(text, p, colW, TextUtils.TruncateAt.END).toString()

    drawCentered(c, fit(state.home.uppercase(), big), leftCx, H * 0.43f, big)
    drawCentered(c, "vs", leftCx, H * 0.50f, small)
    drawCentered(c, fit(state.away.uppercase(), big), leftCx, H * 0.57f, big)
    val pill = TextPaint(small).apply {
      color = Color.WHITE
      typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    val pillText = "EN INSTANTES COMIENZA EL PARTIDO"
    val pillW = pill.measureText(pillText) + H * 0.05f
    fill.color = Color.parseColor("#55000000")
    c.drawRoundRect(RectF(leftCx - pillW / 2, H * 0.625f, leftCx + pillW / 2, H * 0.695f), H * 0.035f, H * 0.035f, fill)
    drawCentered(c, pillText, leftCx, H * 0.66f, pill)

    // Suplentes
    if (state.subs.isNotEmpty()) {
      drawCentered(c, "SUPLENTES", leftCx, H * 0.77f, small)
      val subsPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = H * 0.032f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
      }
      val text = state.subs.joinToString("  ·  ") { listOf(it.number, it.name).filter { s -> s.isNotEmpty() }.joinToString(" ") }
      val layout = android.text.StaticLayout.Builder.obtain(text, 0, text.length, subsPaint, colW.toInt())
        .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
        .setMaxLines(3)
        .setEllipsize(TextUtils.TruncateAt.END)
        .build()
      c.save()
      c.translate(leftCx - colW / 2, H * 0.80f)
      layout.draw(c)
      c.restore()
    }

    // ----- Cancha (derecha) -----
    val pitch = RectF(W * 0.52f, H * 0.06f, W * 0.96f, H * 0.94f)
    val stripes = 8
    val stripeH = pitch.height() / stripes
    for (i in 0 until stripes) {
      fill.color = Color.parseColor(if (i % 2 == 0) "#2E7D32" else "#33873A")
      c.drawRect(pitch.left, pitch.top + i * stripeH, pitch.right, pitch.top + (i + 1) * stripeH, fill)
    }
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.parseColor("#DDFFFFFF")
      style = Paint.Style.STROKE
      strokeWidth = H * 0.005f
    }
    c.drawRect(pitch, line)
    // Mitad de cancha arriba (círculo central cortado) y arco propio abajo
    c.drawArc(RectF(pitch.centerX() - pitch.width() * 0.16f, pitch.top - pitch.width() * 0.16f,
      pitch.centerX() + pitch.width() * 0.16f, pitch.top + pitch.width() * 0.16f), 0f, 180f, false, line)
    val areaW = pitch.width() * 0.55f
    val areaH = pitch.height() * 0.20f
    c.drawRect(pitch.centerX() - areaW / 2, pitch.bottom - areaH, pitch.centerX() + areaW / 2, pitch.bottom, line)
    val goalW = pitch.width() * 0.22f
    c.drawRect(pitch.centerX() - goalW / 2, pitch.bottom - H * 0.015f, pitch.centerX() + goalW / 2, pitch.bottom + H * 0.015f, line)

    // Posiciones: arquero abajo; líneas repartidas hacia arriba
    val outfield = (state.starters.size - 1).coerceAtLeast(0)
    val lines = MatchState.parseFormation(state.formation, outfield)
    val spots = ArrayList<Pair<Float, Float>>()
    spots.add(pitch.centerX() to pitch.bottom - pitch.height() * 0.10f)
    val topY = pitch.top + pitch.height() * 0.16f
    val bottomY = pitch.bottom - pitch.height() * 0.34f
    lines.forEachIndexed { li, count ->
      val y = if (lines.size == 1) (topY + bottomY) / 2
        else bottomY - (bottomY - topY) * li / (lines.size - 1)
      for (k in 0 until count) {
        val x = pitch.left + pitch.width() * (k + 1) / (count + 1)
        spots.add(x to y)
      }
    }

    val r = H * 0.038f
    val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
      textAlign = Paint.Align.CENTER
      textSize = r * 1.0f
    }
    val namePaintP = TextPaint(numPaint).apply { textSize = H * 0.028f }
    val nameBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#AA000000") }
    val maxNameW = pitch.width() / 3.4f
    state.starters.forEachIndexed { i, p ->
      val (x, y) = spots.getOrNull(i) ?: return@forEachIndexed
      fill.color = if (i == 0) Color.parseColor("#141414") else MAROON
      c.drawCircle(x, y, r, fill)
      c.drawCircle(x, y, r, Paint(line).apply { color = Color.WHITE })
      if (p.number.isNotEmpty()) drawCentered(c, p.number, x, y, numPaint)
      if (p.name.isNotEmpty()) {
        val n = TextUtils.ellipsize(p.name, namePaintP, maxNameW, TextUtils.TruncateAt.END).toString()
        val tw = namePaintP.measureText(n) + H * 0.02f
        val ny = y + r + H * 0.032f
        c.drawRoundRect(RectF(x - tw / 2, ny - H * 0.024f, x + tw / 2, ny + H * 0.024f), H * 0.012f, H * 0.012f, nameBg)
        drawCentered(c, n, x, ny, namePaintP)
      }
    }
    return bmp
  }

  // ---------------- Chat ----------------

  private val chatAuthorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#FFB3B3")
    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    textSize = 17 * S
  }
  private val chatTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE
    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    textSize = 19 * S
  }

  /**
   * Últimos comentarios, uno debajo del otro (el más nuevo abajo).
   * Ancho fijo [CHAT_W] en px del video; el alto depende de los mensajes.
   */
  fun renderChat(messages: List<YouTubeApi.ChatMessage>): Bitmap? {
    if (messages.isEmpty()) return null
    val w = (CHAT_W * S).toInt()
    val pad = 10 * S
    val gap = 6 * S
    val textW = (w - pad * 2).toInt()

    val layouts = messages.map { m ->
      val author = TextUtils.ellipsize(m.author, chatAuthorPaint, textW.toFloat(), TextUtils.TruncateAt.END).toString()
      val body = android.text.StaticLayout.Builder
        .obtain(m.text, 0, m.text.length, chatTextPaint, textW)
        .setMaxLines(2)
        .setEllipsize(TextUtils.TruncateAt.END)
        .build()
      author to body
    }
    val authorH = chatAuthorPaint.fontSpacing
    val boxHeights = layouts.map { (_, body) -> pad * 2 + authorH + body.height }
    val h = (boxHeights.sum() + gap * (layouts.size - 1)).toInt()

    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    var y = 0f
    layouts.forEachIndexed { i, (author, body) ->
      val boxH = boxHeights[i]
      fill.color = DARK
      c.drawRoundRect(RectF(0f, y, w.toFloat(), y + boxH), 10 * S, 10 * S, fill)
      fill.color = MAROON
      c.drawRect(RectF(0f, y + 8 * S, 5 * S, y + boxH - 8 * S), fill) // barrita bordó
      c.drawText(author, pad, y + pad - chatAuthorPaint.ascent(), chatAuthorPaint)
      c.save()
      c.translate(pad, y + pad + authorH)
      body.draw(c)
      c.restore()
      y += boxH + gap
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
