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
    // Cuánto tiempo "recuerda" la pelota después de dejar de verla.
    private const val BALL_MEMORY_MS = 1200L
    // "Tumulto": la pelota desaparece entre varios jugadores juntos. La cámara se queda en ese grupo,
    // abre un poco el plano y espera a que la pelota salga.
    private const val CROWD_MIN_PLAYERS = 3
    private const val CROWD_RADIUS_X = 0.14f
    private const val CROWD_RADIUS_Y = 0.22f
    private const val HOLD_MAX_MS = 6000L

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
      ballAt = 0L; ballVx = 0f; ballVy = 0f
      holding = false
      motorSpeed = 0
      playersSeen = 0
      ballSeen = false
    } else {
      lastSeenAt = SystemClock.elapsedRealtime()
    }
  }

  /** Qué sigue: la pelota (con los jugadores de respaldo) o solo el grupo de jugadores. */
  enum class Mode { BALL, PLAYERS }
  @Volatile var mode = Mode.BALL

  // Seguimiento de la pelota: última posición confirmada y velocidad (en "pantallas por segundo").
  private var ballX = 0.5f
  private var ballY = 0.5f
  private var ballVx = 0f
  private var ballVy = 0f
  private var ballAt = 0L

  // Espera en un tumulto: punto que se mira (los pies del grupo) y desde cuándo.
  private var holdX = 0.5f
  private var holdY = 0.5f
  private var holdSince = 0L
  /** true mientras espera a que la pelota salga de un grupo de jugadores. */
  @Volatile var holding = false
    private set

  /** Dónde debería mirar la "lupa" de la IA en el próximo análisis. */
  @Volatile var focusX = 0.5f
    private set
  @Volatile var focusY = 0.5f
    private set

  /** Resultado de la IA (llega ~5 veces por segundo). */
  fun onDetections(r: AutoTracker.Result) {
    if (!enabled) return
    val now = SystemClock.elapsedRealtime()
    playersSeen = r.players.size

    // --- Jugadores: centro y ancho del juego ---
    var px = Float.NaN
    var py = Float.NaN
    var spread = 0f
    if (r.players.size >= 2) {
      val xs = r.players.map { it.cx }.sorted()
      val ys = r.players.map { it.cy }.sorted()
      px = xs[xs.size / 2]
      py = ys[ys.size / 2]
      // Ancho donde está "casi todo el juego" (sin el 10% de cada punta, por ejemplo el arquero lejos).
      val lo = xs[(xs.size * 0.1f).toInt()]
      val hi = xs[((xs.size - 1) * 0.9f).toInt().coerceAtLeast(0)]
      spread = hi - lo
    }

    // --- Pelota: elegir la detección creíble y actualizar posición y velocidad ---
    val ball = pickBall(r, now)
    ballSeen = ball != null
    if (ball != null) {
      val dt = (now - ballAt) / 1000f
      if (ballAt > 0 && dt in 0.05f..1.0f) {
        // Velocidad suavizada (una sola medición puede ser ruido).
        ballVx = 0.6f * ballVx + 0.4f * ((ball.cx - ballX) / dt)
        ballVy = 0.6f * ballVy + 0.4f * ((ball.cy - ballY) / dt)
      } else {
        ballVx = 0f; ballVy = 0f
      }
      ballX = ball.cx; ballY = ball.cy; ballAt = now
      holding = false
    }
    var ballAge = now - ballAt

    // --- Tumulto: la pelota se perdió entre varios jugadores juntos ---
    if (mode != Mode.BALL) holding = false
    if (mode == Mode.BALL && ball == null && !holding && ballAt > 0 && ballAge < BALL_MEMORY_MS &&
      crowdAt(r, ballX, ballY).size >= CROWD_MIN_PLAYERS
    ) {
      holding = true
      holdX = ballX; holdY = ballY; holdSince = now
      ballVx = 0f; ballVy = 0f  // ahí adentro no sigue en línea recta: no "imaginar" que se va
    }
    var crowd: List<AutoTracker.Box> = emptyList()
    if (holding) {
      crowd = crowdAt(r, holdX, holdY)
      if (crowd.size < 2 || now - holdSince > HOLD_MAX_MS) {
        // El grupo se abrió o pasó mucho tiempo: volver a seguir a los jugadores.
        holding = false
        ballAt = 0L
        ballAge = now
      } else {
        // El grupo se mueve despacio: lo acompañamos.
        holdX += (crowd.map { it.cx }.average().toFloat() - holdX) * 0.5f
        holdY += (crowd.map { it.bottom }.average().toFloat() - holdY) * 0.5f
      }
    }
    // Si la dejó de ver hace poco, la "imagina" donde debería estar (sigue su recorrido un ratito).
    val ballRecent = ballAt > 0 && ballAge < BALL_MEMORY_MS
    val predT = (ballAge / 1000f).coerceAtMost(0.5f)
    val bx = (ballX + ballVx * predT).coerceIn(0f, 1f)
    val by = (ballY + ballVy * predT).coerceIn(0f, 1f)

    // --- Decidir el encuadre ---
    var x: Float
    var y: Float
    var w: Float
    if (holding) {
      x = holdX; y = holdY
      val extent = crowd.maxOf { it.cx } - crowd.minOf { it.cx }
      // Un poco más abierto que el grupo, y se sigue abriendo de a poco mientras espera.
      val waited = ((now - holdSince) / HOLD_MAX_MS.toFloat()).coerceIn(0f, 1f)
      w = maxOf(0.62f, extent + 0.38f) + waited * 0.15f
      focusX = holdX; focusY = holdY
    } else if (mode == Mode.BALL && ballRecent) {
      // La pelota manda. Los jugadores tiran un poco para que se vea la jugada, no solo la pelota.
      val pull = if (px.isNaN()) 0f else 0.2f
      x = bx * (1 - pull) + (if (px.isNaN()) 0f else px * pull)
      y = by * (1 - pull) + (if (py.isNaN()) 0f else py * pull)
      // Más abierto si la pelota va rápido o si hace rato que no la ve (para no perderla).
      val speed = kotlin.math.sqrt(ballVx * ballVx + ballVy * ballVy)
      w = 0.55f + (speed * 0.25f).coerceAtMost(0.2f) + (ballAge / BALL_MEMORY_MS.toFloat()) * 0.15f
      focusX = bx; focusY = by
    } else if (!px.isNaN()) {
      x = px; y = py
      w = spread + 0.30f
      if (mode == Mode.BALL) w += 0.1f  // sin pelota: un poco más abierto hasta encontrarla
      r.ball?.let { b ->
        x = 0.55f * b.cx + 0.45f * px
        y = 0.5f * b.cy + 0.5f * py
      }
      focusX = px; focusY = py
    } else {
      if (now - lastSeenAt > LOST_AFTER_MS) {
        targetW = 1f; targetX = 0.5f; targetY = 0.5f
        focusX = 0.5f; focusY = 0.5f
      }
      motorSpeed = 0
      return
    }
    lastSeenAt = now
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

  /** Jugadores con los pies cerca de un punto. */
  private fun crowdAt(r: AutoTracker.Result, x: Float, y: Float) =
    r.players.filter { abs(it.cx - x) < CROWD_RADIUS_X && abs(it.bottom - y) < CROWD_RADIUS_Y }

  /**
   * Entre las posibles pelotas, se queda con una creíble: muy segura, o cerca de donde iba la pelota,
   * o cerca de algún jugador. Así no confunde una cabeza o una luz con la pelota.
   */
  private fun pickBall(r: AutoTracker.Result, now: Long): AutoTracker.Box? {
    val age = now - ballAt
    val predT = (age / 1000f).coerceAtMost(0.5f)
    val ex = ballX + ballVx * predT
    val ey = ballY + ballVy * predT
    return r.balls.firstOrNull { b ->
      val nearHold = holding && abs(b.cx - holdX) < 0.30f && abs(b.cy - holdY) < 0.30f
      val nearPrediction = nearHold || ballAt > 0 && age < BALL_MEMORY_MS && abs(b.cx - ex) < 0.18f && abs(b.cy - ey) < 0.18f
      val nearPlayer = r.players.any { p -> abs(p.cx - b.cx) < 0.12f && abs(p.bottom - b.cy) < 0.12f }
      b.score >= 0.5f || nearPrediction || (nearPlayer && b.score >= 0.25f)
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
      val ease = if (mode == Mode.BALL && ballSeen) PAN_EASE * 1.6f else PAN_EASE
      if (abs(tx - curX) > PAN_DEADZONE * curW) curX += (tx - curX) * ease
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
