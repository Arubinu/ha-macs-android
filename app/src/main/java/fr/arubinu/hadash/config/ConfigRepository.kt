package fr.arubinu.hadash.config

import android.content.Context
import android.util.Log
import fr.arubinu.hadash.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

data class Settings(
    val haUrl: String,
    val haToken: String,
    val voiceUrl: String,
    val keepScreenOn: Boolean,
    /** Échelle du texte ; 0 = suivre le réglage du système. */
    val textScale: Float,
    /** Retour à l'écran d'accueil après ce délai sans activité (0 = jamais). */
    val idleSeconds: Int,
    /** Détection de présence par la caméra frontale (repousse le retour à l'accueil). */
    val presenceDetection: Boolean,
    /** 0 = faible, 1 = moyenne, 2 = élevée. */
    val presenceSensitivity: Int,
    /** Atténuation de l'écran après ce délai sans activité (0 = jamais). */
    val dimSeconds: Int,
    /** Luminosité atténuée, de 0.01 à 1. */
    val dimLevel: Float,
    /** Caméras et captures : plein écran dès le premier appui (sinon d'abord dans la carte). */
    val tapFullscreen: Boolean,
    /** Plein écran : remplir tout l'écran (bords rognés, sans bandes noires) plutôt qu'afficher l'image entière. */
    val fullscreenFill: Boolean,
    /** Couleur principale (#rrggbb) imposée à tout le tableau de bord et à M.A.C.S. ; vide = celle du thème. */
    val accentColor: String = "",
    /** Kiosk mode: the app cannot be left (nor the tablet switched off) without the access code. */
    val kioskEnabled: Boolean = false,
)

/**
 * Charge la configuration du dashboard : URL distante (rechargeable à chaud) → copie locale en cache
 * → fichier par défaut embarqué dans l'APK.
 */
class ConfigRepository(private val context: Context, private val http: OkHttpClient) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; allowTrailingComma = true; allowComments = true }
    private val cache = File(context.filesDir, "dashboard.json")
    /** Avatars des membres créés sur la tablette (inclus dans l'export). */
    val avatars = AvatarStore(context)

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _dashboard = MutableStateFlow(loadLocal())
    /** dashboard.json tel quel (sans aucune surcharge du mode édition). */
    val rawDashboard: StateFlow<JsonObject> = _dashboard.asStateFlow()

    private fun readSettings() = Settings(
        haUrl = prefs.getString("ha_url", null) ?: BuildConfig.DEFAULT_HA_URL,
        haToken = prefs.getString("ha_token", null) ?: BuildConfig.DEFAULT_HA_TOKEN,
        voiceUrl = prefs.getString("voice_url", null) ?: BuildConfig.DEFAULT_VOICE_URL,
        keepScreenOn = prefs.getBoolean("keep_screen_on", true),
        textScale = prefs.getFloat("text_scale", 0f),
        idleSeconds = prefs.getInt("idle_seconds", 120),
        presenceDetection = prefs.getBoolean("presence_detection", true),
        presenceSensitivity = prefs.getInt("presence_sensitivity", 1),
        dimSeconds = prefs.getInt("dim_seconds", 0),
        dimLevel = prefs.getFloat("dim_level", 0.1f),
        tapFullscreen = prefs.getBoolean("tap_fullscreen", false),
        fullscreenFill = prefs.getBoolean("fullscreen_fill", false),
        accentColor = prefs.getString("accent_color", null) ?: "",
        kioskEnabled = prefs.getBoolean("kiosk_enabled", false),
    )

    fun save(s: Settings) {
        prefs.edit()
            .putString("ha_url", s.haUrl.trim())
            .putString("ha_token", s.haToken.trim())
            .putString("voice_url", s.voiceUrl.trim())
            .putBoolean("keep_screen_on", s.keepScreenOn)
            .putFloat("text_scale", s.textScale)
            .putInt("idle_seconds", s.idleSeconds)
            .putBoolean("presence_detection", s.presenceDetection)
            .putInt("presence_sensitivity", s.presenceSensitivity)
            .putInt("dim_seconds", s.dimSeconds)
            .putFloat("dim_level", s.dimLevel)
            .putBoolean("tap_fullscreen", s.tapFullscreen)
            .putBoolean("fullscreen_fill", s.fullscreenFill)
            .putString("accent_color", s.accentColor)
            .putBoolean("kiosk_enabled", s.kioskEnabled)
            .apply()
        _settings.value = readSettings()
    }

    /** Modifie un réglage et l'applique immédiatement. */
    fun update(change: (Settings) -> Settings) = save(change(_settings.value))

    // --- Sauvegarde complète ------------------------------------------------------------------

    /**
     * Toute la configuration dans un seul document : réglages de l'app (connexion, affichage, veille,
     * code, réglages des blocs…) et agencement du tableau de bord.
     */
    fun exportAll(): String {
        val values = prefs.all.mapValues { (_, v) ->
            when (v) {
                is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
                is Number -> kotlinx.serialization.json.JsonPrimitive(v)
                else -> kotlinx.serialization.json.JsonPrimitive(v?.toString())
            }
        }
        val doc = JsonObject(
            mapOf(
                "format" to kotlinx.serialization.json.JsonPrimitive(EXPORT_FORMAT),
                "version" to kotlinx.serialization.json.JsonPrimitive(1),
                "app_version" to kotlinx.serialization.json.JsonPrimitive(BuildConfig.VERSION_NAME),
                "settings" to JsonObject(values),
                "dashboard" to _dashboard.value,
                "avatars" to avatars.export(),
            )
        )
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), doc)
    }

    /** Remplace toute la configuration par celle du fichier. Retourne un message d'erreur éventuel. */
    fun importAll(text: String): String? = runCatching {
        val doc = json.parseToJsonElement(text).jsonObject
        require(doc.s("format") == EXPORT_FORMAT) { context.getString(fr.arubinu.hadash.R.string.import_unknown_format) }
        val dashboard = doc.o("dashboard") ?: error(context.getString(fr.arubinu.hadash.R.string.import_missing_layout))
        val settings = doc.o("settings") ?: JsonObject(emptyMap())
        // Nombres : type lu par l'app (Float ou Int). Oui/non : valeur JSON non textuelle (true/false).
        val types = mapOf(
            "text_scale" to "f", "dim_level" to "f", "idle_seconds" to "i", "presence_sensitivity" to "i", "dim_seconds" to "i",
        )
        prefs.edit().apply {
            clear()
            settings.forEach { (k, v) ->
                val p = v as? kotlinx.serialization.json.JsonPrimitive ?: return@forEach
                when {
                    types[k] == "f" -> putFloat(k, p.content.toFloat())
                    types[k] == "i" -> putInt(k, p.content.toDouble().toInt())
                    !p.isString && (p.content == "true" || p.content == "false") -> putBoolean(k, p.content.toBoolean())
                    else -> putString(k, p.content)
                }
            }
        }.apply()
        cache.writeText(dashboard.toString())
        avatars.import(doc.o("avatars"))
        _dashboard.value = dashboard
        _settings.value = readSettings()
        _blocks.value = readBlocks()
        publish()
        null
    }.getOrElse { it.message ?: it.javaClass.simpleName }

    // --- Code d'accès (global) ---------------------------------------------------------------

    /** Code demandé par les blocs protégés : réglage de l'app, sinon `access_code` de la config, sinon 0000. */
    fun accessCode(): String =
        prefs.getString("access_code", null) ?: _dashboard.value.s("access_code") ?: "0000"

    fun setAccessCode(code: String) {
        prefs.edit().putString("access_code", code).apply()
    }

    // --- Assistant de premier démarrage -------------------------------------------------------

    private val _setupPending = MutableStateFlow(!prefs.getBoolean("setup_done", false))
    /** L'assistant de configuration est à afficher (premier démarrage, ou relancé depuis les réglages). */
    val setupPending: StateFlow<Boolean> = _setupPending.asStateFlow()

    fun startSetup() { _setupPending.value = true }

    fun finishSetup() {
        prefs.edit().putBoolean("setup_done", true).apply()
        _setupPending.value = false
    }

    // --- Réglages propres à chaque bloc (mode édition) ---------------------------------------

    /** Surcharges par bloc telles qu'enregistrées (clé = entity_id d'origine, `member:<id>`…). */
    private val _blocks = MutableStateFlow(readBlocks())
    val rawBlocks: StateFlow<Map<String, JsonObject>> = _blocks.asStateFlow()

    private val _effectiveBlocks = MutableStateFlow(BlockOverrides.aliases(_blocks.value))
    /**
     * Surcharges par bloc, également accessibles par la NOUVELLE entité quand l'entité d'un bloc a été
     * changée (code d'accès, flux permanent, hauteur… suivent l'entité choisie).
     */
    val blocks: StateFlow<Map<String, JsonObject>> = _effectiveBlocks.asStateFlow()

    private val _effectiveDashboard = MutableStateFlow(BlockOverrides.apply(_dashboard.value, _blocks.value))
    /** Configuration utilisée par l'affichage : dashboard.json + entités choisies en mode édition. */
    val dashboard: StateFlow<JsonObject> = _effectiveDashboard.asStateFlow()

    private val _sourceDashboard = MutableStateFlow(BlockOverrides.apply(_dashboard.value, _blocks.value, structuralOnly = true))
    /**
     * Base des éditeurs de blocs : dashboard.json + structure modifiée (cartes ajoutées, retirées ou déplacées
     * dans un carrousel), sans les autres surcharges, qui restent comparées à cette base.
     */
    val sourceDashboard: StateFlow<JsonObject> = _sourceDashboard.asStateFlow()

    /** Recalcule la configuration effective après une modification du dashboard ou des surcharges. */
    private fun publish() {
        _effectiveBlocks.value = BlockOverrides.aliases(_blocks.value)
        _effectiveDashboard.value = BlockOverrides.apply(_dashboard.value, _blocks.value)
        _sourceDashboard.value = BlockOverrides.apply(_dashboard.value, _blocks.value, structuralOnly = true)
    }

    /** Clé d'enregistrement d'un bloc désigné par son entité actuelle (éventuellement remplacée). */
    fun resolveKey(key: String): String = BlockOverrides.resolve(_blocks.value, key)

    /**
     * Remplace un champ de l'objet de configuration [key] (entité, capteur, liste d'entités…) ;
     * null = revenir à la valeur de dashboard.json.
     */
    fun setBlockField(key: String, field: String, value: kotlinx.serialization.json.JsonElement?) {
        val k = resolveKey(key)
        val current = _blocks.value[k] ?: JsonObject(emptyMap())
        val set = current["set"] as? JsonObject ?: JsonObject(emptyMap())
        val newSet = if (value == null) JsonObject(set - field) else JsonObject(set + (field to value))
        val updated = if (newSet.isEmpty()) JsonObject(current - "set") else JsonObject(current + ("set" to newSet))
        val all = (_blocks.value - k).let { if (updated.isEmpty()) it else it + (k to updated) }
        prefs.edit().putString("block_overrides", JsonObject(all).toString()).apply()
        _blocks.value = all
        publish()
    }

    private fun readBlocks(): Map<String, JsonObject> = runCatching {
        json.parseToJsonElement(prefs.getString("block_overrides", "{}")!!).jsonObject
            .mapValues { it.value.jsonObject }
    }.getOrDefault(emptyMap())

    fun setBlockOption(key: String, option: String, value: Boolean?) =
        setBlockValue(key, option, value?.let { kotlinx.serialization.json.JsonPrimitive(it) })

    /** Réglage d'un bloc (null = revenir à la valeur de dashboard.json). */
    fun setBlockValue(key0: String, option: String, value: kotlinx.serialization.json.JsonPrimitive?) {
        val key = resolveKey(key0)
        val current = _blocks.value[key] ?: JsonObject(emptyMap())
        val updated = if (value == null) JsonObject(current - option)
        else JsonObject(current + (option to value))
        val all = (_blocks.value - key).let { if (updated.isEmpty()) it else it + (key to updated) }
        prefs.edit().putString("block_overrides", JsonObject(all).toString()).apply()
        _blocks.value = all
        publish()
    }

    /** Configuration complète d'une carte modifiée dans son éditeur (null = revenir à dashboard.json). */
    fun setBlockConfig(key0: String, config: JsonObject?) {
        val key = resolveKey(key0)
        val current = _blocks.value[key] ?: JsonObject(emptyMap())
        val updated = if (config == null) JsonObject(current - "config") else JsonObject(current + ("config" to config))
        val all = (_blocks.value - key).let { if (updated.isEmpty()) it else it + (key to updated) }
        prefs.edit().putString("block_overrides", JsonObject(all).toString()).apply()
        _blocks.value = all
        publish()
    }

    private fun loadLocal(): JsonObject {
        val text = runCatching { cache.takeIf { it.exists() }?.readText() }.getOrNull()
            ?: context.assets.open("dashboard.json").bufferedReader().use { it.readText() }
        return runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
            Log.e(TAG, "Config locale invalide, retour à la config embarquée", it)
            json.parseToJsonElement(context.assets.open("dashboard.json").bufferedReader().use { r -> r.readText() }).jsonObject
        }
    }

    companion object {
        private const val TAG = "ConfigRepository"
        private const val EXPORT_FORMAT = "ha-android-dashboard"
    }
}
