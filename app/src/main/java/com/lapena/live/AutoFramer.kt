package com.lapena.live

import android.os.SystemClock
import com.pedro.encoder.input.gl.render.filters.CropFilterRender
import com.pedro.library.view.GlInterface
import kotlin.math.abs
import kotlin.math.sign

/**
 * "El camarógrafo": decide a dónde mirar con lo que encuentra la IA y mueve el encuadre de a poco,
 * como lo haría una persona (sin saltos).
 *
 * - Sin motor: recorta y hace zoom dentro de la imagen (seguimiento digital).
 * - Con motor: además le pide al motor que gire para que la jugada quede en el centro.
 *
 * Todo se usa desde el hilo principal, salvo [onDetections], que puede llegar de otro hilo.
 */
class AutoFramer {

  companion object {
    /** Más zoom digital que esto se ve pixelado. */
    const val MAX_ZOOM = 2.0f

    // Suavidad del movimiento (por cuadro, a ~30 por segundo). Más chico = más suave y más lento.
    private const val PAN_EASE = 0.06f
    private const val ZOOM_EASE = 0.025f
    // No se mueve por cambios chiquitos (evita que "tiemble").
    private const val PAN_DEADZONE = 0.025f
    private const val ZOOM_DEADZONE = 0.04f
    // Si no ve a nadie durante este tiempo, abre el plano de a poco.
    private const val LOST_AFTER_MS = 2500L

    // Motor: zona muerta alrededor del centro y velocidad máxima (pasos por segundo del firmware).
    private const val MOTOR_DEADZONE = 0.10f
    const val MOTOR_MAX_SPEED = 700
  }

  /** Filtro de recorte: tiene que ir PRIMERO en la lista, así el marcador y el chat no se recortan. */
  var cropFilter: CropFilterRender? = null
    private set

  @Volatile var enabled = false
    private set

  /** Mientras se marca la cancha se muestra la imagen completa. */
  var showFullFrame = false

  // Objetivo (lo que quiere mostrar) y estado actual (lo que muestra), de 0 a 1.
  @Volatile private var targetX = 0.5f
  @Volatile private var targetY = 0.5f
  @Volatile private var targetW = 1f
  @Volatile private var lastSeenAt = 0L
  private var curX = 0.5f
  private var curY = 0.5f
  private var curW = 1f
  private var lastApplied = floatArrayOf(-1f, -1f, -1f)

  /** Último pedido para el motor (-MOTOR_MAX_SPEED..MOTOR_MAX_SPEED). */
  @Volatile var motorSpeed = 0
    private set
  @Volatile var playersSeen = 0
    private set
  @Volatile var ballSeen = false
    private set

  fun attach(gl: GlInterface) {
    val crop = CropFilterRender()
    cropFilter = crop
    gl.addFilter(crop)
    lastApplied = floatArrayOf(-1f, -1f, -1f)
    apply(force = true)
  }

  fun setEnabled(on: Boolean) {
    enabled = on
    if (!on) {
      targetX = 0.5f; targetY = 0.5f; targetW = 1f
      motorSpeed = 0
      playersSeen = 0
      ballSeen = false
    } else {
      lastSeenAt = SystemClock.elapsedRealtime()
    }
  }

  /** Resultado de la IA (llega ~4 veces por segundo). */
  fun onDetections(r: AutoTracker.Result) {
    if (!enabled) return
    playersSeen = r.players.size
    ballSeen = r.ball != null
    if (r.players.size < 2 && r.ball == null) {
      if (SystemClock.elapsedRealtime() - lastSeenAt > LOST_AFTER_MS) {
        targetW = 1f; targetX = 0.5f; targetY = 0.5f
      }
      motorSpeed = 0
      return
    }
    lastSeenAt = SystemClock.elapsedRealtime()

    var x: Float
    var y: Float
    var w: Float
    if (r.players.size >= 2) {
      val xs = r.players.map { it.cx }.sorted()
      val ys = r.players.map { it.cy }.sorted()
      val median = xs[xs.size / 2]
      // Ancho donde está "casi todo el juego" (sin el 10% de cada punta, por ejemplo el arquero lejos).
      val lo = xs[(xs.size * 0.1f).toInt()]
      val hi = xs[((xs.size - 1) * 0.9f).toInt().coerceAtLeast(0)]
      x = median
      y = ys[ys.size / 2]
      w = (hi - lo) + 0.30f
      r.ball?.let { b ->
        // La pelota pesa mucho, pero sin dejar afuera a los jugadores.
        x = 0.55f * b.cx + 0.45f * median
        y = 0.5f * b.cy + 0.5f * y
      }
    } else {
      val b = r.ball!!
      x = b.cx; y = b.cy; w = 0.6f
    }
    targetX = x.coerceIn(0f, 1f)
    targetY = y.coerceIn(0f, 1f)
    targetW = w.coerceIn(1f / MAX_ZOOM, 1f)

    // Motor: gira si el juego se va mucho para un costado del sensor.
    val err = targetX - 0.5f
    motorSpeed = if (abs(err) < MOTOR_DEADZONE) 0 else {
      val k = ((abs(err) - MOTOR_DEADZONE) / (0.5f - MOTOR_DEADZONE)).coerceIn(0f, 1f)
      (sign(err) * (0.25f + 0.75f * k) * MOTOR_MAX_SPEED).toInt()
    }
  }

  /** Llamar ~30 veces por segundo: acerca el encuadre al objetivo, de a poco. */
  fun tick() {
    val tx = if (enabled && !showFullFrame) targetX else 0.5f
    val ty = if (enabled && !showFullFrame) targetY else 0.5f
    val tw = if (enabled && !showFullFrame) targetW else 1f
    if (showFullFrame || !enabled) {
      // Volver rápido a la imagen completa.
      curX += (tx - curX) * 0.25f; curY += (ty - curY) * 0.25f; curW += (tw - curW) * 0.25f
    } else {
      if (abs(tx - curX) > PAN_DEADZONE * curW) curX += (tx - curX) * PAN_EASE
      if (abs(ty - curY) > PAN_DEADZONE * curW) curY += (ty - curY) * PAN_EASE * 0.6f
      if (abs(tw - curW) > ZOOM_DEADZONE) curW += (tw - curW) * ZOOM_EASE
    }
    apply(force = false)
  }

  /** Zoom actual (1x = imagen completa). */
  fun currentZoom() = 1f / curW

  private fun apply(force: Boolean) {
    val crop = cropFilter ?: return
    val w = curW.coerceIn(1f / MAX_ZOOM, 1f)
    // Misma proporción en alto y ancho, así no se deforma.
    val left = (curX - w / 2f).coerceIn(0f, 1f - w)
    val top = (curY - w / 2f).coerceIn(0f, 1f - w)
    val changed = abs(left - lastApplied[0]) > 0.0005f || abs(top - lastApplied[1]) > 0.0005f ||
      abs(w - lastApplied[2]) > 0.0005f
    if (!force && !changed) return
    lastApplied = floatArrayOf(left, top, w)
    crop.setCropArea(left * 100f, top * 100f, w * 100f, w * 100f)
  }
}
