package fr.pergens.hamacs.voice

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import fr.pergens.hamacs.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * La tablette en satellite vocal Home Assistant, avec le protocole Wyoming (celui de wyoming-satellite, des satellites
 * ESPHome et de l'intégration « Wyoming Protocol ») : serveur TCP sur le port 10700, annoncé en mDNS
 * (`_wyoming._tcp`) ; Home Assistant s'y connecte, choisit le pipeline Assist (STT, agent, TTS, mot d'activation) et
 * renvoie transcription, réponse et audio de la synthèse vocale.
 *
 * Messages : une ligne JSON d'en-tête (`type`, `data` ou `data_length`, `payload_length`), puis les données JSON et
 * le contenu binaire éventuels (audio PCM).
 */
class WyomingSatellite(
    private val ctx: Context,
    private val scope: CoroutineScope,
    /** Nom de l'appareil dans Home Assistant (celui de la tablette). */
    private val name: () -> String,
    /** Adresse du serveur Home Assistant de l'app : seul appareil accepté d'office. */
    private val haUrl: () -> String,
) {
    sealed interface Status {
        data object Off : Status
        /** En attente de Home Assistant (annoncé sur le réseau). */
        data object Waiting : Status
        /** Home Assistant connecté ; [running] : satellite démarré (non mis en pause dans HA). */
        data class Connected(val running: Boolean) : Status
    }

    /** Événement reçu de Home Assistant. */
    class Event(val type: String, val data: JsonObject, val payload: ByteArray?)

    private val prefs = ctx.getSharedPreferences("wyoming", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean("enabled", false))
    val enabled: StateFlow<Boolean> = _enabled
    /** Mot d'activation : micro écouté en continu, détection faite par Home Assistant (openWakeWord…). */
    private val _wakeWord = MutableStateFlow(prefs.getBoolean("wake_word", false))
    val wakeWord: StateFlow<Boolean> = _wakeWord
    private val _status = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = _status

    /** Événements de Home Assistant, traités par l'assistant vocal. */
    var onEvent: ((Event) -> Unit)? = null
    /** Satellite démarré ou arrêté par Home Assistant (run-satellite / pause-satellite / déconnexion). */
    var onRunning: ((Boolean) -> Unit)? = null

    private val json = Json { ignoreUnknownKeys = true }
    private var job: Job? = null
    @Volatile private var client: Socket? = null
    @Volatile private var out: OutputStream? = null
    private val writeLock = Any()
    /** Envois hors du fil principal (réseau interdit), un par un et dans l'ordre. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val writer = Dispatchers.IO.limitedParallelism(1)
    private var nsd: NsdManager.RegistrationListener? = null

    val ready: Boolean get() = (_status.value as? Status.Connected)?.running == true

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean("enabled", on).remove("peer").apply()
        _enabled.value = on
        restart()
    }

    fun setWakeWord(on: Boolean) {
        prefs.edit().putBoolean("wake_word", on).apply()
        _wakeWord.value = on
        onRunning?.invoke(ready)
    }

    fun start() = restart()

    private fun restart() {
        job?.cancel()
        closeClient()
        unregister()
        if (!_enabled.value) { _status.value = Status.Off; return }
        job = scope.launch(Dispatchers.IO) {
            val server = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) } }
                .getOrElse { Log.w(TAG, "port $PORT", it); _status.value = Status.Off; return@launch }
            register(server.localPort)
            _status.value = Status.Waiting
            try {
                while (isActive) {
                    val s = runCatching { server.accept() }.getOrNull() ?: break
                    // Le protocole n'a pas d'authentification : sans ce filtre, n'importe quel appareil du réseau
                    // prendrait la place de Home Assistant et recevrait le micro (mot d'activation : en continu).
                    if (!allowed(s.inetAddress)) {
                        Log.w(TAG, "connexion refusée : ${s.inetAddress?.hostAddress}")
                        runCatching { s.close() }
                        continue
                    }
                    // Une seule connexion de Home Assistant : la nouvelle remplace l'ancienne.
                    closeClient()
                    client = s
                    out = s.getOutputStream()
                    _status.value = Status.Connected(false)
                    launch { readLoop(s) }
                }
            } finally {
                runCatching { server.close() }
                unregister()
            }
        }
    }

    /**
     * Appareil autorisé à se connecter : le serveur Home Assistant de l'app (adresse de [haUrl]), sinon le premier
     * appareil connecté depuis l'activation (HA derrière un proxy, adresse publique…), retenu ensuite. Désactiver puis
     * réactiver le satellite oublie cet appareil. Versions de débogage : la tablette elle-même aussi (`adb forward`).
     */
    private fun allowed(addr: java.net.InetAddress?): Boolean {
        val ip = addr?.hostAddress ?: return false
        if (BuildConfig.DEBUG && addr.isLoopbackAddress) return true
        val host = runCatching { android.net.Uri.parse(haUrl()).host }.getOrNull()
        val ha = host?.let { h -> runCatching { java.net.InetAddress.getAllByName(h).mapNotNull { it.hostAddress } }.getOrNull() }.orEmpty()
        if (ip in ha) return true
        val peer = prefs.getString("peer", null)
        if (peer == null) { prefs.edit().putString("peer", ip).apply(); return true }
        return peer == ip
    }

    private fun closeClient() {
        runCatching { client?.close() }
        client = null
        out = null
    }

    private fun readLoop(s: Socket) {
        val input = BufferedInputStream(s.getInputStream())
        try {
            while (true) {
                val ev = read(input) ?: break
                when (ev.type) {
                    "describe" -> send("info", info())
                    "ping" -> send("pong", buildJsonObject { (ev.data["text"] as? JsonPrimitive)?.let { put("text", it) } })
                    "run-satellite" -> { _status.value = Status.Connected(true); onRunning?.invoke(true) }
                    "pause-satellite" -> { _status.value = Status.Connected(false); onRunning?.invoke(false) }
                    else -> onEvent?.invoke(ev)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "connexion", e)
        } finally {
            if (client == s) {
                closeClient()
                _status.value = if (_enabled.value) Status.Waiting else Status.Off
                onRunning?.invoke(false)
            }
        }
    }

    /** Description du satellite pour Home Assistant. */
    private fun info(): JsonObject = buildJsonObject {
        putJsonObject("satellite") {
            put("name", name())
            putJsonObject("attribution") { put("name", "HA-MACS"); put("url", "https://github.com/Arubinu/ha-macs-android") }
            put("installed", true)
            put("description", "HA-MACS tablet")
            put("version", BuildConfig.VERSION_NAME)
            put("has_vad", false)
            put("supports_trigger", false)
        }
    }

    /** Envoie un événement (en file, dans l'ordre) ; false si Home Assistant n'est pas connecté. */
    fun send(type: String, data: JsonObject = JsonObject(emptyMap()), payload: ByteArray? = null, length: Int = payload?.size ?: 0): Boolean {
        val o = out ?: return false
        val bytes = payload?.copyOf(length)
        scope.launch(writer) { write(o, type, data, bytes) }
        return true
    }

    private fun write(o: OutputStream, type: String, data: JsonObject, payload: ByteArray?) {
        val length = payload?.size ?: 0
        val header = buildJsonObject {
            put("type", type)
            put("version", VERSION)
            if (data.isNotEmpty()) put("data", data)
            if (payload != null && length > 0) put("payload_length", length)
        }.toString() + "\n"
        runCatching {
            synchronized(writeLock) {
                o.write(header.toByteArray())
                if (payload != null && length > 0) o.write(payload, 0, length)
                o.flush()
            }
        }.onFailure { Log.d(TAG, "envoi $type", it) }
    }

    fun audioStart() = send("audio-start", audioFormat())
    fun audioChunk(pcm: ByteArray, n: Int) = send("audio-chunk", audioFormat(), pcm, n)
    fun audioStop() = send("audio-stop", buildJsonObject { put("timestamp", System.currentTimeMillis()) })

    /** Lance un pipeline Assist : depuis le mot d'activation (`wake`) ou directement l'écoute (`asr`). */
    fun runPipeline(fromWake: Boolean) = send("run-pipeline", buildJsonObject {
        put("start_stage", if (fromWake) "wake" else "asr")
        put("end_stage", "tts")
        put("restart_on_end", fromWake)
        putJsonObject("snd_format") { put("rate", 22050); put("width", 2); put("channels", 1) }
    })

    private fun audioFormat() = buildJsonObject {
        put("rate", VoiceAssistant.SAMPLE_RATE); put("width", 2); put("channels", 1)
        put("timestamp", System.currentTimeMillis())
    }

    private fun read(input: InputStream): Event? {
        val line = readLine(input) ?: return null
        if (line.isBlank()) return Event("", JsonObject(emptyMap()), null)
        val head = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return Event("", JsonObject(emptyMap()), null)
        var data = (head["data"] as? JsonObject) ?: JsonObject(emptyMap())
        val dataLength = (head["data_length"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        // Tailles annoncées plafonnées : une valeur énorme ferait allouer des Go et planter l'app.
        if (dataLength > MAX_DATA) return null
        if (dataLength > 0) {
            val extra = readExactly(input, dataLength) ?: return null
            runCatching { json.parseToJsonElement(extra.toString(Charsets.UTF_8)).jsonObject }.getOrNull()?.let { data = JsonObject(data + it) }
        }
        val payloadLength = (head["payload_length"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        if (payloadLength > MAX_PAYLOAD) return null
        val payload = if (payloadLength > 0) readExactly(input, payloadLength) ?: return null else null
        return Event((head["type"] as? JsonPrimitive)?.content ?: "", data, payload)
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray? {
        val b = ByteArray(n)
        var read = 0
        while (read < n) {
            val k = input.read(b, read, n - read)
            if (k < 0) return null
            read += k
        }
        return b
    }

    private fun readLine(input: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return null
            if (c == '\n'.code) return b.toString("UTF-8")
            b.write(c)
            if (b.size() > 1_000_000) return null
        }
    }

    // mDNS : Home Assistant découvre le satellite (intégration Wyoming Protocol, « Découvert »).
    private fun register(port: Int) {
        val mgr = ctx.getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply {
            serviceName = name()
            serviceType = "_wyoming._tcp"
            setPort(port)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { Log.d(TAG, "annoncé : ${i.serviceName}") }
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { Log.w(TAG, "annonce : $code") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        runCatching { mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, l); nsd = l }
    }

    private fun unregister() {
        val l = nsd ?: return
        nsd = null
        runCatching { ctx.getSystemService(NsdManager::class.java)?.unregisterService(l) }
    }

    companion object {
        private const val TAG = "WyomingSatellite"
        const val PORT = 10700
        private const val VERSION = "1.5.4"
        private const val MAX_DATA = 1_000_000
        /** Morceau d'audio (TTS en 22 kHz : quelques Ko ; marge large). */
        private const val MAX_PAYLOAD = 8_000_000

        fun text(e: Event): String = (e.data["text"] as? JsonPrimitive)?.content.orEmpty()
        fun int(e: Event, k: String, def: Int): Int = (e.data[k] as? JsonPrimitive)?.content?.toIntOrNull() ?: def

        @Suppress("unused") private fun JsonElement.str() = (this as? JsonPrimitive)?.content
    }
}
