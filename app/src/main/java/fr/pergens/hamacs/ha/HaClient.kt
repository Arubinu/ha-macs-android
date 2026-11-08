package fr.pergens.hamacs.ha

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class ConnState { Disconnected, Connecting, Connected, AuthFailed }

class HaException(val code: String, message: String) : Exception(message)

/**
 * Client WebSocket Home Assistant.
 *
 * Utilise `subscribe_entities` (format compressé, celui du frontend officiel) pour garder en
 * mémoire l'état de toutes les entités, et expose des commandes / abonnements génériques.
 */
class HaClient(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _states = MutableStateFlow<Map<String, EntityState>>(emptyMap())
    val states: StateFlow<Map<String, EntityState>> = _states.asStateFlow()

    private val _conn = MutableStateFlow(ConnState.Disconnected)
    val connection: StateFlow<ConnState> = _conn.asStateFlow()

    private val _unreachableSince = MutableStateFlow<Long?>(System.currentTimeMillis())
    /** Depuis quand Home Assistant ne répond plus (null = connecté). */
    val unreachableSince: StateFlow<Long?> = _unreachableSince.asStateFlow()

    private val _refusals =kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Actions refusées par Home Assistant (ou impossibles à envoyer), pour en prévenir l'utilisateur. */
    val refusals: kotlinx.coroutines.flow.SharedFlow<String> = _refusals

    private var ws: WebSocket? = null
    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>()
    private val eventHandlers = ConcurrentHashMap<Int, (JsonObject) -> Unit>()
    private val binaryHandlers = ConcurrentHashMap<Int, (JsonObject) -> Unit>()

    /** Abonnements persistants, ré-émis automatiquement après une reconnexion. */
    private val persistent = ConcurrentHashMap<Long, Pair<JsonObject, (JsonObject) -> Unit>>()
    private val persistentIds = ConcurrentHashMap<Long, Int>()
    private val persistentSeq = AtomicInteger(0)

    var baseUrl: String = ""
        private set
    var token: String = ""
        private set

    private var reconnectJob: Job? = null
    private var generation = 0

    fun connect(url: String, accessToken: String) {
        baseUrl = url.trimEnd('/')
        token = accessToken
        generation++
        ws?.cancel()
        open(generation)
    }

    private fun open(gen: Int) {
        if (gen != generation) return
        _conn.value = ConnState.Connecting
        val wsUrl = baseUrl.replaceFirst("http", "ws") + "/api/websocket"
        val req = Request.Builder().url(wsUrl).build()
        ws = http.newWebSocket(req, Listener(gen))
    }

    private fun scheduleReconnect(gen: Int) {
        if (gen != generation) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(3000)
            open(gen)
        }
    }

    private inner class Listener(private val gen: Int) : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (gen != generation) return
            val el = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return
            // HA peut regrouper plusieurs messages dans un tableau (coalesce_messages).
            if (el is JsonArray) el.forEach { handle(webSocket, it.jsonObject) } else handle(webSocket, el.jsonObject)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WebSocket failure: ${t.message}")
            onClosed(webSocket, 1006, t.message ?: "")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (gen != generation) return
            failAllPending()
            if (_unreachableSince.value == null) _unreachableSince.value = System.currentTimeMillis()
            if (_conn.value != ConnState.AuthFailed) {
                _conn.value = ConnState.Disconnected
                scheduleReconnect(gen)
            }
        }
    }

    private fun handle(socket: WebSocket, msg: JsonObject) {
        when (msg.str("type")) {
            "auth_required" -> socket.send(buildJsonObject {
                put("type", "auth")
                put("access_token", token)
            }.toString())

            "auth_ok" -> {
                _conn.value = ConnState.Connected
                _unreachableSince.value = null
                // Regroupe les messages côté serveur pour limiter la charge sur les vieilles tablettes.
                sendRaw(buildJsonObject { put("type", "supported_features"); put("features", buildJsonObject { put("coalesce_messages", 1) }) })
                subscribeEntitiesInternal()
                loadStateTranslations()
                persistent.forEach { (key, sub) -> persistentIds[key] = sendSubscription(sub.first, sub.second) }
            }

            "auth_invalid" -> {
                val message = msg.str("message").orEmpty()
                Log.e(TAG, "Authentification refusée: $message")
                // Message d'authentification mal formé (autre message arrivé avant) : pas un jeton refusé, on se reconnecte.
                if (message.startsWith("Auth message incorrectly formatted")) socket.cancel()
                else _conn.value = ConnState.AuthFailed
            }

            "result" -> {
                val id = msg["id"]?.jsonPrimitive?.int ?: return
                val def = pending.remove(id) ?: return
                if (msg["success"]?.jsonPrimitive?.contentOrNull == "true") {
                    def.complete(msg["result"] ?: JsonNull)
                } else {
                    val err = msg["error"] as? JsonObject
                    def.completeExceptionally(HaException(err?.str("code") ?: "unknown", err?.str("message") ?: err?.str("code") ?: "unknown"))
                }
            }

            "event" -> {
                val id = msg["id"]?.jsonPrimitive?.int ?: return
                val ev = msg["event"] as? JsonObject ?: return
                eventHandlers[id]?.invoke(ev)
            }
        }
    }

    private fun failAllPending() {
        pending.values.forEach { it.completeExceptionally(HaException("disconnected", "connection lost")) }
        pending.clear()
        eventHandlers.clear()
    }

    /**
     * HA exige des identifiants strictement croissants : l'attribution et l'envoi sont atomiques,
     * sinon deux coroutines peuvent envoyer leurs messages dans le désordre.
     */
    private val sendLock = Any()

    private fun sendWithId(msg: JsonObject, before: (Int) -> Unit = {}): Pair<Int, Boolean> = synchronized(sendLock) {
        val id = ids.getAndIncrement()
        before(id)
        // Rien avant l'authentification : HA prendrait le message pour celui d'authentification (« auth_invalid »).
        // Les abonnements persistants sont rejoués après `auth_ok`, les appels échouent (« not connected »).
        if (_conn.value != ConnState.Connected) return@synchronized id to false
        id to (ws?.send(JsonObject(msg + ("id" to JsonPrimitive(id))).toString()) ?: false)
    }

    private fun sendRaw(msg: JsonObject): Int = sendWithId(msg).first

    /** Envoie une commande et attend son résultat. */
    private val _stateTranslations = MutableStateFlow<Map<String, String>>(emptyMap())
    /**
     * Traductions des états fournies par Home Assistant, dans la langue de l'app (celles du frontend) :
     * `component.<domaine>.entity_component.<device_class ou _>.state.<état>` → libellé.
     */
    val stateTranslations: StateFlow<Map<String, String>> = _stateTranslations.asStateFlow()

    private fun loadStateTranslations() {
        scope.launch {
            runCatching {
                val res = call(buildJsonObject {
                    put("type", "frontend/get_translations")
                    put("language", java.util.Locale.getDefault().toLanguageTag().let { if (it.startsWith("pt-BR")) it else it.substringBefore('-') })
                    put("category", "entity_component")
                }).jsonObject
                val r = res["resources"]?.jsonObject ?: return@runCatching
                _stateTranslations.value = r.mapNotNull { (k, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
            }.onFailure { Log.w(TAG, "translations: ${it.message}") }
        }
    }

    /** État traduit par Home Assistant, ou null s'il n'en fournit pas. */
    fun translateState(domain: String, deviceClass: String?, state: String): String? {
        val t = _stateTranslations.value
        if (t.isEmpty()) return null
        return deviceClass?.let { t["component.$domain.entity_component.$it.state.$state"] }
            ?: t["component.$domain.entity_component._.state.$state"]
    }

    suspend fun call(msg: JsonObject, timeoutMs: Long = 15_000): JsonElement {
        val def = CompletableDeferred<JsonElement>()
        val (id, sent) = sendWithId(msg) { pending[it] = def }
        if (!sent) {
            pending.remove(id)
            throw HaException("not_connected", "not connected")
        }
        return withTimeout(timeoutMs) { def.await() }
    }

    private fun sendSubscription(msg: JsonObject, onEvent: (JsonObject) -> Unit): Int {
        return sendWithId(msg) { id ->
            eventHandlers[id] = onEvent
            pending[id] = CompletableDeferred<JsonElement>().also { d ->
                d.invokeOnCompletion { e -> if (e != null) Log.w(TAG, "Abonnement refusé ${msg.str("type")}: ${e.message}") }
            }
        }.first
    }

    /**
     * Abonnement persistant (rejoué après reconnexion). Retourne une fonction de désabonnement.
     */
    fun subscribe(msg: JsonObject, onEvent: (JsonObject) -> Unit): () -> Unit {
        val key = persistentSeq.incrementAndGet().toLong()
        persistent[key] = msg to onEvent
        if (_conn.value == ConnState.Connected) persistentIds[key] = sendSubscription(msg, onEvent)
        return {
            persistent.remove(key)
            persistentIds.remove(key)?.let { subId ->
                eventHandlers.remove(subId)
                if (_conn.value == ConnState.Connected) {
                    sendRaw(buildJsonObject { put("type", "unsubscribe_events"); put("subscription", subId) })
                }
            }
        }
    }

    /** Abonnement ponctuel (ex. pipeline Assist) qui n'est pas rejoué après reconnexion. */
    fun subscribeOnce(msg: JsonObject, onEvent: (JsonObject) -> Unit): Int = sendSubscription(msg, onEvent)

    fun unsubscribeOnce(id: Int) {
        eventHandlers.remove(id)
    }

    /** Envoie des données binaires (audio du pipeline Assist) préfixées par l'identifiant du handler. */
    fun sendBinary(handlerId: Int, data: ByteArray) {
        val buf = ByteArray(data.size + 1)
        buf[0] = handlerId.toByte()
        System.arraycopy(data, 0, buf, 1, data.size)
        ws?.send(ByteString.of(*buf))
    }

    suspend fun callService(
        domain: String,
        service: String,
        data: JsonObject? = null,
        target: JsonObject? = null,
        /** Service qui renvoie des données (recherche, file d'attente…) : `{ "response": … }`. */
        returnResponse: Boolean = false,
    ): JsonElement = call(buildJsonObject {
        put("type", "call_service")
        put("domain", domain)
        put("service", service)
        if (data != null) put("service_data", data)
        if (target != null) put("target", target)
        if (returnResponse) put("return_response", true)
    })

    /** Appel de service sans attente du résultat ; [notify] : prévenir l'utilisateur en cas de refus. */
    fun fireService(domain: String, service: String, data: JsonObject? = null, target: JsonObject? = null, notify: Boolean = true) {
        scope.launch {
            runCatching { callService(domain, service, data, target) }
                .onFailure {
                    Log.w(TAG, "Service $domain.$service: ${it.message}")
                    if (notify) _refusals.tryEmit(if ((it as? HaException)?.code in setOf("not_connected", "disconnected")) NOT_CONNECTED else it.message?.takeIf { m -> m.isNotBlank() } ?: "$domain.$service")
                }
        }
    }

    fun toggle(entityId: String) {
        val domain = entityId.substringBefore('.')
        val (svcDomain, svc) = when (domain) {
            "light", "switch", "fan", "input_boolean", "automation", "siren", "humidifier" -> domain to "toggle"
            "cover" -> "cover" to "toggle"
            "lock" -> "lock" to if (_states.value[entityId]?.state == "locked") "unlock" else "lock"
            "media_player" -> "media_player" to "toggle"
            "scene" -> "scene" to "turn_on"
            "script" -> "script" to "turn_on"
            "button", "input_button" -> domain to "press"
            else -> "homeassistant" to "toggle"
        }
        fireService(svcDomain, svc, target = buildJsonObject { put("entity_id", entityId) })
    }

    /** Résout une URL relative de Home Assistant (/local/…, /api/…). */
    fun resolveUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        // Adresse complète, ou fichier embarqué dans l'app (file:///android_asset/…) : utilisée telle quelle.
        return if (path.startsWith("http://") || path.startsWith("https://") || path.startsWith("file:")) path else baseUrl + (if (path.startsWith("/")) path else "/$path")
    }

    // ---------------------------------------------------------------------------------------
    // subscribe_entities (format compressé)
    // ---------------------------------------------------------------------------------------

    private fun subscribeEntitiesInternal() {
        val current = HashMap<String, EntityState>()
        _states.value = emptyMap()
        sendSubscription(buildJsonObject { put("type", "subscribe_entities") }) { ev ->
            ev["a"]?.jsonObject?.forEach { (id, raw) ->
                current[id] = decodeFull(id, raw.jsonObject)
            }
            ev["c"]?.jsonObject?.forEach { (id, raw) ->
                val old = current[id] ?: return@forEach
                current[id] = applyDiff(old, raw.jsonObject)
            }
            ev["r"]?.jsonArray?.forEach { current.remove(it.jsonPrimitive.content) }
            _states.value = VirtualEntities.augment(current)
        }
    }

    private fun decodeFull(id: String, o: JsonObject): EntityState {
        val lc = o["lc"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val lu = o["lu"]?.jsonPrimitive?.doubleOrNull ?: lc
        return EntityState(
            entityId = id,
            state = o["s"]?.jsonPrimitive?.contentOrNull ?: "unknown",
            attributes = o["a"] as? JsonObject ?: JsonObject(emptyMap()),
            lastChanged = (lc * 1000).toLong(),
            lastUpdated = (lu * 1000).toLong(),
        )
    }

    private fun applyDiff(old: EntityState, diff: JsonObject): EntityState {
        var s = old
        (diff["+"] as? JsonObject)?.let { add ->
            val attrs = HashMap(s.attributes)
            (add["a"] as? JsonObject)?.let { attrs.putAll(it) }
            val lc = add["lc"]?.jsonPrimitive?.doubleOrNull
            val lu = add["lu"]?.jsonPrimitive?.doubleOrNull
            s = s.copy(
                state = add["s"]?.jsonPrimitive?.contentOrNull ?: s.state,
                attributes = JsonObject(attrs),
                lastChanged = lc?.let { (it * 1000).toLong() } ?: s.lastChanged,
                lastUpdated = (lu ?: lc)?.let { (it * 1000).toLong() } ?: s.lastUpdated,
            )
        }
        (diff["-"] as? JsonObject)?.let { rem ->
            val keys = rem["a"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            if (keys.isNotEmpty()) s = s.copy(attributes = JsonObject(s.attributes - keys.toSet()))
        }
        return s
    }

    companion object {
        private const val TAG = "HaClient"
        /** Action impossible faute de connexion (message traduit à l'affichage). */
        const val NOT_CONNECTED = "#not_connected"
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
