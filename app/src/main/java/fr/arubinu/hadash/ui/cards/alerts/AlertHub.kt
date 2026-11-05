package fr.arubinu.hadash.ui.cards.alerts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import fr.arubinu.hadash.config.ConfigRepository
import fr.arubinu.hadash.config.b
import fr.arubinu.hadash.config.i
import fr.arubinu.hadash.config.o
import fr.arubinu.hadash.config.objs
import fr.arubinu.hadash.config.s
import fr.arubinu.hadash.ha.EntityState
import fr.arubinu.hadash.ha.HaClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import fr.arubinu.hadash.ha.history
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/** Traductions de la carte et de son éditeur (reprises de l'AlertTicker Card). */
class AtcI18n(context: Context) {
    private val root = Json.parseToJsonElement(context.assets.open("alert_ticker/i18n.json").bufferedReader().use { it.readText() }).jsonObject
    val lang: String = Locale.getDefault().language.takeIf { root.o("card")?.containsKey(it) == true } ?: "en"
    private fun table(name: String) = root.o(name)?.o(lang) ?: JsonObject(emptyMap())
    private val card = table("card")
    private val editor = table("editor")
    private val defaultMsg = table("default_msg")
    private val app = table("app")
    private val appEn = root.o("app")?.o("en") ?: JsonObject(emptyMap())

    /** Libellé de la carte (`T`). */
    fun t(key: String): String = card.s(key) ?: key
    /** Libellé de l'éditeur (`ET`), sinon de la carte. */
    fun e(key: String): String = editor.s(key) ?: card.s(key) ?: key
    fun defaultMessage(theme: String): String? = defaultMsg.s(theme)
    /** Libellé ajouté par l'app (éditeur, synthèse vocale), avec `%s` remplacé par [args]. */
    fun a(key: String, vararg args: Any?): String {
        val text = app.s(key) ?: appEn.s(key) ?: key
        return if (args.isEmpty()) text else String.format(java.util.Locale.ROOT, text, *args)
    }
    fun themeDesc(theme: String): String? = root.o("theme_desc")?.o(theme)?.s(lang)
    fun groupLabel(cat: String): String = root.o("theme_groups")?.o(cat)?.s(lang) ?: cat
    val templates: List<Pair<String, JsonObject>> by lazy { root.o("templates")?.entries?.map { it.key to it.value.jsonObject } ?: emptyList() }
    fun templateName(t: JsonObject) = t.o("name")?.s(lang) ?: ""
    fun templateDesc(t: JsonObject) = t.o("desc")?.s(lang) ?: ""
}

/** Bannière overlay : alerte affichée par-dessus les autres écrans. */
data class OverlayAlert(val cardKey: String, val config: JsonObject, val alerts: List<Active>, val at: Long = System.currentTimeMillis())

/**
 * Fait tourner le moteur de chaque AlertTicker du tableau de bord, même hors écran : c'est ici que
 * sont gérés l'historique, le son, la synthèse vocale, les notifications push et la bannière overlay.
 */
class AlertHub(
    private val context: Context,
    private val ha: HaClient,
    private val config: ConfigRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = AtcStore(context)
    val registry = AtcRegistry()
    val templates = AtcTemplates(ha)
    val i18n by lazy { AtcI18n(context) }

    private val engines = HashMap<String, AlertEngine>()
    private val lastConfigs = HashMap<String, JsonObject>()
    private val results = HashMap<String, MutableStateFlow<EngineResult>>()
    private val _overlay = MutableSharedFlow<OverlayAlert>(extraBufferCapacity = 4)
    val overlay: SharedFlow<OverlayAlert> = _overlay.asSharedFlow()
    private val _cards = MutableStateFlow<Map<String, Pair<String, JsonObject>>>(emptyMap())
    /** Cartes AlertTicker connues : clé → (écran, configuration effective). */
    val cards: StateFlow<Map<String, Pair<String, JsonObject>>> = _cards.asStateFlow()

    fun engine(key: String): AlertEngine = synchronized(engines) {
        engines.getOrPut(key) { AlertEngine(ha, store, registry, templates) }
    }

    fun result(key: String): StateFlow<EngineResult> = mutableResult(key)

    private fun mutableResult(key: String): MutableStateFlow<EngineResult> = synchronized(results) {
        results.getOrPut(key) { MutableStateFlow(EngineResult(emptyList(), 0, 0)) }
    }

    /** Configuration effective : celle de l'éditeur (réglages du bloc) sinon dashboard.json. */
    fun effectiveConfig(card: JsonObject): JsonObject = config.blocks.value[cardKey(card)]?.o("config") ?: card

    fun start() {
        val tick = flow { while (true) { emit(System.currentTimeMillis()); delay(1000) } }
        scope.launch {
            combine(config.dashboard, config.blocks, ha.states, tick, store.version) { dash, _, states, _, _ -> dash to states }
                .conflate()
                .collect { (dash, states) -> runCatching { evaluate(dash, states) }.onFailure { android.util.Log.e("AlertHub", "evaluate", it) } }
        }
        scope.launch {
            ha.connection.collect {
                if (it == fr.arubinu.hadash.ha.ConnState.Connected) {
                    registry.refresh(ha, force = true)
                    runCatching { catchUpPersistent() }.onFailure { e -> android.util.Log.w("AlertHub", "catch-up", e) }
                }
            }
        }
    }

    /**
     * Alertes `persistent` : un déclenchement survenu pendant que la tablette était éteinte ou déconnectée
     * (ex. courrier déposé puis capteur revenu au repos) est retrouvé dans l'historique de Home Assistant
     * (24 h, après la dernière prise en compte) ; l'alerte reste alors affichée jusqu'au ✕.
     */
    private suspend fun catchUpPersistent() {
        val found = LinkedHashMap<String, Pair<String, JsonObject>>()
        config.dashboard.value.o("screens")?.forEach { (screenId, screen) -> findTickers(screen, screenId, found) }
        val now = System.currentTimeMillis()
        found.forEach { (key, pair) ->
            val engine = engine(key)
            pair.second.objs("alerts").forEachIndexed { idx, a ->
                if (!a.b("persistent") || !a.b("enabled", true) || a.s("attribute") != null) return@forEachIndexed
                val entity = a.s("entity")?.takeIf { it.isNotEmpty() } ?: return@forEachIndexed
                val skey = snoozeKey(Active(a, idx, entity))
                if (skey in store.persistent) return@forEachIndexed
                val after = maxOf(store.persistentAck[skey] ?: 0L, now - 24 * 3_600_000L)
                val spans = ha.history(listOf(entity), 24)[entity].orEmpty()
                val hit = spans.lastOrNull { it.start > after && engine.matches(it.state, a) } ?: return@forEachIndexed
                store.latch(skey, hit.start)
            }
        }
    }

    private fun evaluate(dash: JsonObject, states: Map<String, EntityState>) {
        val found = LinkedHashMap<String, Pair<String, JsonObject>>()
        dash.o("screens")?.forEach { (screenId, screen) -> findTickers(screen, screenId, found) }
        _cards.value = found
        found.forEach { (key, pair) ->
            val cfg = pair.second
            val engine = engine(key)
            if (lastConfigs[key] != cfg) { engine.reset(); lastConfigs[key] = cfg }
            val r = engine.compute(cfg, states, sideEffects = true)
            if (r.fresh.isNotEmpty()) onFresh(key, cfg, r, states)
            mutableResult(key).value = r
        }
    }

    private fun findTickers(el: JsonElement, screen: String, out: MutableMap<String, Pair<String, JsonObject>>) {
        when (el) {
            is JsonObject -> {
                if (el.s("type") in TYPES) out[cardKey(el)] = screen to effectiveConfig(el)
                el.values.forEach { findTickers(it, screen, out) }
            }
            is JsonArray -> el.forEach { findTickers(it, screen, out) }
            else -> Unit
        }
    }

    private fun onFresh(key: String, cfg: JsonObject, r: EngineResult, states: Map<String, EntityState>) {
        val engine = engine(key)
        val max = cfg.i("history_max_events") ?: 50
        val history = store.history(key)
        val now = System.currentTimeMillis()
        r.fresh.forEach { al ->
            if (r.initial) {
                // Rechargement : pas de doublon si la même entité a été enregistrée il y a moins de 5 min.
                if (history.none { it.entity == (al.entity ?: "") && now - it.ts < 5 * 60_000L }) recordHistory(key, cfg, al, states, max)
            } else {
                recordHistory(key, cfg, al, states, max)
                if (al.cfg.b("sound") || (cfg.b("sound_enabled") && al.cfg["sound"] == null)) playSound(al, cfg)
                speak(cfg, al, states, engine)
                pushNotify(cfg, al, states, engine)
            }
        }
        if (!r.initial && cfg.b("overlay_mode")) {
            val minPrio = cfg.i("overlay_min_priority")
            val shown = r.fresh.filter { al -> al.cfg.b("overlay", true) && (minPrio == null || al.priority <= minPrio) }
            if (shown.isNotEmpty()) _overlay.tryEmit(OverlayAlert(key, cfg, shown))
        }
    }

    private fun recordHistory(key: String, cfg: JsonObject, al: Active, states: Map<String, EntityState>, max: Int) {
        val engine = engine(key)
        val es = al.entity?.let { states[it] }
        val ses = al.cfg.s("secondary_entity")?.let { states[it] }
        val text = al.cfg.s("history_message") ?: al.cfg.s("message")
        store.addHistory(key, HistoryEntry(
            ts = System.currentTimeMillis(),
            message = engine.resolve(text, al, states),
            theme = al.theme,
            icon = AtcThemes[al.theme].emoji,
            entity = al.entity ?: "",
            entityName = es?.attrString("friendly_name") ?: al.entity,
            entityState = es?.let { engine.formatState(it, al.cfg.s("attribute")) },
            secondaryName = ses?.let { it.attrString("friendly_name") ?: it.entityId },
            secondaryState = ses?.let { engine.formatState(it, al.cfg.s("secondary_attribute")) },
        ), max)
    }

    // --- Son ---------------------------------------------------------------------------------

    /** Tons générés selon la catégorie (double bip critique, bip moyen, carillon OK…) ou fichier audio. */
    fun playSound(al: Active, cfg: JsonObject) {
        val url = al.cfg.s("sound_url") ?: cfg.s("sound_url")
        if (!url.isNullOrBlank()) {
            scope.launch(Dispatchers.Main) {
                runCatching {
                    MediaPlayer().apply {
                        setDataSource(ha.resolveUrl(url))
                        setOnPreparedListener { it.start() }
                        setOnCompletionListener { it.release() }
                        prepareAsync()
                    }
                }
            }
            return
        }
        val tones = when (AtcThemes[al.theme].category) {
            Category.Critical -> listOf(Triple(960.0, 0.0, 0.15) to 0.45f, Triple(960.0, 0.22, 0.15) to 0.45f)
            Category.Warning -> listOf(Triple(700.0, 0.0, 0.35) to 0.30f)
            Category.Ok -> listOf(Triple(440.0, 0.0, 0.15) to 0.20f, Triple(660.0, 0.18, 0.28) to 0.20f)
            else -> listOf(Triple(520.0, 0.0, 0.30) to 0.20f)
        }
        scope.launch(Dispatchers.IO) { runCatching { playTones(tones) } }
    }

    private fun playTones(tones: List<Pair<Triple<Double, Double, Double>, Float>>) {
        val rate = 44100
        val total = tones.maxOf { (t, _) -> t.second + t.third }
        val buf = ShortArray((total * rate).toInt() + 1)
        tones.forEach { (t, vol) ->
            val (freq, start, dur) = t
            val s0 = (start * rate).toInt()
            val n = (dur * rate).toInt()
            for (k in 0 until n) {
                // Décroissance exponentielle jusqu'à 0,001 comme exponentialRampToValueAtTime.
                val env = vol * Math.pow(0.001 / vol, k.toDouble() / n)
                val v = (sin(2 * PI * freq * k / rate) * env * Short.MAX_VALUE).toInt()
                val idx = s0 + k
                if (idx < buf.size) buf[idx] = (buf[idx] + v).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(buf.size * 2)
            .build()
        track.write(buf, 0, buf.size)
        track.play()
        Thread.sleep((total * 1000).toLong() + 200)
        track.release()
    }

    // --- Synthèse vocale et notifications -----------------------------------------------------

    private fun speak(cfg: JsonObject, al: Active, states: Map<String, EntityState>, engine: AlertEngine) {
        if (!al.cfg.b("tts") || cfg["tts_enabled"]?.let { (it as? JsonPrimitive)?.contentOrNull == "false" } == true) return
        val message = al.cfg.s("tts_message")?.let { engine.resolve(it, al, states) } ?: ttsFallback(al, states)
        if (message.isBlank()) return
        val notify = al.cfg.s("tts_notify_service") ?: cfg.s("tts_notify_service")
        if (!notify.isNullOrBlank()) {
            ha.fireService("notify", notify.removePrefix("notify."), notify = false, data = buildJsonObject {
                put("message", message)
                put("data", buildJsonObject { put("type", al.cfg.s("tts_notify_type") ?: cfg.s("tts_notify_type") ?: "tts") })
            })
            return
        }
        val player = al.cfg.s("tts_entity") ?: cfg.s("tts_entity") ?: return
        val engineId = al.cfg.s("tts_engine") ?: cfg.s("tts_engine") ?: states.keys.firstOrNull { it.startsWith("tts.") } ?: return
        ha.fireService("tts", "speak", buildJsonObject {
            put("entity_id", engineId)
            put("media_player_entity_id", player)
            put("message", message)
            cfg.s("tts_language")?.let { put("language", it) }
        }, notify = false)
    }

    private fun ttsFallback(al: Active, states: Map<String, EntityState>): String {
        val name = al.entity?.let { states[it]?.attrString("friendly_name") } ?: al.entity ?: ""
        return when (AtcThemes[al.theme].category) {
            Category.Critical -> i18n.a("tts_critical", name)
            Category.Warning -> i18n.a("tts_warning", name)
            Category.Info -> i18n.a("tts_info", name)
            Category.Ok -> i18n.a("tts_ok", name)
            Category.Timer -> i18n.a("tts_timer", name)
            else -> i18n.a("tts_alert", name)
        }
    }

    private fun pushNotify(cfg: JsonObject, al: Active, states: Map<String, EntityState>, engine: AlertEngine) {
        if (!al.cfg.b("push_notify") || cfg["push_notify_enabled"]?.let { (it as? JsonPrimitive)?.contentOrNull == "false" } == true) return
        val service = al.cfg.s("push_notify_service") ?: return
        val title = al.cfg.s("push_notify_title")?.let { engine.resolve(it, al, states) } ?: al.cfg.s("badge_label") ?: al.cfg.s("name")
        val message = al.cfg.s("push_notify_message")?.let { engine.resolve(it, al, states) } ?: engine.resolve(al.cfg.s("message"), al, states)
        if (message.isBlank()) return
        ha.fireService("notify", service.removePrefix("notify."), buildJsonObject {
            put("message", message)
            title?.let { put("title", it) }
            (al.cfg["push_notify_data"] as? JsonObject)?.let { put("data", resolveData(it, al, states, engine)) }
        }, notify = false)
    }

    private fun resolveData(e: JsonElement, al: Active, states: Map<String, EntityState>, engine: AlertEngine): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { resolveData(it.value, al, states, engine) })
        is JsonArray -> JsonArray(e.map { resolveData(it, al, states, engine) })
        is JsonPrimitive -> if (e.isString) JsonPrimitive(engine.resolve(e.content, al, states)) else e
        else -> e
    }

    companion object {
        val TYPES = setOf("alert_ticker", "ticker")
        fun cardKey(card: JsonObject): String = card.s("id") ?: "alert_ticker"
    }
}
