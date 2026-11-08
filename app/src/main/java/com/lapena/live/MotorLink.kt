package com.lapena.live

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * Conexión Bluetooth con el motor (placa ESP32 con el programa de la carpeta firmware/).
 * Usa el "servicio UART" de Nordic: mandamos líneas de texto cortas.
 *
 *   S<n>   velocidad en pasos por segundo (negativo = al otro lado). S0 = quieto.
 *   C      volver al centro (donde estaba al prenderlo)
 *   H      "acá es el centro"
 *
 * El motor se frena solo si deja de recibir órdenes por 1 segundo (por si se corta la conexión).
 */
@SuppressLint("MissingPermission") // Los permisos se piden antes de llamar a connect().
class MotorLink(
  context: Context,
  private val onState: (State) -> Unit,
) {
  enum class State { OFF, SEARCHING, CONNECTED }

  companion object {
    private const val TAG = "MotorLink"
    const val DEVICE_NAME = "LaPena-Cam"
    private val SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    private const val SCAN_TIMEOUT_MS = 15_000L
  }

  private val appContext = context.applicationContext
  private val main = Handler(Looper.getMainLooper())
  private val adapter: BluetoothAdapter? =
    (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
  private var gatt: BluetoothGatt? = null
  private var rx: BluetoothGattCharacteristic? = null
  @Volatile var state = State.OFF
    private set
  private var scanning = false
  private var wanted = false
  private var lastSpeedSent = Int.MIN_VALUE
  private var lastSentAt = 0L

  /** Si el motor gira al revés (según cómo quedó armado), se invierte acá. */
  var inverted = false

  val bluetoothOn get() = adapter?.isEnabled == true

  fun connect() {
    val a = adapter ?: return setState(State.OFF)
    if (!a.isEnabled) return setState(State.OFF)
    wanted = true
    if (state != State.OFF) return
    setState(State.SEARCHING)
    val scanner = a.bluetoothLeScanner ?: return setState(State.OFF)
    // Se busca por el servicio (siempre va en el anuncio; el nombre a veces no entra).
    val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
    val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
    scanning = true
    scanner.startScan(listOf(filter), settings, scanCallback)
    main.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
  }

  fun disconnect() {
    wanted = false
    stopScan()
    if (state == State.CONNECTED) sendNow("S0")
    main.postDelayed({
      gatt?.disconnect()
      gatt?.close()
      gatt = null
      rx = null
      setState(State.OFF)
    }, 150)
  }

  /** Velocidad para el motor. Se puede llamar seguido: solo manda si cambió o cada 300 ms (para que no se frene). */
  fun setSpeed(speed: Int) {
    if (state != State.CONNECTED) return
    val s = if (inverted) -speed else speed
    val now = android.os.SystemClock.elapsedRealtime()
    if (s == lastSpeedSent && now - lastSentAt < 300) return
    if (sendNow("S$s")) {
      lastSpeedSent = s
      lastSentAt = now
    }
  }

  fun center() { sendNow("C") }
  fun setHome() { sendNow("H") }

  // ---------------------------------------------------------------------------

  private val scanTimeout = Runnable {
    if (scanning) {
      stopScan()
      wanted = false
      setState(State.OFF)
    }
  }

  private val scanCallback = object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
      if (!scanning) return
      stopScan()
      openGatt(result.device)
    }

    override fun onScanFailed(errorCode: Int) {
      Log.w(TAG, "Scan falló: $errorCode")
      scanning = false
      setState(State.OFF)
    }
  }

  private fun stopScan() {
    main.removeCallbacks(scanTimeout)
    if (!scanning) return
    scanning = false
    try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
  }

  private fun openGatt(device: BluetoothDevice) {
    gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    } else {
      device.connectGatt(appContext, false, gattCallback)
    }
  }

  private val gattCallback = object : BluetoothGattCallback() {
    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
      if (newState == BluetoothProfile.STATE_CONNECTED) {
        g.discoverServices()
      } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
        g.close()
        gatt = null
        rx = null
        setState(State.OFF)
        // Si se cortó solo (no lo apagamos nosotros), vuelve a buscar.
        if (wanted) main.postDelayed({ if (wanted) connect() }, 1500)
      }
    }

    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
      val c = g.getService(SERVICE)?.getCharacteristic(RX)
      if (c == null) {
        Log.w(TAG, "El dispositivo no tiene el servicio del motor")
        g.disconnect()
        return
      }
      rx = c
      lastSpeedSent = Int.MIN_VALUE
      setState(State.CONNECTED)
    }
  }

  @Suppress("DEPRECATION")
  private fun sendNow(text: String): Boolean {
    val g = gatt ?: return false
    val c = rx ?: return false
    val bytes = (text + "\n").toByteArray()
    return try {
      if (Build.VERSION.SDK_INT >= 33) {
        g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
          android.bluetooth.BluetoothStatusCodes.SUCCESS
      } else {
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        c.value = bytes
        g.writeCharacteristic(c)
      }
    } catch (e: Exception) {
      Log.w(TAG, "No se pudo mandar al motor", e)
      false
    }
  }

  private fun setState(s: State) {
    state = s
    main.post { onState(s) }
  }
}
