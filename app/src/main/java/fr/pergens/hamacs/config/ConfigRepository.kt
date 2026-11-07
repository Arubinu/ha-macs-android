package fr.pergens.hamacs.config

import android.content.Context
import android.util.Log
import fr.pergens.hamacs.BuildConfig
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
    /** Mode nuit : écran noir (ou très atténué) plus vite la nuit ; un contact ou une présence le rallume. */
    val nightEnabled: Boolean = false,
    /** Début / fin de la nuit : "sun" (coucher / lever du soleil, lus dans sun.sun) ou "HH:MM". */
    val nightStart: String = "sun",
    val nightEnd: String = "sun",
    /** Inactivité avant la mise en veille de nuit. */
    val nightSeconds: Int = 30,
    /** Écran noir (sinon luminosité [nightLevel]). */
    val nightBlack: Boolean = true,
    val nightLevel: Float = 0.02f,
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
        haUrl = prefs.getString("ha_url", null) ?: "http://homeassistant.local:8123",
        haToken = prefs.getString("ha_token", null) ?: "",
        voiceUrl = prefs.getString("voice_url", null) ?: "ws://homeassistant.local:8765/ws",
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
        nightEnabled = prefs.getBoolean("night_enabled", false),
        nightStart = prefs.getString("night_start", null) ?: "sun",
        nightEnd = prefs.getString("night_end", null) ?: "sun",
        nightSeconds = prefs.getInt("night_seconds", 30),
        nightBlack = prefs.getBoolean("night_black", true),
        nightLevel = prefs.getFloat("night_level", 0.02f),
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
            .putBoolean("night_enabled", s.nightEnabled)
            .putString("night_start", s.nightStart)
            .putString("night_end", s.nightEnd)
            .putInt("night_seconds", s.nightSeconds)
            .putBoolean("night_black", s.nightBlack)
            .putFloat("night_level", s.nightLevel)
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
        val P = { v: Any? -> kotlinx.serialization.json.JsonPrimitive(v?.toString()) }
        // Compteur d'erreurs et codes à redéfinir : propres à l'appareil, non exportés.
        val local = prefs.all.filterKeys {
            it != "pin_fails" && it != "pin_lock_until" && it != CODES_TO_REPLACE && it !in fr.pergens.hamacs.ha.MobileAppLink.LOCAL_KEYS
        }
        // Code d'accès venu de l'agencement (racine `access_code`) : exporté chiffré avec les autres, pas en clair.
        val codes = local.filter { (k, v) -> isCode(k) && v is String && v.isNotEmpty() }.mapValues { it.value as String }.toMutableMap()
        if ("access_code" !in codes) _dashboard.value.s("access_code")?.takeIf { it.isNotEmpty() }?.let { codes["access_code"] = it }
        val values = local.filterKeys { !isCode(it) }.mapValues { (k, v) ->
            when {
                // Jeton Home Assistant et codes chiffrés avec une clé propre à cet appareil : ils ne se déchiffrent qu'ici.
                k in SECRETS && v is String && v.isNotBlank() -> P(DeviceSecret.encrypt(context, v))
                v is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
                v is Number -> kotlinx.serialization.json.JsonPrimitive(v)
                else -> P(v)
            }
        } + codes.mapValues { P(DeviceSecret.encrypt(context, it.value)) }
        // Codes identiques regroupés : sur une autre tablette, un seul code de remplacement est demandé par groupe.
        val distinct = codes.values.distinct()
        val groups = codes.mapValues { kotlinx.serialization.json.JsonPrimitive(distinct.indexOf(it.value)) }
        val doc = JsonObject(
            mapOf(
                "format" to P(EXPORT_FORMAT),
                "version" to kotlinx.serialization.json.JsonPrimitive(1),
                "app_version" to P(BuildConfig.VERSION_NAME),
                "settings" to JsonObject(values),
                "code_groups" to JsonObject(groups),
                "dashboard" to JsonObject(_dashboard.value - "access_code"),
                "avatars" to avatars.export(),
            )
        )
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), doc)
    }

    /**
     * Dernier import : jeton Home Assistant chiffré pour un autre appareil (ou absent) ; il faut le saisir à nouveau.
     */
    var importNeedsToken = false
        private set

    // --- Codes ---------------------------------------------------------------------------------

    /** Clés des codes (chiffrés à l'export) : accès, maître, codes propres aux membres. */
    private fun isCode(key: String) = key == "access_code" || key == "master_code" || key.startsWith("member_code:")

    /**
     * Codes à redéfinir après l'import d'une sauvegarde d'une autre tablette (illisibles ici), regroupés : un code de
     * remplacement par groupe de codes identiques. Conservé jusqu'à ce qu'ils soient tous redéfinis.
     */
    private val _codesToReplace = MutableStateFlow(readCodesToReplace())
    val codesToReplace: StateFlow<List<List<String>>> = _codesToReplace.asStateFlow()

    private fun readCodesToReplace(): List<List<String>> =
        prefs.getString(CODES_TO_REPLACE, null).orEmpty().split('|').filter { it.isNotEmpty() }.map { it.split(',') }

    private fun writeCodesToReplace(groups: List<List<String>>) {
        prefs.edit().putString(CODES_TO_REPLACE, groups.joinToString("|") { it.joinToString(",") }).apply()
        _codesToReplace.value = groups
    }

    /** Donne [code] à toutes les clés du premier groupe à redéfinir. */
    fun replaceCodes(group: List<String>, code: String) {
        prefs.edit().apply { group.forEach { putString(it, code) } }.apply()
        writeCodesToReplace(_codesToReplace.value.filter { it != group })
    }

    /** Code propre à un membre (null = aucun : le code d'accès est demandé). */
    fun memberCode(id: String): String? = prefs.getString("member_code:$id", null)?.takeIf { it.isNotEmpty() }

    fun setMemberCode(id: String, code: String?) {
        prefs.edit().apply { if (code.isNullOrEmpty()) remove("member_code:$id") else putString("member_code:$id", code) }.apply()
    }

    /** Remplace toute la configuration par celle du fichier. Retourne un message d'erreur éventuel. */
    fun importAll(text: String): String? = runCatching {
        importNeedsToken = false
        val doc = json.parseToJsonElement(text).jsonObject
        require(doc.s("format") == EXPORT_FORMAT) { context.getString(fr.pergens.hamacs.R.string.import_unknown_format) }
        val dashboard = doc.o("dashboard") ?: error(context.getString(fr.pergens.hamacs.R.string.import_missing_layout))
        val settings = doc.o("settings") ?: JsonObject(emptyMap())
        val groupOf = doc.o("code_groups")
        // Codes chiffrés pour une autre tablette : à redéfinir, un code de remplacement par groupe de codes identiques.
        val unreadable = settings.filter { (k, v) ->
            isCode(k) && (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { DeviceSecret.isEncrypted(it) && DeviceSecret.decrypt(context, it) == null } == true
        }.keys
        val missing = unreadable.groupBy { (groupOf?.get(it) as? kotlinx.serialization.json.JsonPrimitive)?.content ?: it }.values
            .map { keys -> keys.sortedBy { if (it == "access_code") 0 else if (it == "master_code") 1 else 2 } }
            .sortedBy { g -> if ("access_code" in g) 0 else if ("master_code" in g) 1 else 2 }
        // Nombres : type lu par l'app (Float ou Int). Oui/non : valeur JSON non textuelle (true/false).
        val types = mapOf(
            "text_scale" to "f", "dim_level" to "f", "idle_seconds" to "i", "presence_sensitivity" to "i", "dim_seconds" to "i",
            "night_seconds" to "i", "night_level" to "f",
        )
        prefs.edit().apply {
            clear()
            settings.forEach { (k, v) ->
                val p = v as? kotlinx.serialization.json.JsonPrimitive ?: return@forEach
                when {
                    // Jeton chiffré : déchiffré avec la clé de cet appareil ; autre appareil → vide (à saisir).
                    // En-têtes du proxy : de même (autre appareil → à saisir à nouveau).
                    k in SECRETS && DeviceSecret.isEncrypted(p.content) -> putString(k, DeviceSecret.decrypt(context, p.content).orEmpty())
                    // Codes chiffrés : déchiffrés ici, sinon à redéfinir (rien n'est écrit, pas de code par défaut).
                    isCode(k) && DeviceSecret.isEncrypted(p.content) -> DeviceSecret.decrypt(context, p.content)?.let { putString(k, it) }
                    types[k] == "f" -> putFloat(k, p.content.toFloat())
                    types[k] == "i" -> putInt(k, p.content.toDouble().toInt())
                    !p.isString && (p.content == "true" || p.content == "false") -> putBoolean(k, p.content.toBoolean())
                    else -> putString(k, p.content)
                }
            }
            // Pas de jeton utilisable : vide (et non absent, sinon le jeton par défaut du build serait repris).
            val token = (settings["ha_token"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (token == null || (DeviceSecret.isEncrypted(token) && DeviceSecret.decrypt(context, token) == null)) {
                putString("ha_token", "")
                importNeedsToken = true
            }
            putString(CODES_TO_REPLACE, missing.joinToString("|") { it.joinToString(",") })
        }.apply()
        _codesToReplace.value = missing
        cache.writeText(dashboard.toString())
        avatars.import(doc.o("avatars"))
        _dashboard.value = dashboard
        _settings.value = readSettings()
        proxyHeaders = readProxyHeaders()
        _blocks.value = readBlocks()
        publish()
        null
    }.getOrElse { it.message ?: it.javaClass.simpleName }

    // --- Proxy inverse ---------------------------------------------------------------------------

    /**
     * En-têtes ajoutés à chaque requête vers Home Assistant (API, WebSocket, images, vidéos, sons), pour un accès par
     * un proxy inverse qui authentifie les applications : Pangolin (`P-Access-Token-Id` / `P-Access-Token`),
     * Cloudflare Access (`CF-Access-Client-Id` / `CF-Access-Client-Secret`)… Chiffrés à l'export, comme le jeton.
     */
    @Volatile var proxyHeaders: List<Pair<String, String>> = readProxyHeaders()
        private set

    private fun readProxyHeaders(): List<Pair<String, String>> = runCatching {
        json.parseToJsonElement(prefs.getString("proxy_headers", null) ?: "{}").jsonObject
            .map { (k, v) -> k to ((v as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "") }
    }.getOrDefault(emptyList())

    fun setProxyHeaders(list: List<Pair<String, String>>) {
        val valid = list.map { it.first.trim() to it.second.trim() }.filter { HEADER_NAME.matches(it.first) && it.second.isNotEmpty() }
        prefs.edit().apply {
            if (valid.isEmpty()) remove("proxy_headers")
            else putString("proxy_headers", JsonObject(valid.associate { it.first to kotlinx.serialization.json.JsonPrimitive(it.second) }).toString())
        }.apply()
        proxyHeaders = readProxyHeaders()
    }

    // --- Code d'accès (global) ---------------------------------------------------------------

    /** Code demandé par les blocs protégés : réglage de l'app, sinon `access_code` de la config, sinon 0000. */
    fun accessCode(): String =
        prefs.getString("access_code", null) ?: _dashboard.value.s("access_code") ?: "0000"

    fun setAccessCode(code: String) {
        prefs.edit().putString("access_code", code).apply()
    }

    // --- Essais de code ------------------------------------------------------------------------
    // Compteur commun à tous les codes vérifiés par l'app (maître, accès, membres), conservé au redémarrage :
    // à partir de 3 erreurs, attente de 30 s doublée à chaque nouvelle erreur (15 min au plus).

    /** Fin du blocage en cours (ms, horloge murale), 0 si aucun. */
    fun pinLockedUntil(): Long {
        val until = prefs.getLong("pin_lock_until", 0L)
        val now = System.currentTimeMillis()
        // Horloge reculée : on ne bloque jamais plus que le maximum.
        return if (until <= now) 0L else minOf(until, now + PIN_MAX_LOCK)
    }

    fun pinFailed() {
        val fails = prefs.getInt("pin_fails", 0) + 1
        val edit = prefs.edit().putInt("pin_fails", fails)
        if (fails >= 3) {
            val lock = minOf(30_000L shl minOf(fails - 3, 10), PIN_MAX_LOCK)
            edit.putLong("pin_lock_until", System.currentTimeMillis() + lock)
        }
        edit.apply()
    }

    fun pinSucceeded() {
        prefs.edit().remove("pin_fails").remove("pin_lock_until").apply()
    }

    /** Code maître (vide = aucun) : demandé pour ouvrir les réglages et le menu du kiosque. */
    fun masterCode(): String = prefs.getString("master_code", null).orEmpty()

    fun setMasterCode(code: String) {
        prefs.edit().putString("master_code", code).apply()
        masterCodeVersion.intValue++
    }

    /** Change à chaque modification du code maître (écrans qui affichent s'il est défini). */
    val masterCodeVersion = androidx.compose.runtime.mutableIntStateOf(0)

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
        private const val PIN_MAX_LOCK = 15 * 60_000L
        private const val CODES_TO_REPLACE = "codes_to_replace"
        /** Réglages secrets chiffrés à l'export (lisibles seulement sur l'appareil d'origine). */
        private val SECRETS = setOf("ha_token", "proxy_headers")
        /** Nom d'en-tête HTTP valide (token RFC 7230). */
        val HEADER_NAME = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}")
    }
}
