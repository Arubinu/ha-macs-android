package fr.pergens.hamacs.ha

import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Connexion à Home Assistant avec le compte de l'utilisateur (flux OAuth2 / IndieAuth de HA), puis création d'un
 * jeton d'accès longue durée propre à la tablette : plus besoin de recopier un jeton.
 *
 * - [authorizeUrl] : page de connexion de HA ; après connexion, HA renvoie vers `redirectUri?code=…&state=…`.
 *   HA accepte un `client_id` URL dont l'hôte est un nom de domaine ou une adresse du réseau local, et une
 *   `redirect_uri` de même schéma et même hôte.
 * - [obtainToken] : échange du code (jetons d'une demi-heure), création du jeton longue durée par la WebSocket,
 *   puis révocation de la session temporaire.
 */
object HaLogin {
    private const val TAG = "HaLogin"
    private val json = Json { ignoreUnknownKeys = true }

    /** Connexion dans l'app (WebView) : la redirection est interceptée, l'adresse n'est jamais chargée. */
    const val APP_CLIENT_ID = "https://pergens.fr/hamacs/"
    const val APP_REDIRECT = "https://pergens.fr/hamacs/auth"

    fun authorizeUrl(base: String, clientId: String, redirectUri: String, state: String): String =
        Uri.parse(base.trimEnd('/') + "/auth/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("state", state)
            .build().toString()

    /** Code d'autorisation → jeton longue durée (« HA-MACS · modèle · date », visible dans Profil › Sécurité de HA). */
    suspend fun obtainToken(http: OkHttpClient, base: String, clientId: String, code: String): String = withContext(Dispatchers.IO) {
        val root = base.trimEnd('/')
        val tokens = http.newCall(
            Request.Builder().url("$root/auth/token").post(
                FormBody.Builder().add("grant_type", "authorization_code").add("code", code).add("client_id", clientId).build()
            ).build()
        ).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("HTTP ${r.code}")
            json.parseToJsonElement(body).jsonObject
        }
        val access = tokens["access_token"]?.jsonPrimitive?.contentOrNull ?: error("no access_token")
        val refresh = tokens["refresh_token"]?.jsonPrimitive?.contentOrNull
        try {
            longLivedToken(http, root, access, clientName())
        } finally {
            // Session temporaire inutile ensuite : révoquée (sans effet sur le jeton longue durée).
            if (refresh != null) runCatching {
                http.newCall(Request.Builder().url("$root/auth/revoke").post(FormBody.Builder().add("token", refresh).build()).build()).execute().close()
            }.onFailure { Log.w(TAG, "révocation", it) }
        }
    }

    /** Nom unique (HA refuse deux jetons de même nom). */
    private fun clientName(): String {
        val model = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" ")
            .split(' ').distinct().joinToString(" ").replaceFirstChar { it.uppercase() }
        return "HA-MACS · $model · " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date())
    }

    private suspend fun longLivedToken(http: OkHttpClient, root: String, access: String, name: String): String = withTimeout(20_000) {
        suspendCancellableCoroutine { cont ->
            val wsUrl = root.replaceFirst("http", "ws") + "/api/websocket"
            val ws = http.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val msg = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                    when (msg["type"]?.jsonPrimitive?.contentOrNull) {
                        "auth_required" -> webSocket.send(buildJsonObject { put("type", "auth"); put("access_token", access) }.toString())
                        "auth_ok" -> webSocket.send(buildJsonObject {
                            put("id", 1); put("type", "auth/long_lived_access_token"); put("client_name", name); put("lifespan", 3650)
                        }.toString())
                        "auth_invalid" -> fail(webSocket, "auth_invalid")
                        "result" -> {
                            val token = msg["result"]?.jsonPrimitive?.contentOrNull
                            if (msg["success"]?.jsonPrimitive?.contentOrNull == "true" && token != null) {
                                if (cont.isActive) cont.resume(token)
                                webSocket.close(1000, null)
                            } else fail(webSocket, (msg["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: "error")
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (cont.isActive) cont.resumeWithException(t)
                }

                private fun fail(webSocket: WebSocket, reason: String) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException(reason))
                    webSocket.close(1000, null)
                }
            })
            cont.invokeOnCancellation { ws.cancel() }
        }
    }
}
