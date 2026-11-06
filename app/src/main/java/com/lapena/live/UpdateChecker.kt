package com.lapena.live

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Compara la versión instalada con la última publicada en GitHub.
 * El número de versión va en las notas de la release: "[versionCode=N]".
 */
object UpdateChecker {
  private const val API =
    "https://api.github.com/repos/elpabrinco-ui/lapena-live/releases/tags/ultima-version"
  const val DOWNLOAD_URL =
    "https://github.com/elpabrinco-ui/lapena-live/releases/download/ultima-version/LaPenaLive.apk"

  fun installedVersion(context: Context): Long {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    @Suppress("DEPRECATION")
    return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
  }

  /** Llama a [onNewVersion] (en otro hilo) solo si hay una versión más nueva. */
  fun check(context: Context, onNewVersion: (Long) -> Unit) {
    val installed = installedVersion(context)
    thread(isDaemon = true) {
      try {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val body = JSONObject(text).optString("body")
        val latest = Regex("""versionCode=(\d+)""").find(body)?.groupValues?.get(1)?.toLongOrNull()
        if (latest != null && latest > installed) onNewVersion(latest)
      } catch (e: Exception) {
        // Sin internet o GitHub no responde: no pasa nada.
      }
    }
  }
}
