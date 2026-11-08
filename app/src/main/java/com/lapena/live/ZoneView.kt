package com.lapena.live

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Capa transparente sobre la vista previa para marcar la cancha: se arrastra el dedo de una esquina
 * a la otra. Afuera del rectángulo queda oscuro (eso la IA no lo mira: banco, público, etc.).
 */
class ZoneView @JvmOverloads constructor(
  context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

  /** Proporción del video (ancho / alto), para saber dónde está la imagen dentro de la pantalla. */
  var videoAspect = 16f / 9f

  var zone: Zone = Zone.FULL
    set(value) { field = value; invalidate() }

  /** Se llama cuando el usuario termina de dibujar. */
  var onZoneDrawn: ((Zone) -> Unit)? = null

  private var startX = 0f
  private var startY = 0f
  private val dim = Paint().apply { color = Color.argb(150, 0, 0, 0) }
  private val border = Paint().apply {
    color = Color.rgb(255, 214, 0)
    style = Paint.Style.STROKE
    strokeWidth = 6f
    isAntiAlias = true
  }
  private val text = Paint().apply {
    color = Color.WHITE
    textSize = 44f
    isAntiAlias = true
    textAlign = Paint.Align.CENTER
    setShadowLayer(6f, 0f, 0f, Color.BLACK)
  }

  /** Parte de la pantalla donde se ve el video (con bandas negras si la pantalla es más ancha). */
  private fun videoRect(): RectF {
    val w = width.toFloat()
    val h = height.toFloat()
    return if (w / h > videoAspect) {
      val vw = h * videoAspect
      RectF((w - vw) / 2f, 0f, (w + vw) / 2f, h)
    } else {
      val vh = w / videoAspect
      RectF(0f, (h - vh) / 2f, w, (h + vh) / 2f)
    }
  }

  private fun toVideo(x: Float, y: Float): Pair<Float, Float> {
    val r = videoRect()
    return ((x - r.left) / r.width()).coerceIn(0f, 1f) to ((y - r.top) / r.height()).coerceIn(0f, 1f)
  }

  @SuppressLint("ClickableViewAccessibility")
  override fun onTouchEvent(event: MotionEvent): Boolean {
    val (vx, vy) = toVideo(event.x, event.y)
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        startX = vx; startY = vy
        zone = Zone(vx, vy, vx, vy)
      }
      MotionEvent.ACTION_MOVE -> zone = Zone(min(startX, vx), min(startY, vy), max(startX, vx), max(startY, vy))
      MotionEvent.ACTION_UP -> {
        val z = Zone(min(startX, vx), min(startY, vy), max(startX, vx), max(startY, vy))
        zone = z
        onZoneDrawn?.invoke(z)
      }
    }
    return true
  }

  override fun onDraw(canvas: Canvas) {
    val r = videoRect()
    val z = RectF(
      r.left + zone.left * r.width(), r.top + zone.top * r.height(),
      r.left + zone.right * r.width(), r.top + zone.bottom * r.height(),
    )
    // Oscurece todo lo que queda fuera de la cancha.
    canvas.drawRect(0f, 0f, width.toFloat(), z.top, dim)
    canvas.drawRect(0f, z.bottom, width.toFloat(), height.toFloat(), dim)
    canvas.drawRect(0f, z.top, z.left, z.bottom, dim)
    canvas.drawRect(z.right, z.top, width.toFloat(), z.bottom, dim)
    canvas.drawRect(z, border)
    canvas.drawText("Arrastrá el dedo de una esquina de la cancha a la otra", width / 2f, r.centerY(), text)
  }
}
