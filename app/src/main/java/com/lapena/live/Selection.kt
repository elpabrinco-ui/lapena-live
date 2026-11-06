package com.lapena.live

import android.content.Context

/** Partido de YouTube elegido para transmitir (queda guardado en el celular). */
data class Selection(val broadcastId: String, val title: String, val liveChatId: String?) {
  companion object {
    private const val PREFS = "lapena_live"
    const val KEY_STREAM = "stream_key"

    fun load(context: Context): Selection? {
      val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      val id = p.getString("sel_id", null) ?: return null
      return Selection(id, p.getString("sel_title", "").orEmpty(), p.getString("sel_chat", null))
    }

    fun save(context: Context, sel: Selection, streamKey: String) {
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString("sel_id", sel.broadcastId)
        .putString("sel_title", sel.title)
        .putString("sel_chat", sel.liveChatId)
        .putString(KEY_STREAM, streamKey)
        .apply()
    }

    fun clear(context: Context) {
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .remove("sel_id").remove("sel_title").remove("sel_chat").apply()
    }
  }
}
