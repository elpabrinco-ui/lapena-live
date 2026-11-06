package com.lapena.live

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class YouTubeException(message: String, val reason: String = "") : Exception(message)

/**
 * Llamadas a la API de YouTube (Data API v3, parte de transmisiones en vivo).
 * Todas son bloqueantes: llamarlas desde un hilo de fondo.
 */
object YouTubeApi {
  private const val BASE = "https://www.googleapis.com/youtube/v3/"
  private const val STREAM_TITLE = "La Peña Live (app)"

  data class Broadcast(
    val id: String,
    val title: String,
    val lifeCycle: String,      // created, ready, testing, live, complete...
    val privacy: String,        // public, unlisted, private
    val scheduledStart: String,
    val liveChatId: String?,
    val boundStreamId: String?,
  ) {
    val isLive get() = lifeCycle == "live" || lifeCycle == "liveStarting"
  }

  data class ChatMessage(val author: String, val text: String)
  data class ChatPage(val messages: List<ChatMessage>, val nextPageToken: String?, val pollMs: Long)

  // ---------------- HTTP ----------------

  private fun call(token: String, method: String, path: String, body: JSONObject? = null): JSONObject {
    val conn = URL(BASE + path).openConnection() as HttpURLConnection
    conn.requestMethod = method
    conn.connectTimeout = 15000
    conn.readTimeout = 20000
    conn.setRequestProperty("Authorization", "Bearer $token")
    conn.setRequestProperty("Accept", "application/json")
    if (body != null) {
      conn.doOutput = true
      conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
      conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
    } else if (method == "POST") {
      conn.doOutput = true
      conn.setFixedLengthStreamingMode(0)
    }
    val code = conn.responseCode
    val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
      ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
    conn.disconnect()
    if (code !in 200..299) {
      val err = try { JSONObject(text).optJSONObject("error") } catch (e: Exception) { null }
      val reason = err?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
      val message = err?.optString("message")?.takeIf { it.isNotEmpty() } ?: "HTTP $code"
      throw YouTubeException(translate(reason, message), reason)
    }
    return if (text.isBlank()) JSONObject() else JSONObject(text)
  }

  private fun translate(reason: String, message: String): String = when (reason) {
    "liveStreamingNotEnabled" -> "El canal no tiene habilitadas las transmisiones en vivo"
    "quotaExceeded" -> "Se terminó el cupo diario de la API de YouTube (se renueva mañana)"
    "invalidScheduledStartTime" -> "La fecha y hora tienen que ser en el futuro"
    "liveChatEnded" -> "El chat de este vivo terminó"
    "redundantTransition" -> "El vivo ya estaba en ese estado"
    else -> message
  }

  private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

  // ---------------- Fechas ----------------

  private fun utcFormat(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
  }

  fun toIsoUtc(millis: Long): String = utcFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(Date(millis))

  fun parseIso(iso: String): Long? = try {
    utcFormat("yyyy-MM-dd'T'HH:mm:ss").parse(iso.take(19))?.time
  } catch (e: Exception) {
    null
  }

  // ---------------- Transmisiones ----------------

  private fun parseBroadcast(o: JSONObject): Broadcast {
    val snippet = o.optJSONObject("snippet") ?: JSONObject()
    val status = o.optJSONObject("status") ?: JSONObject()
    val details = o.optJSONObject("contentDetails") ?: JSONObject()
    return Broadcast(
      id = o.optString("id"),
      title = snippet.optString("title"),
      lifeCycle = status.optString("lifeCycleStatus"),
      privacy = status.optString("privacyStatus"),
      scheduledStart = snippet.optString("scheduledStartTime"),
      liveChatId = snippet.optString("liveChatId").takeIf { it.isNotEmpty() },
      boundStreamId = details.optString("boundStreamId").takeIf { it.isNotEmpty() },
    )
  }

  private fun items(o: JSONObject): List<JSONObject> {
    val arr = o.optJSONArray("items") ?: JSONArray()
    return (0 until arr.length()).map { arr.getJSONObject(it) }
  }

  /** Vivos en curso y programados, ordenados por fecha. */
  fun listBroadcasts(token: String): List<Broadcast> {
    val parts = "id,snippet,status,contentDetails"
    val active = items(call(token, "GET", "liveBroadcasts?part=$parts&broadcastStatus=active&maxResults=10"))
    val upcoming = items(call(token, "GET", "liveBroadcasts?part=$parts&broadcastStatus=upcoming&maxResults=25"))
    return (active + upcoming).map(::parseBroadcast).distinctBy { it.id }
      .sortedBy { parseIso(it.scheduledStart) ?: Long.MAX_VALUE }
  }

  fun getBroadcast(token: String, id: String): Broadcast? =
    items(call(token, "GET", "liveBroadcasts?part=id,snippet,status,contentDetails&id=${enc(id)}"))
      .firstOrNull()?.let(::parseBroadcast)

  /** Programa un partido nuevo y lo deja listo para transmitir con la app. */
  fun schedule(
    token: String, title: String, description: String, startMillis: Long, privacy: String,
  ): Broadcast {
    val body = JSONObject()
      .put("snippet", JSONObject()
        .put("title", title.take(100))
        .put("description", description.take(4900))
        .put("scheduledStartTime", toIsoUtc(startMillis)))
      .put("status", JSONObject()
        .put("privacyStatus", privacy)
        .put("selfDeclaredMadeForKids", false))
      .put("contentDetails", JSONObject()
        .put("enableAutoStart", true)    // empieza solo cuando la app manda video
        .put("enableAutoStop", false)    // no corta si se pierde la señal un rato
        .put("enableDvr", true)
        .put("latencyPreference", "low")
        .put("monitorStream", JSONObject().put("enableMonitorStream", false)))
    val created = parseBroadcast(call(token, "POST", "liveBroadcasts?part=id,snippet,status,contentDetails", body))
    val streamId = ensureStream(token).first
    return bind(token, created.id, streamId)
  }

  private fun bind(token: String, broadcastId: String, streamId: String): Broadcast =
    parseBroadcast(call(token, "POST",
      "liveBroadcasts/bind?id=${enc(broadcastId)}&part=id,snippet,status,contentDetails&streamId=${enc(streamId)}"))

  /**
   * Busca (o crea) el "stream" reutilizable de la app.
   * Devuelve (id del stream, clave de transmisión).
   */
  fun ensureStream(token: String): Pair<String, String> {
    val mine = items(call(token, "GET", "liveStreams?part=id,snippet,cdn&mine=true&maxResults=50"))
    val existing = mine.firstOrNull { it.optJSONObject("snippet")?.optString("title") == STREAM_TITLE }
    val stream = existing ?: call(token, "POST", "liveStreams?part=id,snippet,cdn,contentDetails",
      JSONObject()
        .put("snippet", JSONObject().put("title", STREAM_TITLE))
        .put("cdn", JSONObject()
          .put("ingestionType", "rtmp")
          .put("resolution", "variable")
          .put("frameRate", "variable"))
        .put("contentDetails", JSONObject().put("isReusable", true)))
    val key = stream.optJSONObject("cdn")?.optJSONObject("ingestionInfo")?.optString("streamName").orEmpty()
    if (key.isEmpty()) throw YouTubeException("YouTube no devolvió la clave de transmisión")
    return stream.optString("id") to key
  }

  /** Clave de un stream por id. */
  private fun streamKey(token: String, streamId: String): String? =
    items(call(token, "GET", "liveStreams?part=id,cdn&id=${enc(streamId)}")).firstOrNull()
      ?.optJSONObject("cdn")?.optJSONObject("ingestionInfo")?.optString("streamName")
      ?.takeIf { it.isNotEmpty() }

  /**
   * Prepara un partido elegido de la lista: si no está vinculado a un stream,
   * lo vincula al de la app. Devuelve el partido actualizado y la clave.
   */
  fun prepareForStreaming(token: String, broadcast: Broadcast): Pair<Broadcast, String> {
    broadcast.boundStreamId?.let { id ->
      streamKey(token, id)?.let { return broadcast to it }
    }
    val (streamId, key) = ensureStream(token)
    return bind(token, broadcast.id, streamId) to key
  }

  /** Termina el vivo en YouTube (como "Finalizar transmisión" en YouTube Studio). */
  fun complete(token: String, broadcastId: String) {
    call(token, "POST", "liveBroadcasts/transition?broadcastStatus=complete&id=${enc(broadcastId)}&part=status")
  }

  // ---------------- Chat ----------------

  fun chatMessages(token: String, liveChatId: String, pageToken: String?): ChatPage {
    var path = "liveChat/messages?liveChatId=${enc(liveChatId)}&part=snippet,authorDetails&maxResults=200"
    if (!pageToken.isNullOrEmpty()) path += "&pageToken=${enc(pageToken)}"
    val res = call(token, "GET", path)
    val messages = items(res).mapNotNull { item ->
      val snippet = item.optJSONObject("snippet") ?: return@mapNotNull null
      val text = snippet.optString("displayMessage").trim()
      if (text.isEmpty()) return@mapNotNull null
      val author = item.optJSONObject("authorDetails")?.optString("displayName").orEmpty()
      ChatMessage(author.ifEmpty { "Anónimo" }, text)
    }
    return ChatPage(messages, res.optString("nextPageToken").takeIf { it.isNotEmpty() },
      res.optLong("pollingIntervalMillis", 5000))
  }
}
