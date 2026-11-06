package com.lapena.live

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Descarga la última versión dentro de la app y abre la ventana de Android
 * "¿Querés actualizar esta app?". La nueva versión se instala encima de la actual.
 */
object AppUpdater {

  @Volatile var busy = false
    private set

  /** true si Android ya permite que esta app instale actualizaciones. */
  fun canInstall(activity: Activity): Boolean =
    Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()

  /** Abre el ajuste de Android para permitir instalar actualizaciones desde esta app. */
  fun openInstallPermission(activity: Activity) {
    activity.startActivity(
      Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
    )
  }

  /**
   * Descarga e instala. [onProgress] recibe 0..100 (o -1 si no se sabe el tamaño);
   * [onError] se llama si algo falla. Todo llega en otro hilo.
   */
  fun downloadAndInstall(activity: Activity, onProgress: (Int) -> Unit, onError: (String) -> Unit) {
    if (busy) return
    busy = true
    val ctx = activity.applicationContext
    thread {
      try {
        val file = download(ctx, onProgress)
        install(ctx, file)
      } catch (e: Exception) {
        onError(e.message ?: "Error al descargar")
      } finally {
        busy = false
      }
    }
  }

  private fun download(ctx: Context, onProgress: (Int) -> Unit): File {
    val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
    val file = File(dir, "LaPenaLive.apk")
    var url = URL(UpdateChecker.DOWNLOAD_URL)
    // GitHub redirige a otro servidor: seguir hasta 5 redirecciones.
    var conn: HttpURLConnection
    var hops = 0
    while (true) {
      conn = url.openConnection() as HttpURLConnection
      conn.instanceFollowRedirects = false
      conn.connectTimeout = 15000
      conn.readTimeout = 30000
      val code = conn.responseCode
      if (code in 300..399 && hops < 5) {
        url = URL(url, conn.getHeaderField("Location"))
        conn.disconnect()
        hops++
        continue
      }
      if (code !in 200..299) throw Exception("GitHub respondió $code")
      break
    }
    val total = conn.contentLengthLong
    conn.inputStream.use { input ->
      file.outputStream().use { out ->
        val buf = ByteArray(64 * 1024)
        var done = 0L
        var lastPct = -2
        while (true) {
          val n = input.read(buf)
          if (n < 0) break
          out.write(buf, 0, n)
          done += n
          val pct = if (total > 0) (done * 100 / total).toInt() else -1
          if (pct != lastPct) { onProgress(pct); lastPct = pct }
        }
      }
    }
    conn.disconnect()
    if (file.length() < 100_000) throw Exception("La descarga quedó incompleta")
    return file
  }

  private fun install(ctx: Context, file: File) {
    val installer = ctx.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
    val sessionId = installer.createSession(params)
    installer.openSession(sessionId).use { session ->
      session.openWrite("LaPenaLive.apk", 0, file.length()).use { out ->
        file.inputStream().use { it.copyTo(out) }
        session.fsync(out)
      }
      val flags = PendingIntent.FLAG_UPDATE_CURRENT or
        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
      val pi = PendingIntent.getBroadcast(ctx, sessionId,
        Intent(ctx, UpdateReceiver::class.java), flags)
      session.commit(pi.intentSender)
    }
  }
}

/** Recibe el resultado de la instalación y abre la ventana de confirmación de Android. */
class UpdateReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
      PackageInstaller.STATUS_PENDING_USER_ACTION -> {
        val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
          intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
          @Suppress("DEPRECATION")
          intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
        confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (confirm != null) context.startActivity(confirm)
      }
      PackageInstaller.STATUS_SUCCESS -> { /* la app se reinicia con la versión nueva */ }
      PackageInstaller.STATUS_FAILURE_ABORTED -> { /* el usuario canceló */ }
      else -> {
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "error desconocido"
        Toast.makeText(context, "No se pudo actualizar: $msg", Toast.LENGTH_LONG).show()
      }
    }
  }
}
