package com.lapena.live

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.pedro.encoder.input.video.Camera2ApiManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Mira la imagen de la cámara unas pocas veces por segundo y busca jugadores y la pelota con IA
 * (MediaPipe, modelo EfficientDet-Lite0, todo dentro del celular, sin internet).
 *
 * Coordenadas: todo va de 0 a 1 sobre el video tal como sale (0,0 = arriba a la izquierda).
 */
class AutoTracker(
  context: Context,
  /** Cuántas veces por segundo analiza la imagen. Más = sigue mejor, pero calienta más. */
  private val analysesPerSecond: Float = 5f,
  private val onResult: (Result) -> Unit,
) : Camera2ApiManager.ImageCallback {

  companion object {
    private const val TAG = "AutoTracker"
    const val MODEL = "efficientdet_lite0.tflite"

    // Tamaño de la imagen que analiza la IA: se parte en 2 mitades (izquierda/derecha) para que los
    // jugadores lejanos no queden demasiado chicos.
    private const val ANALYSIS_W = 640
    private const val ANALYSIS_H = 360

    // Recorte "lupa": un cuadrado a resolución completa alrededor de donde debería estar la pelota.
    // Ahí la pelota se ve más grande y la IA la encuentra mucho mejor.
    private const val FOCUS = 320

    private const val MIN_PERSON_SCORE = 0.30f
    private const val MIN_BALL_SCORE = 0.30f
    private const val MIN_BALL_SCORE_FOCUS = 0.20f
    // Una "persona" que ocupa más de la mitad del alto está pegada a la cámara (alguien que pasa).
    private const val MAX_PERSON_HEIGHT = 0.5f
  }

  /** Algo detectado: centro y tamaño, de 0 a 1. */
  data class Box(val cx: Float, val cy: Float, val w: Float, val h: Float, val score: Float) {
    val bottom get() = cy + h / 2f
  }

  /** [balls]: posibles pelotas, de la más segura a la menos segura. */
  data class Result(val players: List<Box>, val balls: List<Box>, val atMs: Long) {
    val ball get() = balls.firstOrNull()
  }

  /** Zona de la cancha (de 0 a 1). Lo que queda afuera (banco, público) no se tiene en cuenta. */
  @Volatile var zone: Zone = Zone.FULL

  /** Grados que hay que girar la imagen de la cámara para que quede derecha (0 o 180 en horizontal). */
  @Volatile var rotationDegrees: Int = 0

  /** Dónde mirar con la "lupa" (de 0 a 1, sobre el video): la pelota esperada o el centro del juego. */
  @Volatile var focusX = 0.5f
  @Volatile var focusY = 0.5f

  private val thread = HandlerThread("ai-tracker").apply { start() }
  private val worker = Handler(thread.looper)
  private val busy = AtomicBoolean(false)
  private var lastAnalysisAt = 0L
  private val intervalMs = (1000f / analysesPerSecond).toLong()
  private val pixels = IntArray(ANALYSIS_W * ANALYSIS_H)
  private val focusPixels = IntArray(FOCUS * FOCUS)
  @Volatile private var detector: ObjectDetector? = null
  @Volatile var failed: String? = null
    private set
  @Volatile private var running = true

  init {
    worker.post {
      try {
        val options = ObjectDetector.ObjectDetectorOptions.builder()
          .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
          .setRunningMode(RunningMode.IMAGE)
          .setMaxResults(30)
          .setScoreThreshold(0.25f)
          .setCategoryAllowlist(listOf("person", "sports ball"))
          .build()
        detector = ObjectDetector.createFromOptions(context.applicationContext, options)
      } catch (e: Throwable) {
        Log.e(TAG, "No se pudo cargar la IA", e)
        failed = e.message ?: e.javaClass.simpleName
      }
    }
  }

  /** Llega cada cuadro de la cámara (en otro hilo). Casi todos se descartan: solo usamos ~4 por segundo. */
  override fun onImageAvailable(image: Image) {
    if (!running || detector == null) return
    val now = SystemClock.elapsedRealtime()
    if (now - lastAnalysisAt < intervalMs || busy.get()) return
    lastAnalysisAt = now
    busy.set(true)
    val srcW = image.width
    val srcH = image.height
    val bmp: Bitmap
    var focusBmp: Bitmap? = null
    var focusRect: FloatArray? = null
    try {
      val outH = min(ANALYSIS_H, (ANALYSIS_W.toLong() * srcH / srcW).toInt()).coerceAtLeast(1)
      bmp = convert(image, 0, 0, srcW, srcH, ANALYSIS_W, outH, pixels)
      // Lupa: solo con el celular en horizontal (0° o 180°), que es como se transmite.
      val rot = rotationDegrees
      if ((rot == 0 || rot == 180) && srcW >= FOCUS && srcH >= FOCUS) {
        val fx = if (rot == 180) 1f - focusX else focusX
        val fy = if (rot == 180) 1f - focusY else focusY
        val x0 = (fx * srcW - FOCUS / 2).toInt().coerceIn(0, srcW - FOCUS)
        val y0 = (fy * srcH - FOCUS / 2).toInt().coerceIn(0, srcH - FOCUS)
        focusBmp = convert(image, x0, y0, FOCUS, FOCUS, FOCUS, FOCUS, focusPixels)
        focusRect = floatArrayOf(x0.toFloat() / srcW, y0.toFloat() / srcH, FOCUS.toFloat() / srcW, FOCUS.toFloat() / srcH)
      }
    } catch (e: Throwable) {
      Log.w(TAG, "Cuadro inválido", e)
      busy.set(false)
      return
    }
    worker.post {
      try {
        if (running) analyze(bmp, focusBmp, focusRect, now)
      } catch (e: Throwable) {
        Log.w(TAG, "Falló el análisis", e)
      } finally {
        bmp.recycle()
        focusBmp?.recycle()
        busy.set(false)
      }
    }
  }

  fun release() {
    running = false
    worker.post {
      try { detector?.close() } catch (_: Throwable) {}
      detector = null
      thread.quitSafely()
    }
  }

  // ---------------------------------------------------------------------------

  private fun analyze(bmp: Bitmap, focusBmp: Bitmap?, focusRect: FloatArray?, atMs: Long) {
    val det = detector ?: return
    val w = bmp.width
    val h = bmp.height
    val all = ArrayList<Pair<String, Box>>()
    // Dos mitades con un poco de solapamiento, así nadie queda cortado justo en el medio.
    val tileW = (w * 0.55f).toInt()
    for (left in intArrayOf(0, w - tileW)) {
      val tile = Bitmap.createBitmap(bmp, left, 0, tileW, h)
      val result = det.detect(BitmapImageBuilder(tile).build())
      tile.recycle()
      for (d in result.detections()) {
        val cat = d.categories().firstOrNull() ?: continue
        val r = d.boundingBox()
        val box = Box(
          cx = (left + r.centerX()) / w,
          cy = r.centerY() / h,
          w = r.width() / w,
          h = r.height() / h,
          score = cat.score(),
        )
        all.add(cat.categoryName() to rotate(box))
      }
    }

    // Lupa: solo buscamos la pelota (los jugadores ya salen en la imagen completa).
    val focusBalls = ArrayList<Box>()
    if (focusBmp != null && focusRect != null) {
      val result = det.detect(BitmapImageBuilder(focusBmp).build())
      val fw = focusBmp.width.toFloat()
      val fh = focusBmp.height.toFloat()
      for (d in result.detections()) {
        val cat = d.categories().firstOrNull() ?: continue
        if (cat.categoryName() != "sports ball" || cat.score() < MIN_BALL_SCORE_FOCUS) continue
        val r = d.boundingBox()
        focusBalls.add(rotate(Box(
          cx = focusRect[0] + r.centerX() / fw * focusRect[2],
          cy = focusRect[1] + r.centerY() / fh * focusRect[3],
          w = r.width() / fw * focusRect[2],
          h = r.height() / fh * focusRect[3],
          score = cat.score(),
        )))
      }
    }

    val z = zone
    val players = all.filter { (name, b) ->
      name == "person" && b.score >= MIN_PERSON_SCORE && b.h <= MAX_PERSON_HEIGHT &&
        z.contains(b.cx, min(b.bottom, 1f))
    }.map { it.second }
    val balls = (all.filter { (name, b) -> name == "sports ball" && b.score >= MIN_BALL_SCORE }.map { it.second } + focusBalls)
      .filter { z.contains(it.cx, it.cy) && it.h < 0.15f }
      .sortedByDescending { it.score }
    onResult(Result(dedupe(players), balls, atMs))
  }

  /** Quita duplicados (el mismo jugador visto en las dos mitades). */
  private fun dedupe(boxes: List<Box>): List<Box> {
    val out = ArrayList<Box>()
    for (b in boxes.sortedByDescending { it.score }) {
      if (out.none { o -> kotlin.math.abs(o.cx - b.cx) < max(o.w, b.w) * 0.5f && kotlin.math.abs(o.cy - b.cy) < max(o.h, b.h) * 0.5f }) {
        out.add(b)
      }
    }
    return out
  }

  private fun rotate(b: Box): Box = when (rotationDegrees) {
    180 -> b.copy(cx = 1f - b.cx, cy = 1f - b.cy)
    90 -> Box(cx = 1f - b.cy, cy = b.cx, w = b.h, h = b.w, score = b.score)
    270 -> Box(cx = b.cy, cy = 1f - b.cx, w = b.h, h = b.w, score = b.score)
    else -> b
  }

  /**
   * Pasa una parte del cuadro YUV de la cámara (x0, y0, regionW × regionH, en píxeles) a una imagen
   * en color de outW × outH. Rápido: toma un punto de cada tanto si hay que achicar.
   */
  private fun convert(
    image: Image, x0: Int, y0: Int, regionW: Int, regionH: Int, outW: Int, outH: Int, out: IntArray,
  ): Bitmap {
    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]
    val yBuf = yPlane.buffer
    val uBuf = uPlane.buffer
    val vBuf = vPlane.buffer
    val yRow = yPlane.rowStride
    val yPix = yPlane.pixelStride
    val uvRow = uPlane.rowStride
    val uvPix = uPlane.pixelStride
    val yLimit = yBuf.limit()
    val uLimit = uBuf.limit()
    val vLimit = vBuf.limit()
    var i = 0
    for (oy in 0 until outH) {
      val sy = y0 + oy * regionH / outH
      val yRowStart = sy * yRow
      val uvRowStart = (sy / 2) * uvRow
      for (ox in 0 until outW) {
        val sx = x0 + ox * regionW / outW
        val yi = yRowStart + sx * yPix
        val uvi = uvRowStart + (sx / 2) * uvPix
        val y = if (yi < yLimit) (yBuf.get(yi).toInt() and 0xFF) else 0
        val u = (if (uvi < uLimit) (uBuf.get(uvi).toInt() and 0xFF) else 128) - 128
        val v = (if (uvi < vLimit) (vBuf.get(uvi).toInt() and 0xFF) else 128) - 128
        val r = (y + 1.402f * v).toInt().coerceIn(0, 255)
        val g = (y - 0.344f * u - 0.714f * v).toInt().coerceIn(0, 255)
        val b = (y + 1.772f * u).toInt().coerceIn(0, 255)
        out[i++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
      }
    }
    return Bitmap.createBitmap(out, 0, outW, outW, outH, Bitmap.Config.ARGB_8888)
  }
}

/** Rectángulo de la cancha, de 0 a 1. */
data class Zone(val left: Float, val top: Float, val right: Float, val bottom: Float) {
  fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
  fun isFull() = left <= 0.001f && top <= 0.001f && right >= 0.999f && bottom >= 0.999f

  fun serialize() = "$left,$top,$right,$bottom"

  companion object {
    val FULL = Zone(0f, 0f, 1f, 1f)
    fun parse(s: String?): Zone {
      val p = s?.split(",")?.mapNotNull { it.toFloatOrNull() } ?: return FULL
      if (p.size != 4) return FULL
      val z = Zone(p[0], p[1], p[2], p[3])
      return if (z.right - z.left > 0.05f && z.bottom - z.top > 0.05f) z else FULL
    }
  }
}
