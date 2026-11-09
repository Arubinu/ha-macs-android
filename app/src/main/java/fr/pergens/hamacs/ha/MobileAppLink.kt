package fr.pergens.hamacs.ha

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log
import fr.pergens.hamacs.BuildConfig
import fr.pergens.hamacs.config.ConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale

/**
 * La tablette vue par Home Assistant : elle s'enregistre comme l'app mobile officielle (intégration `mobile_app`,
 * présente par défaut). Home Assistant voit sa batterie, l'écran et la présence détectée, et peut lui envoyer des
 * notifications par `notify.mobile_app_<nom>` :
 * - texte quelconque : bandeau en haut de l'écran ;
 * - `message: TTS` + `data.tts_text` : lecture à voix haute ;
 * - `message: command_screen_on` : allume l'écran ;
 * - `message: command_camera` + `data.entity` : caméra en plein écran ;
 * - `message: command_macs_mood` + `data.mood` (`surprised`, `happy`…) et `data.duration` (secondes, 10 par défaut) :
 *   humeur de M.A.C.S. le temps indiqué, sans entité à créer dans Home Assistant (`idle` l'annule).
 * Réception par la WebSocket déjà ouverte (`mobile_app/push_notification_channel`), sans service Google.
 */
class MobileAppLink(
    private val context: Context,
    private val http: OkHttpClient,
    private val ha: HaClient,
    private val config: ConfigRepository,
    private val scope: CoroutineScope,
) {
    sealed interface Status {
        data object Off : Status
        data object Registering : Status
        data class Registered(val service: String) : Status
        data class Failed(val message: String) : Status
    }

    /** Ce que l'app affiche ou fait à la demande de Home Assistant. */
    sealed interface Command {
        data object ScreenOn : Command
        data class Camera(val entity: String) : Command
        data class Banner(val title: String?, val message: String) : Command
    }

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _status = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = _status.asStateFlow()
    val commands = MutableSharedFlow<Command>(extraBufferCapacity = 8)

    /** Écran allumé (pas d'écran noir de nuit), fourni par l'activité. */
    val screenOn = MutableStateFlow(true)
    /** Dernier mouvement vu par la caméra (détection de présence). */
    var lastMotion: () -> Long = { 0L }

    private var unsubscribe: (() -> Unit)? = null
    private var loop: Job? = null
    private var tts: TextToSpeech? = null

    var enabled: Boolean
        get() = prefs.getBoolean("ha_device_enabled", false)
        set(v) { prefs.edit().putBoolean("ha_device_enabled", v).apply(); refresh() }

    var deviceName: String
        get() = prefs.getString("ha_device_name", null)?.takeIf { it.isNotBlank() } ?: "HA-MACS ${Build.MODEL}"
        set(v) { prefs.edit().putString("ha_device_name", v.trim()).apply(); if (webhook != null) scope.launch { updateRegistration() } }

    /** Enregistrement propre à cette tablette et à ce serveur (non exporté). */
    private var webhook: String?
        get() = prefs.getString("ha_webhook_id", null)?.takeIf { prefs.getString("ha_webhook_url", null) == ha.baseUrl }
        set(v) { prefs.edit().putString("ha_webhook_id", v).putString("ha_webhook_url", ha.baseUrl).apply() }

    fun start() {
        scope.launch {
            combine(ha.connection, config.settings) { c, s -> c to s.haUrl }.distinctUntilChanged().collect { refresh() }
        }
        scope.launch { screenOn.collect { if (webhook != null) pushStates() } }
    }

    private fun refresh() {
        loop?.cancel(); loop = null
        unsubscribe?.invoke(); unsubscribe = null
        if (!enabled) { _status.value = Status.Off; return }
        if (ha.connection.value != ConnState.Connected) return
        loop = scope.launch {
            val id = webhook ?: runCatching { register() }.getOrElse {
                Log.w(TAG, "enregistrement", it)
                _status.value = Status.Failed(it.message ?: it.javaClass.simpleName)
                return@launch
            }
            _status.value = Status.Registered("notify.mobile_app_" + slug(deviceName))
            unsubscribe = ha.subscribe(buildJsonObject {
                put("type", "mobile_app/push_notification_channel"); put("webhook_id", id); put("support_confirm", true)
            }) { onPush(id, it) }
            while (true) {
                pushStates()
                delay(60_000)
            }
        }
    }

    // --- Enregistrement et capteurs ------------------------------------------------------------

    private suspend fun register(): String = withContext(Dispatchers.IO) {
        _status.value = Status.Registering
        val body = buildJsonObject {
            put("device_id", deviceId())
            put("app_id", BuildConfig.APPLICATION_ID)
            put("app_name", "HA-MACS")
            put("app_version", BuildConfig.VERSION_NAME)
            put("device_name", deviceName)
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("os_name", "Android")
            put("os_version", Build.VERSION.RELEASE)
            put("supports_encryption", false)
            putJsonObject("app_data") { put("push_websocket_channel", true) }
        }
        val req = Request.Builder().url("${ha.baseUrl}/api/mobile_app/registrations")
            .header("Authorization", "Bearer ${ha.token}")
            .post(body.toString().toRequestBody(JSON)).build()
        val id = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            json.parseToJsonElement(r.body!!.string()).jsonObject["webhook_id"]?.jsonPrimitive?.contentOrNull ?: error("webhook_id")
        }
        webhook = id
        sensors().forEach { s -> webhookCall(id, "register_sensor", s) }
        id
    }

    private suspend fun updateRegistration() {
        val id = webhook ?: return
        runCatching {
            webhookCall(id, "update_registration", buildJsonObject {
                put("app_version", BuildConfig.VERSION_NAME); put("device_name", deviceName)
                put("manufacturer", Build.MANUFACTURER); put("model", Build.MODEL); put("os_version", Build.VERSION.RELEASE)
                putJsonObject("app_data") { put("push_websocket_channel", true) }
            })
        }
        if (_status.value is Status.Registered) _status.value = Status.Registered("notify.mobile_app_" + slug(deviceName))
    }

    /** Capteurs de la tablette : définition (avec état) pour l'enregistrement. */
    private fun sensors(): List<JsonObject> {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: -1
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val presence = System.currentTimeMillis() - lastMotion() < 60_000
        fun sensor(id: String, name: String, type: String, state: JsonElement, deviceClass: String?, icon: String, unit: String? = null) = buildJsonObject {
            put("unique_id", id); put("name", name); put("type", type); put("state", state); put("icon", icon)
            deviceClass?.let { put("device_class", it) }
            unit?.let { put("unit_of_measurement", it); put("state_class", "measurement") }
        }
        return listOf(
            sensor("battery_level", "Batterie", "sensor", JsonPrimitive(level), "battery", "mdi:battery", "%"),
            sensor("battery_charging", "En charge", "binary_sensor", JsonPrimitive(plugged), "battery_charging", "mdi:power-plug"),
            sensor("screen_on", "Écran allumé", "binary_sensor", JsonPrimitive(screenOn.value), null, "mdi:tablet-dashboard"),
            sensor("presence", "Présence", "binary_sensor", JsonPrimitive(presence), "occupancy", "mdi:motion-sensor"),
        )
    }

    private suspend fun pushStates() {
        val id = webhook ?: return
        runCatching {
            webhookCall(id, "update_sensor_states", buildJsonArray {
                sensors().forEach { s ->
                    add(buildJsonObject {
                        put("unique_id", s["unique_id"]!!); put("type", s["type"]!!); put("state", s["state"]!!); put("icon", s["icon"]!!)
                    })
                }
            })
        }.onFailure { Log.w(TAG, "états", it) }
    }

    private suspend fun webhookCall(id: String, type: String, data: JsonElement) = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("type", type); put("data", data) }
        http.newCall(Request.Builder().url("${ha.baseUrl}/api/webhook/$id").post(body.toString().toRequestBody(JSON)).build()).execute().use { r ->
            // Webhook inconnu (appareil supprimé dans Home Assistant) : nouvel enregistrement au prochain passage.
            if (r.code == 410 || r.code == 404) { prefs.edit().remove("ha_webhook_id").apply() }
            if (!r.isSuccessful) error("HTTP ${r.code}")
        }
    }

    // --- Notifications -------------------------------------------------------------------------

    private fun onPush(id: String, event: JsonObject) {
        val e = (event["event"] as? JsonObject) ?: event
        e["hass_confirm_id"]?.jsonPrimitive?.contentOrNull?.let { confirm ->
            scope.launch { runCatching { ha.call(buildJsonObject { put("type", "mobile_app/push_notification_confirm"); put("webhook_id", id); put("confirm_id", confirm) }) } }
        }
        val message = e["message"]?.jsonPrimitive?.contentOrNull ?: return
        val data = e["data"] as? JsonObject
        when (message) {
            "TTS" -> speak(data?.get("tts_text")?.jsonPrimitive?.contentOrNull ?: return)
            "command_screen_on" -> commands.tryEmit(Command.ScreenOn)
            "command_camera" -> commands.tryEmit(Command.Camera(data?.get("entity")?.jsonPrimitive?.contentOrNull ?: return))
            "command_macs_mood" -> {
                val mood = data?.get("mood")?.jsonPrimitive?.contentOrNull ?: return
                val seconds = data["duration"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt() ?: 10
                fr.pergens.hamacs.App.instance.macs.commandMood(mood, seconds)
                commands.tryEmit(Command.ScreenOn)
            }
            else -> commands.tryEmit(Command.Banner(e["title"]?.jsonPrimitive?.contentOrNull, message))
        }
    }

    private fun speak(text: String) {
        commands.tryEmit(Command.ScreenOn)
        val engine = tts
        if (engine != null) { engine.speak(text, TextToSpeech.QUEUE_ADD, null, "hamacs"); return }
        var created: TextToSpeech? = null
        created = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                created?.language = Locale.getDefault()
                created?.speak(text, TextToSpeech.QUEUE_ADD, null, "hamacs")
            }
        }
        tts = created
    }

    private fun deviceId(): String = prefs.getString("ha_device_id", null) ?: java.util.UUID.randomUUID().toString().also {
        prefs.edit().putString("ha_device_id", it).apply()
    }

    companion object {
        private const val TAG = "MobileAppLink"
        private val JSON = "application/json".toMediaType()

        /** Nom du service de notification (`notify.mobile_app_<nom>`), comme Home Assistant le forme. */
        fun slug(name: String): String = java.text.Normalizer.normalize(name.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9]+"), "_").trim('_')

        /** Préférences propres à l'appareil (non exportées). */
        val LOCAL_KEYS = setOf("ha_webhook_id", "ha_webhook_url", "ha_device_id")
    }
}
