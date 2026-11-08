package fr.pergens.hamacs.ui.cards.alerts

import android.content.Context
import fr.pergens.hamacs.config.b
import fr.pergens.hamacs.config.d
import fr.pergens.hamacs.config.i
import fr.pergens.hamacs.config.o
import fr.pergens.hamacs.config.objs
import fr.pergens.hamacs.config.s
import fr.pergens.hamacs.config.strs
import fr.pergens.hamacs.ha.EntityState
import fr.pergens.hamacs.ha.HaClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Moteur de l'AlertTicker Card (port de `_computeActiveAlerts` et de ses aides) : expansion des filtres
 * d'entités, conditions ET/OU, on_change, trigger_delay, auto_dismiss_after, persistent, snooze, dismiss,
 * plage horaire, regroupement, tri par priorité.
 */

/** Une diapositive active : alerte (éventuellement développée d'un filtre) ou résumé de groupe. */
data class Active(
    /** Configuration de l'alerte ; pour un filtre, `entity` et `message` sont déjà substitués. */
    val cfg: JsonObject,
    val idx: Int,
    val entity: String?,
    /** Alerte d'origine quand elle vient d'un filtre (entity_filter / device_class / label / zone). */
    val source: JsonObject? = null,
    val group: Group? = null,
    /** Membre d'un groupe développé (bouton ◀ pour le refermer). */
    val expandedGroupKey: String? = null,
) {
    val theme: String get() = cfg.s("theme") ?: "emergency"
    val priority: Int get() = cfg.i("priority") ?: 99
    val fromFilter: Boolean get() = source != null
}

data class Group(val key: String, val members: List<Active>, val names: List<String>)

data class EngineResult(
    val active: List<Active>,
    val snoozedCount: Int,
    val dismissedCount: Int,
    /** Alertes devenues actives depuis le calcul précédent (son, TTS, notification, bannière). */
    val fresh: List<Active> = emptyList(),
    /** Premier calcul : les alertes déjà actives vont dans l'historique, sans son ni annonce. */
    val initial: Boolean = false,
)

/** Entrée d'historique (même contenu que la carte d'origine). */
data class HistoryEntry(
    val ts: Long,
    val message: String,
    val theme: String,
    val icon: String,
    val entity: String,
    val entityName: String?,
    val entityState: String?,
    val secondaryName: String?,
    val secondaryState: String?,
)

/** Clé unique d'une alerte (`_snoozeKey`) : entité, attribut, opérateur et valeur (JSON). */
fun snoozeKey(a: Active): String {
    val st = a.cfg["state"]
    val stateJson = when {
        st == null -> "undefined"
        st is JsonNull -> "null"
        else -> st.toString()
    }
    return "${a.entity}||${a.cfg.s("attribute") ?: ""}||${a.cfg.s("operator") ?: "="}||$stateJson"
}

/** Mémoire persistante partagée (équivalent du localStorage de la carte). */
class AtcStore(context: Context) {
    private val prefs = context.getSharedPreferences("alert_ticker", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _version = MutableStateFlow(0)
    /** Incrémenté à chaque changement (snooze, dismiss, historique) pour recalculer l'affichage. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun readMap(name: String): MutableMap<String, Long> = runCatching {
        json.parseToJsonElement(prefs.getString(name, "{}")!!).jsonObject
            .mapValues { (it.value as JsonPrimitive).content.toLong() }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun writeMap(name: String, m: Map<String, Long>) {
        prefs.edit().putString(name, JsonObject(m.mapValues { JsonPrimitive(it.value) }).toString()).apply()
        _version.value++
    }

    val snoozed: MutableMap<String, Long> = readMap("snooze").filterValues { it > System.currentTimeMillis() }.toMutableMap()
    val dismissed: MutableMap<String, Long> = readMap("dismissed")
    val persistent: MutableSet<String> = runCatching {
        json.parseToJsonElement(prefs.getString("persistent", "[]")!!).jsonArray.map { (it as JsonPrimitive).content }.toMutableSet()
    }.getOrDefault(mutableSetOf())
    /** Instantané de l'état au déclenchement ({trigger_state}, {trigger_time}). */
    val triggerStates = ConcurrentHashMap<String, Triple<String, String?, Long>>()

    fun snooze(key: String, until: Long) { snoozed[key] = until; writeMap("snooze", snoozed) }
    fun resetSnooze() { snoozed.clear(); writeMap("snooze", snoozed) }
    fun dismiss(key: String, lastChanged: Long) { dismissed[key] = lastChanged; writeMap("dismissed", dismissed) }
    fun undismiss(key: String) { if (dismissed.remove(key) != null) writeMap("dismissed", dismissed) }
    fun resetDismissed() { dismissed.clear(); writeMap("dismissed", dismissed) }
    /** persistent : heure du déclenchement (mémorisée, {trigger_time}) et heure de la dernière prise en compte (✕). */
    val persistentSince: MutableMap<String, Long> = readMap("persistent_since")
    val persistentAck: MutableMap<String, Long> = readMap("persistent_ack")

    fun latch(key: String, since: Long) {
        if (persistent.add(key)) {
            prefs.edit().putString("persistent", JsonArray(persistent.map { JsonPrimitive(it) }).toString()).apply()
            persistentSince[key] = since
            writeMap("persistent_since", persistentSince)
        }
    }
    fun unlatch(key: String) {
        persistentSince.remove(key)
        persistentAck[key] = System.currentTimeMillis()
        prefs.edit().putString("persistent_since", JsonObject(persistentSince.mapValues { JsonPrimitive(it.value) }).toString()).apply()
        writeMap("persistent_ack", persistentAck)
        if (persistent.remove(key)) {
            prefs.edit().putString("persistent", JsonArray(persistent.map { JsonPrimitive(it) }).toString()).apply()
            _version.value++
        }
    }

    // --- Historique (par carte) ---
    fun history(card: String): List<HistoryEntry> = runCatching {
        json.parseToJsonElement(prefs.getString("history_$card", "[]")!!).jsonArray.map { e ->
            val o = e.jsonObject
            HistoryEntry(
                o.d("ts")?.toLong() ?: 0L, o.s("message", ""), o.s("theme", "emergency"), o.s("icon", "🔔"), o.s("entity", ""),
                o.s("entityName"), o.s("entityState"), o.s("secondaryName"), o.s("secondaryState"),
            )
        }
    }.getOrDefault(emptyList())

    fun addHistory(card: String, entry: HistoryEntry, max: Int) {
        val list = (listOf(entry) + history(card)).take(max.coerceAtLeast(1))
        val arr = JsonArray(list.map { h ->
            buildJsonObject {
                put("ts", h.ts); put("message", h.message); put("theme", h.theme); put("icon", h.icon); put("entity", h.entity)
                h.entityName?.let { put("entityName", it) }; h.entityState?.let { put("entityState", it) }
                h.secondaryName?.let { put("secondaryName", it) }; h.secondaryState?.let { put("secondaryState", it) }
            }
        })
        prefs.edit().putString("history_$card", arr.toString()).apply()
        _version.value++
    }

    fun clearHistory(card: String) {
        prefs.edit().remove("history_$card").apply()
        _version.value++
    }
}

/** Registres HA (entités, appareils, zones) pour {device}, {area}, area_filter et label_filter. */
class AtcRegistry {
    data class Ent(val deviceId: String?, val areaId: String?, val labels: List<String>, val icon: String?, val technical: Boolean = false)
    data class Dev(val name: String?, val areaId: String?)

    @Volatile var entities: Map<String, Ent> = emptyMap()
    @Volatile var devices: Map<String, Dev> = emptyMap()
    @Volatile var areas: Map<String, String> = emptyMap()
    @Volatile var labels: Map<String, String> = emptyMap()
    @Volatile private var loadedAt = 0L

    suspend fun refresh(ha: HaClient, force: Boolean = false) {
        if (!force && System.currentTimeMillis() - loadedAt < 10 * 60_000L) return
        runCatching {
            val ents = ha.call(buildJsonObject { put("type", "config/entity_registry/list") }, 30_000) as JsonArray
            entities = ents.associate { e ->
                val o = e.jsonObject
                o.s("entity_id", "") to Ent(o.s("device_id"), o.s("area_id"), o.strs("labels"), o.s("icon") ?: o.s("original_icon"), technical = o.s("entity_category") != null || o.s("hidden_by") != null || o.s("disabled_by") != null)
            }
            val devs = ha.call(buildJsonObject { put("type", "config/device_registry/list") }, 30_000) as JsonArray
            devices = devs.associate { e -> val o = e.jsonObject; o.s("id", "") to Dev(o.s("name_by_user") ?: o.s("name"), o.s("area_id")) }
            val ars = ha.call(buildJsonObject { put("type", "config/area_registry/list") }, 30_000) as JsonArray
            areas = ars.associate { e -> val o = e.jsonObject; o.s("area_id", "") to o.s("name", "") }
            runCatching {
                val lbs = ha.call(buildJsonObject { put("type", "config/label_registry/list") }, 30_000) as JsonArray
                labels = lbs.associate { e -> val o = e.jsonObject; o.s("label_id", "") to o.s("name", "") }
            }
            loadedAt = System.currentTimeMillis()
        }
    }

    fun deviceName(entity: String): String? = entities[entity]?.deviceId?.let { devices[it]?.name }
    fun areaId(entity: String): String? = entities[entity]?.let { e -> e.areaId ?: e.deviceId?.let { devices[it]?.areaId } }
    fun areaName(entity: String): String = areaId(entity)?.let { areas[it] } ?: ""
}

/** Rendu des templates Jinja via `render_template` (abonnement HA, mis à jour en direct). */
class AtcTemplates(private val ha: HaClient) {
    private val cache = ConcurrentHashMap<String, String>()
    private val subs = ConcurrentHashMap<String, () -> Unit>()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun get(tpl: String): String? {
        if (!subs.containsKey(tpl)) {
            subs[tpl] = ha.subscribe(buildJsonObject {
                put("type", "render_template"); put("template", tpl); put("strict", false)
                put("variables", JsonObject(emptyMap()))
            }) { ev ->
                val r = ev["result"] ?: return@subscribe
                cache[tpl] = (r as? JsonPrimitive)?.contentOrNull ?: r.toString()
                _version.value++
            }
        }
        return cache[tpl]
    }

    /** Résilie les abonnements qui ne servent plus. */
    fun retain(keep: Set<String>) {
        subs.keys.filter { it !in keep && !cache.containsKey(it) }.forEach { k -> subs.remove(k)?.invoke() }
    }
}

fun hasTemplate(s: String?) = s != null && (s.contains("{{") || s.contains("{%"))

/** Chemin d'attribut imbriqué : `activity.0.forecast`, `activity[0].forecast`. */
fun resolveAttrPath(attrs: JsonObject, path: String?): JsonElement? {
    if (path.isNullOrEmpty()) return null
    var cur: JsonElement? = attrs
    for (part in path.replace(Regex("\\[(\\d+)]"), ".$1").split('.')) {
        cur = when (cur) {
            is JsonObject -> cur[part]
            is JsonArray -> part.toIntOrNull()?.let { cur.getOrNull(it) }
            else -> null
        }
        if (cur == null) return null
    }
    return cur
}

/** `String(value)` de JavaScript : tableaux joints par des virgules, null → "". */
fun jsString(e: JsonElement?): String = when (e) {
    null, is JsonNull -> ""
    is JsonPrimitive -> e.contentOrNull ?: ""
    is JsonArray -> e.joinToString(",") { jsString(it) }
    is JsonObject -> "[object Object]"
}

/** Valeur brute comparée aux conditions : état, ou attribut converti comme `String(...)`. */
fun rawValue(e: EntityState, attribute: String?): String =
    if (attribute.isNullOrEmpty()) e.state else jsString(resolveAttrPath(e.attributes, attribute))

internal fun jsFloat(s: String): Double? = Regex("^\\s*[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?").find(s)?.value?.trim()?.toDoubleOrNull()

/** Horodatage ISO ou epoch (s / ms) pour les opérateurs « older » / « newer ». */
internal fun parseTimestamp(v: String): Long? {
    v.toDoubleOrNull()?.let { n -> return if (n > 1e11) n.toLong() else (n * 1000).toLong() }
    return runCatching { OffsetDateTime.parse(v).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { Instant.parse(v).toEpochMilli() }.getOrNull()
}

/**
 * Comparaison d'une condition (`_matchesState` de la carte d'origine) : nombres comparés comme nombres (préfixe
 * numérique, comme `parseFloat`), sinon texte ; `older` / `newer` : âge en secondes d'un horodatage par rapport à [now].
 */
internal fun compareState(value: String, trigger: String, op: String, now: Long = System.currentTimeMillis()): Boolean {
    val na = jsFloat(value)
    val nt = jsFloat(trigger)
    return when (op) {
        "=", "==" -> if (na != null && nt != null) na == nt else value == trigger
        "!=" -> if (na != null && nt != null) na != nt else value != trigger
        "contains" -> value.lowercase().contains(trigger.lowercase())
        "not_contains" -> !value.lowercase().contains(trigger.lowercase())
        "older", "newer" -> {
            val ms = parseTimestamp(value) ?: return false
            val thr = trigger.toDoubleOrNull() ?: return false
            val diff = (now - ms) / 1000.0
            if (op == "older") diff > thr else diff < thr
        }
        ">" -> na != null && nt != null && na > nt
        "<" -> na != null && nt != null && na < nt
        ">=" -> na != null && nt != null && na >= nt
        "<=" -> na != null && nt != null && na <= nt
        else -> value == trigger
    }
}

/**
 * Heure d'un déclenchement : « 09:58 » aujourd'hui, sinon [yesterday] (« 18:20 (hier) ») ou [date] (« 18:20 (4 nov.) »).
 */
internal fun triggerTimeLabel(
    ms: Long, today: java.time.LocalDate, zone: ZoneId, locale: Locale,
    yesterday: (String) -> String, date: (String, String) -> String,
): String {
    val t = Instant.ofEpochMilli(ms).atZone(zone)
    val time = DateTimeFormatter.ofPattern("HH:mm", locale).format(t)
    return when (t.toLocalDate()) {
        today -> time
        today.minusDays(1) -> yesterday(time)
        else -> date(time, DateTimeFormatter.ofPattern("d MMM", locale).format(t))
    }
}

/**
 * Évaluation locale d'un template simple en attendant HA : `states('x')`, `state_attr('x', 'a')`, `is_state('x', 'v')`,
 * filtres `round(n)`, `int`, `float`, `upper`, `lower`, `default(v)`. Null si l'expression n'est pas reconnue.
 */
internal fun evalJinja(expr: String, states: Map<String, EntityState>, format: (EntityState) -> String): String? {
    val parts = expr.split('|').map { it.trim() }
    val base = parts[0]
    var v: String? = null
    Regex("^states\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)$").find(base)?.let { m -> v = states[m.groupValues[1]]?.let(format) ?: "unknown" }
    if (v == null) Regex("^state_attr\\(\\s*['\"]([^'\"]+)['\"]\\s*,\\s*['\"]([^'\"]+)['\"]\\s*\\)$").find(base)?.let { m ->
        v = states[m.groupValues[1]]?.let { jsString(resolveAttrPath(it.attributes, m.groupValues[2])) } ?: ""
    }
    if (v == null) Regex("^is_state\\(\\s*['\"]([^'\"]+)['\"]\\s*,\\s*['\"]([^'\"]+)['\"]\\s*\\)$").find(base)?.let { m ->
        v = if (states[m.groupValues[1]]?.state == m.groupValues[2]) "true" else "false"
    }
    var r = v ?: return null
    parts.drop(1).forEach { f ->
        Regex("^round\\((\\d+)\\)$").find(f)?.let { m -> r = String.format(Locale.US, "%.${m.groupValues[1]}f", jsFloat(r) ?: Double.NaN); return@forEach }
        when (f) {
            "int" -> r = (jsFloat(r)?.toLong() ?: 0).toString()
            "float" -> r = (jsFloat(r) ?: Double.NaN).toString()
            "upper" -> r = r.uppercase()
            "lower" -> r = r.lowercase()
            else -> Regex("^default\\(\\s*['\"]?([^'\"]*?)['\"]?\\s*\\)$").find(f)?.let { m ->
                if (r.isEmpty() || r == "unknown" || r == "unavailable") r = m.groupValues[1]
            }
        }
    }
    return r
}

class AlertEngine(
    private val ha: HaClient,
    private val store: AtcStore,
    val registry: AtcRegistry,
    val templates: AtcTemplates,
    private val locale: Locale = Locale.getDefault(),
) {
    // Suivi on_change / délais (par « idx:entité »)
    private val prevValues = HashMap<String, String>()
    private val changeTriggers = HashMap<String, Long>()
    private var lastActiveKeys: Set<String>? = null
    private val lastActiveIdx = HashMap<Int, Boolean>()
    val expandedGroups: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    /** Réinitialise le suivi (nouvelle configuration). */
    fun reset() {
        prevValues.clear(); changeTriggers.clear(); lastActiveKeys = null; lastActiveIdx.clear()
    }

    // ------------------------------------------------------------------------------------------
    // Conditions
    // ------------------------------------------------------------------------------------------

    /** `_matchesState` : =, !=, >, <, >=, <=, contains, not_contains, older, newer, liste. */
    fun matches(value: String, cond: JsonObject): Boolean {
        val stEl = cond["state"]
        if (stEl is JsonArray) return stEl.map { jsString(it) }.contains(value)
        var trigger = (stEl as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } ?: "on"
        val op = cond.s("operator") ?: "="
        if (hasTemplate(trigger)) trigger = templates.get(trigger) ?: miniJinja(trigger)
        return compareState(value, trigger, op)
    }

    private fun conds(a: JsonObject): List<JsonObject> = when (val c = a["conditions"]) {
        is JsonArray -> c.mapNotNull { it as? JsonObject }
        is JsonObject -> if (c.s("entity") != null) listOf(c) else emptyList()
        else -> emptyList()
    }

    private fun condResults(a: JsonObject, entity: String?, states: Map<String, EntityState>): List<Boolean> = conds(a).map { cond ->
        val eid = cond.s("entity")?.let { if (it == "{entity}" || it == "this.entity_id") entity else it } ?: return@map false
        val es = states[eid] ?: return@map false
        matches(rawValue(es, cond.s("attribute")), cond)
    }

    private fun timeRangeOk(a: JsonObject): Boolean {
        val tr = a.o("time_range") ?: return true
        fun parse(s: String?): Int? = s?.split(':')?.let { p -> val h = p.getOrNull(0)?.toIntOrNull(); val m = p.getOrNull(1)?.toIntOrNull(); if (h == null || m == null) null else h * 60 + m }
        val from = parse(tr.s("from")) ?: return true
        val to = parse(tr.s("to")) ?: return true
        val now = LocalTime.now().let { it.hour * 60 + it.minute }
        return if (from <= to) now in from until to else now >= from || now < to
    }

    // ------------------------------------------------------------------------------------------
    // Calcul des alertes actives
    // ------------------------------------------------------------------------------------------

    fun compute(config: JsonObject, states: Map<String, EntityState>, sideEffects: Boolean): EngineResult {
        val alerts = config.objs("alerts")
        val testMode = config.b("test_mode")
        val now = System.currentTimeMillis()

        // 1. Expansion des filtres : une alerte par entité correspondante.
        val expanded = ArrayList<Active>()
        alerts.forEachIndexed { idx, a ->
            if (a.b("enabled", true).not() && !testMode) return@forEachIndexed
            val filter = a.s("entity_filter")?.takeIf { it.isNotEmpty() }
            val dc = a.s("device_class")?.takeIf { it.isNotEmpty() }
            val labelFilter = listOrSingle(a, "label_filter")
            val areaFilter = listOrSingle(a, "area_filter")
            if ((filter != null || dc != null || labelFilter != null || areaFilter != null) && a.s("entity").isNullOrEmpty()) {
                val matcher = filter?.let { filterMatcher(it) }
                val excluded = (a.strs("entity_filter_exclude") + a.strs("device_class_exclude")).toSet()
                states.values.asSequence().filter { e ->
                    if (e.entityId in excluded) return@filter false
                    if (dc != null && e.attrString("device_class") != dc) return@filter false
                    if (matcher != null && !matcher(e.entityId) && !matcher(e.attrString("friendly_name") ?: "")) return@filter false
                    if (labelFilter != null && labelFilter.none { it in (registry.entities[e.entityId]?.labels ?: emptyList()) }) return@filter false
                    if (areaFilter != null && registry.areaId(e.entityId).let { ar -> areaFilter.none { it == ar } }) return@filter false
                    true
                }.sortedBy { it.entityId }.forEach { e ->
                    val name = e.attrString("friendly_name") ?: e.entityId
                    val msg = (a.s("message") ?: "").replace("{entity}", e.entityId).replace("{name}", name)
                        .replace("{state}", formatState(e, a.s("attribute")))
                    val cfg = JsonObject(a + mapOf("entity" to JsonPrimitive(e.entityId), "message" to JsonPrimitive(msg)))
                    expanded += Active(cfg, idx, e.entityId, source = a)
                }
            } else expanded += Active(a, idx, a.s("entity"))
        }

        // 2. Filtrage par conditions.
        var snoozedCount = 0
        var dismissedCount = 0
        val active = expanded.filter { al ->
            val a = al.cfg
            val es = al.entity?.let { states[it] }
            if (es == null && !testMode) return@filter false
            if (testMode) return@filter true
            if (!timeRangeOk(a)) return@filter false
            val key = "${al.idx}:${al.entity}"
            val skey = snoozeKey(al)
            val hasOwnState = a["state"].let { it != null && it !is JsonNull && jsString(it).isNotEmpty() }
            if (a.b("on_change")) {
                // Déclenchement à chaque changement de valeur ; visible jusqu'à auto_dismiss_after.
                val v = rawValue(es!!, a.s("attribute"))
                val prev = prevValues.put(key, v)
                if (prev != null && prev != v) changeTriggers[key] = now
                val trig = changeTriggers[key] ?: return@filter false
                a.d("auto_dismiss_after")?.let { if (now >= trig + (it * 1000).toLong()) { changeTriggers.remove(key); return@filter false } }
                if (hasOwnState && !matches(v, a)) return@filter false
                val rs = condResults(a, al.entity, states)
                if (rs.isNotEmpty() && !(if (a.s("conditions_logic") == "or") rs.any { it } else rs.all { it })) return@filter false
            } else {
                val primaryOk = !hasOwnState || matches(rawValue(es!!, a.s("attribute")), a)
                val rs = condResults(a, al.entity, states)
                val passes = if (rs.isNotEmpty()) {
                    if (a.s("conditions_logic") == "or") (if (hasOwnState) primaryOk || rs.any { it } else rs.any { it })
                    else primaryOk && rs.all { it }
                } else primaryOk
                // persistent : reste affichée après le retour à la normale, jusqu'au ✕.
                if (!passes) return@filter a.b("persistent") && skey in store.persistent
                // Dernier changement parmi l'entité et les conditions (délais).
                var latest = es?.lastChanged ?: 0L
                conds(a).forEach { c -> c.s("entity")?.let { states[it] }?.let { if (it.lastChanged > latest) latest = it.lastChanged } }
                a.d("trigger_delay")?.takeIf { it > 0 }?.let { d -> if (now - latest < d * 1000) return@filter false }
                a.d("auto_dismiss_after")?.takeIf { it > 0 }?.let { ad ->
                    val shownAt = latest + ((a.d("trigger_delay") ?: 0.0) * 1000).toLong()
                    if (now >= shownAt + (ad * 1000).toLong()) return@filter false
                }
            }
            if (a.b("persistent")) store.latch(skey, es?.lastChanged?.takeIf { it > 0 } ?: now)
            val exp = store.snoozed[skey]
            if (exp != null && exp > now) { snoozedCount++; return@filter false }
            if (isDismissed(al, es)) { dismissedCount++; return@filter false }
            true
        }.sortedBy { it.priority }

        // 3. Nouvelles alertes : historique, son, TTS, notifications.
        var fresh = emptyList<Active>()
        var initial = false
        if (sideEffects && !testMode) {
            val keys = active.map { snoozeKey(it) }.toSet()
            val prev = lastActiveKeys
            initial = prev == null
            fresh = active.filter { prev == null || snoozeKey(it) !in prev }
            fresh.forEach { al ->
                val k = snoozeKey(al)
                if (!store.triggerStates.containsKey(k)) al.entity?.let { states[it] }?.let { es ->
                    store.triggerStates[k] = Triple(es.state, al.cfg.s("attribute")?.let { rawValue(es, it) }, now)
                }
            }
            store.triggerStates.keys.retainAll(keys)
            lastActiveKeys = keys
            // active_state_entity : input_boolean synchronisé quand l'alerte change d'état.
            val idxSet = active.map { it.idx }.toSet()
            alerts.forEachIndexed { idx, a ->
                val ent = a.s("active_state_entity") ?: return@forEachIndexed
                val on = idx in idxSet
                if (lastActiveIdx[idx] != on) {
                    lastActiveIdx[idx] = on
                    ha.fireService("input_boolean", if (on) "turn_on" else "turn_off", target = buildJsonObject { put("entity_id", ent) }, notify = false)
                }
            }
        }

        // 4. Regroupement des alertes de filtre (group: true).
        val buckets = LinkedHashMap<Int, MutableList<Active>>()
        val out = ArrayList<Active>()
        active.forEach { al -> if (al.source?.b("group") == true) buckets.getOrPut(al.idx) { mutableListOf() } += al else out += al }
        buckets.forEach { (idx, members) ->
            val src = members.first().source!!
            val gkey = "grp_$idx"
            if (gkey in expandedGroups) {
                out += members.map { m ->
                    val cfg = src.s("group_expanded_message")?.let { JsonObject(m.cfg + ("message" to JsonPrimitive(it))) } ?: m.cfg
                    m.copy(cfg = cfg, expandedGroupKey = gkey)
                }
            } else if (members.size >= (src.i("group_min") ?: 3)) {
                val names = members.map { states[it.entity]?.attrString("friendly_name") ?: it.entity ?: "" }
                fun sub(t: String) = t.replace("{count}", members.size.toString()).replace("{names}", names.joinToString(", "))
                val rawMsg = src.s("group_message") ?: "{count} alert"
                val msg = if (hasTemplate(rawMsg)) templates.get(rawMsg)?.let { sub(it) } ?: sub(miniJinja(rawMsg)) else sub(rawMsg)
                val sec = src.s("group_secondary_text")?.let { if (hasTemplate(it)) templates.get(it)?.let(::sub) ?: sub(miniJinja(it)) else sub(it) }
                val cfg = buildJsonObject {
                    put("theme", src.s("theme") ?: "warning")
                    src.s("icon")?.let { put("icon", it) }
                    put("priority", src.i("priority") ?: 3)
                    put("message", msg)
                    sec?.let { put("secondary_text", it) }
                    src.o("group_tap_action")?.let { put("tap_action", it) }
                    src.o("group_hold_action")?.let { put("hold_action", it) }
                    src.o("group_double_tap_action")?.let { put("double_tap_action", it) }
                }
                out += Active(cfg, idx, null, group = Group(gkey, members, names))
            } else out += members
        }
        val final = if (buckets.isEmpty()) out else out.sortedBy { it.cfg.i("priority") ?: 3 }
        return EngineResult(final, snoozedCount, dismissedCount, fresh, initial)
    }

    private fun isDismissed(al: Active, es: EntityState?): Boolean {
        val k = snoozeKey(al)
        val stored = store.dismissed[k] ?: return false
        if (es == null) return false
        if (stored == es.lastChanged) return true
        store.undismiss(k)
        return false
    }

    private fun listOrSingle(a: JsonObject, key: String): List<String>? = when (val v = a[key]) {
        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.takeIf { it.isNotEmpty() }
        is JsonPrimitive -> v.contentOrNull?.takeIf { it.isNotEmpty() }?.let { listOf(it) }
        else -> null
    }

    // ------------------------------------------------------------------------------------------
    // Actions sur les alertes
    // ------------------------------------------------------------------------------------------

    fun snooze(al: Active, hours: Double) {
        val until = System.currentTimeMillis() + (hours * 3_600_000).toLong()
        val targets = al.group?.members ?: listOf(al)
        targets.forEach { store.snooze(snoozeKey(it), until) }
    }

    fun dismiss(al: Active, states: Map<String, EntityState>) {
        val es = al.entity?.let { states[it] } ?: return
        store.dismiss(snoozeKey(al), es.lastChanged)
    }

    fun dismissPersistent(al: Active, states: Map<String, EntityState>) {
        store.unlatch(snoozeKey(al))
        dismiss(al, states)
    }

    // ------------------------------------------------------------------------------------------
    // Messages
    // ------------------------------------------------------------------------------------------

    /** `_formatStateValue` : état traduit et formaté comme le frontend HA (nombre localisé + unité). */
    fun formatState(e: EntityState, attribute: String?): String {
        if (!attribute.isNullOrEmpty()) {
            val v = resolveAttrPath(e.attributes, attribute)
            val p = v as? JsonPrimitive
            if (p != null && !p.isString) p.contentOrNull?.toDoubleOrNull()?.let { return formatNumber(it) }
            return jsString(v)
        }
        val n = e.state.toDoubleOrNull()
        if (n != null && e.domain != "input_text") {
            val unit = e.unit
            val num = formatNumber(n, e.attrDouble("display_precision")?.toInt())
            return if (unit.isNullOrEmpty()) num else num + blankBefore(unit) + unit
        }
        if (e.attrString("device_class") == "timestamp" && e.state.contains('T')) {
            runCatching {
                val t = OffsetDateTime.parse(e.state).atZoneSameInstant(ZoneId.systemDefault())
                return DateTimeFormatter.ofPattern("d MMMM yyyy à HH:mm", locale).format(t)
            }
        }
        return translateState(e)
    }

    private fun formatNumber(n: Double, decimals: Int? = null): String {
        val f = NumberFormat.getNumberInstance(locale)
        if (decimals != null) { f.minimumFractionDigits = decimals; f.maximumFractionDigits = decimals } else f.maximumFractionDigits = 10
        f.isGroupingUsed = true
        return f.format(n)
    }

    private fun blankBefore(unit: String): String = when {
        unit == "°" -> ""
        unit == "%" -> if (locale.language in setOf("cs", "de", "fi", "fr", "sk", "sv")) " " else ""
        else -> " "
    }

    /** Traduction des états courants (le frontend HA utilise ses fichiers de traduction). */
    private fun translateState(e: EntityState): String {
        val fr = locale.language == "fr"
        val s = e.state
        val dc = e.attrString("device_class")
        // Traductions de Home Assistant (toutes ses langues) ; tables ci-dessous en secours.
        ha.translateState(e.domain, dc, s)?.let { return it }
        if (e.domain == "binary_sensor") {
            val on = s == "on"
            if (s == "on" || s == "off") return when (dc) {
                "door", "window", "opening", "garage_door" -> if (fr) (if (on) "Ouvert" else "Fermé") else (if (on) "Open" else "Closed")
                "motion", "occupancy", "presence", "sound", "vibration", "gas", "smoke", "carbon_monoxide", "tamper" ->
                    if (fr) (if (on) "Détecté" else "Non détecté") else (if (on) "Detected" else "Clear")
                "battery" -> if (fr) (if (on) "Faible" else "Normal") else (if (on) "Low" else "Normal")
                "moisture" -> if (fr) (if (on) "Humide" else "Sec") else (if (on) "Wet" else "Dry")
                "connectivity" -> if (fr) (if (on) "Connecté" else "Déconnecté") else (if (on) "Connected" else "Disconnected")
                "lock" -> if (fr) (if (on) "Déverrouillé" else "Verrouillé") else (if (on) "Unlocked" else "Locked")
                "problem" -> if (fr) (if (on) "Problème" else "OK") else (if (on) "Problem" else "OK")
                "plug", "power" -> if (fr) (if (on) "Branché" else "Débranché") else (if (on) "Plugged in" else "Unplugged")
                else -> if (fr) (if (on) "Activé" else "Désactivé") else (if (on) "On" else "Off")
            }
        }
        val map = if (fr) mapOf(
            "on" to "Activé", "off" to "Désactivé", "unavailable" to "Indisponible", "unknown" to "Inconnu",
            "home" to "À la maison", "not_home" to "Absent", "open" to "Ouvert", "closed" to "Fermé", "opening" to "Ouverture",
            "closing" to "Fermeture", "locked" to "Verrouillé", "unlocked" to "Déverrouillé", "playing" to "Lecture en cours",
            "paused" to "En pause", "idle" to "Inactif", "standby" to "En veille", "active" to "Actif", "disarmed" to "Désarmé",
            "armed_away" to "Armé (absent)", "armed_home" to "Armé (présent)", "armed_night" to "Armé (nuit)", "triggered" to "Déclenché",
            "pending" to "En attente", "arming" to "Armement", "above_horizon" to "Au-dessus de l'horizon", "below_horizon" to "Sous l'horizon",
        ) else mapOf(
            "on" to "On", "off" to "Off", "unavailable" to "Unavailable", "unknown" to "Unknown", "home" to "Home",
            "not_home" to "Away", "open" to "Open", "closed" to "Closed", "locked" to "Locked", "unlocked" to "Unlocked",
            "playing" to "Playing", "paused" to "Paused", "idle" to "Idle", "active" to "Active", "disarmed" to "Disarmed",
        )
        return map[s] ?: s
    }

    /**
     * `_resolveMessage` : {state}, {name}, {entity}, {device}, {area}, {timer}, {trigger_state},
     * {trigger_attribute}, {trigger_time} et templates `{{ }}` rendus par HA.
     */
    fun resolve(text: String?, al: Active, states: Map<String, EntityState>): String {
        var msg = text ?: return ""
        if (!msg.contains('{')) return msg
        val ent = al.entity
        val es = ent?.let { states[it] }
        fun subEntity(m: String): String {
            var r = m
            if (es != null) {
                r = r.replace("{state}", formatState(es, al.cfg.s("attribute")))
                    .replace("{name}", es.attrString("friendly_name") ?: ent).replace("{entity}", ent)
            }
            if (ent != null && (r.contains("{device}") || r.contains("{area}"))) {
                r = r.replace("{device}", registry.deviceName(ent) ?: ent).replace("{area}", registry.areaName(ent))
            }
            return r
        }
        if (hasTemplate(msg)) {
            if (ent != null) msg = subEntity(msg)
            templates.get(msg)?.let { return it }
            msg = Regex("\\{\\{\\s*([\\s\\S]*?)\\s*\\}\\}").replace(msg) { m -> evalJinja(m.groupValues[1].trim(), states) ?: m.value }
        }
        if (msg.contains("{timer}")) msg = msg.replace("{timer}", timerData(al, states).remaining)
        if (msg.contains("{trigger_")) {
            val snap = store.triggerStates[snoozeKey(al)]
            msg = msg.replace("{trigger_state}", snap?.first ?: es?.state ?: "")
                .replace("{trigger_attribute}", snap?.second ?: "")
                .replace("{trigger_time}", (store.persistentSince[snoozeKey(al)] ?: snap?.third)?.let { triggerTime(it) } ?: "")
        }
        return subEntity(msg)
    }

    /**
     * Heure du déclenchement, jour entre parenthèses s'il ne s'agit pas d'aujourd'hui : « 09:58 », « 18:20 (hier) »,
     * « 18:20 (4 nov.) ». Se lit après « à » dans toutes les langues (« Détection à {trigger_time} »).
     */
    private fun triggerTime(ms: Long): String {
        val i18n = fr.pergens.hamacs.App.instance.alerts.i18n
        return triggerTimeLabel(
            ms, java.time.LocalDate.now(), ZoneId.systemDefault(), locale,
            { i18n.a("when_yesterday", it) }, { time, day -> i18n.a("when_date", time, day) },
        )
    }

    /** Évaluation immédiate des templates simples en attendant la réponse de HA. */
    private fun miniJinja(t: String): String =
        Regex("\\{\\{\\s*([\\s\\S]*?)\\s*\\}\\}").replace(t) { m -> evalJinja(m.groupValues[1].trim(), haStates()) ?: "" }

    private fun haStates() = ha.states.value

    private fun evalJinja(expr: String, states: Map<String, EntityState>): String? =
        fr.pergens.hamacs.ui.cards.alerts.evalJinja(expr, states) { formatState(it, null) }

    // ------------------------------------------------------------------------------------------
    // Minuteries (thèmes countdown, hourglass, timer_pulse, timer_ring)
    // ------------------------------------------------------------------------------------------

    data class Timer(val progress: Float, val remaining: String, val active: Boolean)
    private val tsTotals = HashMap<String, Long>()

    fun timerData(al: Active, states: Map<String, EntityState>): Timer {
        val es = al.entity?.let { states[it] } ?: return Timer(1f, "--:--", false)
        fun fmt(sec: Long): String {
            val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
        }
        val bad = es.state in setOf("unavailable", "unknown", "none", "")
        if (es.attrString("device_class") == "timestamp") {
            if (bad) { tsTotals.remove(es.entityId); return Timer(-1f, "--:--", false) }
            val end = parseTimestamp(es.state) ?: return Timer(-1f, "--:--", false)
            val rem = ((end - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            if (rem == 0L) { tsTotals.remove(es.entityId); return Timer(-1f, "00:00", false) }
            val total = tsTotals[es.entityId]?.takeIf { it >= rem } ?: rem.also { tsTotals[es.entityId] = it }
            return Timer((rem.toFloat() / total).coerceAtMost(1f), fmt(rem), true)
        }
        val uom = es.unit
        if (uom == "%") {
            val pct = es.state.toDoubleOrNull()
            if (pct == null || bad) return Timer(-1f, "--", false)
            val c = pct.coerceIn(0.0, 100.0)
            return Timer((c / 100).toFloat(), "${Math.round(c)}%", c > 0)
        }
        if (uom == "s" || uom == "min") {
            val raw = es.state.toDoubleOrNull()
            if (raw == null || bad) { tsTotals.remove(es.entityId); return Timer(-1f, "--:--", false) }
            val rem = Math.round(if (uom == "min") raw * 60 else raw).coerceAtLeast(0)
            if (rem == 0L) { tsTotals.remove(es.entityId); return Timer(-1f, "00:00", false) }
            val total = tsTotals[es.entityId]?.takeIf { it >= rem } ?: rem.also { tsTotals[es.entityId] = it }
            return Timer((rem.toFloat() / total).coerceAtMost(1f), fmt(rem), true)
        }
        if (es.state != "active") return Timer(0f, "00:00", false)
        val finishes = es.attrString("finishes_at")?.let { parseTimestamp(it) }
        val duration = es.attrString("duration")
        if (finishes == null || duration == null) return Timer(1f, "--:--", true)
        val rem = ((finishes - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        val p = duration.split(':').map { it.toLongOrNull() ?: 0 }
        val total = (p.getOrElse(0) { 0 } * 3600 + p.getOrElse(1) { 0 } * 60 + p.getOrElse(2) { 0 })
        return Timer(if (total > 0) rem.toFloat() / total else 0f, fmt(rem), true)
    }

    companion object {
        /** `entity_filter` : sous-chaîne insensible à la casse, ou motif avec `*`. */
        fun filterMatcher(filter: String): (String) -> Boolean {
            val f = filter.lowercase()
            if (f.contains('*')) {
                val re = Regex("^" + f.split('*').joinToString(".*") { Regex.escape(it) } + "$")
                return { re.matches(it.lowercase()) }
            }
            return { it.lowercase().contains(f) }
        }
    }
}
