package com.lapena.live

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks

/**
 * Inicio de sesión con Google para usar la API de YouTube.
 * Google guarda el permiso: después de la primera vez, el token se obtiene sin pantallas.
 */
object YouTubeAuth {
  private const val SCOPE = "https://www.googleapis.com/auth/youtube.force-ssl"

  private fun request() = AuthorizationRequest.builder()
    .setRequestedScopes(listOf(Scope(SCOPE)))
    .build()

  /**
   * Pide un token. Si hace falta que el usuario elija la cuenta o acepte permisos,
   * abre la pantalla de Google con [launcher] (y el resultado llega a la Activity).
   */
  fun getToken(
    activity: Activity,
    launcher: ActivityResultLauncher<IntentSenderRequest>,
    onToken: (String) -> Unit,
    onError: (String) -> Unit,
  ) {
    Identity.getAuthorizationClient(activity).authorize(request())
      .addOnSuccessListener { result ->
        val pending = result.pendingIntent
        if (result.hasResolution() && pending != null) {
          launcher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
        } else {
          val token = result.accessToken
          if (token != null) onToken(token) else onError("Google no devolvió acceso")
        }
      }
      .addOnFailureListener { onError(it.message ?: "Error de Google") }
  }

  /** Token desde el resultado de la pantalla de Google. */
  fun tokenFromIntent(activity: Activity, data: Intent?): String? = try {
    Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data).accessToken
  } catch (e: Exception) {
    null
  }

  /**
   * Token sin pantallas, para usar desde un hilo de fondo (chat, finalizar vivo).
   * Devuelve null si hace falta iniciar sesión otra vez.
   */
  fun getTokenSilently(context: Context): String? = try {
    val result = Tasks.await(Identity.getAuthorizationClient(context).authorize(request()))
    if (result.hasResolution()) null else result.accessToken
  } catch (e: Exception) {
    null
  }
}
