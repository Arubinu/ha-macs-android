package fr.arubinu.hadash.setup

import kotlinx.coroutines.flow.first
import android.content.Context
import fr.arubinu.hadash.App
import fr.arubinu.hadash.R
import fr.arubinu.hadash.config.BlockOverrides
import fr.arubinu.hadash.config.o
import fr.arubinu.hadash.config.objs
import fr.arubinu.hadash.config.s
import fr.arubinu.hadash.ha.EntityState
import fr.arubinu.hadash.macs.MacsOptions
import fr.arubinu.hadash.ui.CameraStreams
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.text.Normalizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList

/** Pièce proposée, tirée d'une zone de Home Assistant. */
class AreaChoice(
    val id: String,
    val name: String,
    val icon: String,
    val main: String?,
    val temperature: String?,
    val humidity: String?,
    val devices: List<String>,
    selected: Boolean,
) {
    var selected by mutableStateOf(selected)
}

/** Membre proposé, tiré d'une personne de Home Assistant (capteurs de son téléphone trouvés automatiquement). */
class PersonChoice(
    val entity: String,
    val name: String,
    val picture: String?,
    val battery: String?,
    val steps: String?,
    val charger: String?,
    selected: Boolean,
) {
    var selected by mutableStateOf(selected)
}

/**
 * Caméra physique : ses flux ([streams]), celui de la carte ([preview], léger), celui du plein écran ([fullscreen],
 * le plus défini ; null = le même) et son onglet ([tab] = id d'un [CamTab] ; null = masquée).
 */
class CameraChoice(
    val name: String,
    val streams: List<String>,
    preview: String,
    fullscreen: String?,
    tab: String?,
) {
    var preview by mutableStateOf(preview)
    var fullscreen by mutableStateOf(fullscreen)
    var tab by mutableStateOf(tab)
}

/** Onglet de caméras proposé (repris de l'agencement fourni, renommable, ou ajouté dans l'assistant). */
class CamTab(val id: String, label: String, icon: String) {
    var label by mutableStateOf(label)
    var icon by mutableStateOf(icon)
}

/** Élément principal du tableau de bord (météo, alarme…) : entité choisie parmi celles du [domain]. */
class RoleChoice(val role: Role, entity: String?) {
    var entity by mutableStateOf(entity)
}

enum class Role(val domain: String, val deviceClass: String?, val title: Int, val icon: String) {
    Weather("weather", null, R.string.setup_role_weather, "mdi:weather-partly-cloudy"),
    Alarm("alarm_control_panel", null, R.string.setup_role_alarm, "mdi:shield-home"),
    Music("media_player", null, R.string.setup_role_music, "mdi:music"),
    Power("sensor", "power", R.string.setup_role_power, "mdi:lightning-bolt"),
    OutdoorTemp("sensor", "temperature", R.string.setup_role_outdoor_temp, "mdi:thermometer"),
    Rain("sensor", null, R.string.setup_role_rain, "mdi:weather-rainy"),
}

/** Résultat de l'analyse de la maison, modifié par l'assistant puis appliqué. */
class SetupPlan(
    /** Part des entités de l'agencement fourni présentes dans cette maison (0–1). */
    val coverage: Double,
    keepLayout: Boolean,
    val areas: List<AreaChoice>,
    val persons: List<PersonChoice>,
    val cameras: List<CameraChoice>,
    val cameraTabs: MutableList<CamTab>,
    val roles: List<RoleChoice>,
) {
    var keepLayout by mutableStateOf(keepLayout)
    /** Code maître choisi dans l'assistant (vide = garder le réglage actuel). */
    var masterCode by mutableStateOf("")
    var masterConfirm by mutableStateOf("")
    val masterValid get() = masterCode.isEmpty() || (masterCode.length in 4..8 && masterCode == masterConfirm)
}

/**
 * Analyse la maison (zones, personnes, caméras, entités) pour adapter l'agencement fourni, puis applique les choix
 * de l'assistant sous forme de réglages de blocs : tout reste modifiable ensuite en mode édition.
 */
object HomeSetup {
    private val ENTITY = Regex("^[a-z_]+\\.[a-z0-9_]+$")

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
    private fun EntityState.text() = norm(entityId + " " + name)

    /** Entités référencées par l'agencement fourni (hors AlertTicker, qui a sa propre configuration). */
    private fun templateEntities(root: JsonElement, out: MutableSet<String> = HashSet()): Set<String> {
        when (root) {
            is JsonObject -> if (root.s("type") !in setOf("alert_ticker", "ticker")) root.values.forEach { templateEntities(it, out) }
            is JsonArray -> root.forEach { templateEntities(it, out) }
            is JsonPrimitive -> if (root.isString && ENTITY.matches(root.content)) out += root.content
        }
        return out
    }

    /** Entités des lecteurs de musique et des graphiques de consommation de l'agencement fourni. */
    private fun musicEntities(raw: JsonElement) = find(raw, "media_player").mapNotNull { it.s("entity") }.toSet()
    private fun powerEntities(raw: JsonElement) =
        (find(raw, "power_graph") + find(raw, "mini_graph").filter { it["entity"] != null }).mapNotNull { it.s("entity") }.toSet()

    private fun find(root: JsonElement, type: String): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> { if (e.s("type") == type) out += e; e.values.forEach { walk(it) } }
                is JsonArray -> e.forEach { walk(it) }
                else -> Unit
            }
        }
        walk(root)
        return out
    }

    private val AREA_ICONS = listOf(
        listOf("salon", "sejour", "living", "lounge") to "mdi:sofa",
        listOf("cuisine", "kitchen") to "mdi:countertop",
        listOf("chambre", "bedroom", "parent") to "mdi:bed",
        listOf("enfant", "kid", "child", "bebe", "baby") to "mdi:teddy-bear",
        listOf("bain", "bath", "douche", "shower") to "mdi:shower",
        listOf("toilet", "wc") to "mdi:toilet",
        listOf("bureau", "office", "study") to "mdi:desk",
        listOf("entree", "hall", "couloir", "corridor", "entrance") to "mdi:door",
        listOf("garage") to "mdi:garage",
        listOf("jardin", "garden", "terrasse", "terrace", "balcon", "balcony", "exterieur", "outside") to "mdi:flower",
        listOf("buanderie", "laundry", "cellier") to "mdi:washing-machine",
        listOf("salle a manger", "dining") to "mdi:silverware-fork-knife",
        listOf("grenier", "attic", "cave", "basement", "sous-sol") to "mdi:stairs",
    )

    private val OUTSIDE = listOf("ext", "out", "jardin", "garden", "parking", "balcon", "balcony", "terrasse", "terrace", "cour", "yard",
        "portail", "gate", "rue", "street", "drive", "allee", "facade", "front", "back", "porch", "garage", "piscine", "pool")

    /** Analyse : nécessite la connexion à Home Assistant et son registre. */
    suspend fun scan(): SetupPlan {
        val app = App.instance
        val ha = app.ha
        val registry = app.alerts.registry
        // « Connecté » est annoncé avant la réception des états : les attendre (sinon maison vide).
        kotlinx.coroutines.withTimeoutOrNull(20_000) { ha.states.first { it.isNotEmpty() } }
        registry.refresh(ha, force = true)
        val states = ha.states.value
        val template = templateEntities(app.config.rawDashboard.value)
        val coverage = if (template.isEmpty()) 0.0 else template.count { it in states } / template.size.toDouble()

        // --- Zones → pièces
        val areaIcons: Map<String, String?> = runCatching {
            (ha.call(buildJsonObject { put("type", "config/area_registry/list") }, 30_000) as JsonArray)
                .associate { e -> val o = e.jsonObject; o.s("area_id").orEmpty() to o.s("icon") }
        }.getOrDefault(emptyMap())
        val byArea = states.values.groupBy { registry.areaId(it.entityId) }
        val areas = registry.areas.entries.sortedBy { it.value.lowercase() }.mapNotNull { (id, name) ->
            // Entités techniques exclues (LED d'état, réglages, diagnostic, entités masquées).
            val ents = byArea[id].orEmpty().filter { e ->
                registry.entities[e.entityId]?.technical != true && (!e.isUnavailable || e.domain in setOf("light", "switch"))
            }
            fun dc(e: EntityState) = e.attrString("device_class")
            val lights = ents.filter { it.domain == "light" }.sortedBy { e ->
                if (Regex("plafon|ceiling|main|principal|lustre|suspension").containsMatchIn(e.text())) 0 else 1
            }
            val main = lights.firstOrNull()?.entityId ?: ents.firstOrNull { it.domain == "switch" }?.entityId
            val temp = ents.firstOrNull { it.domain == "sensor" && dc(it) == "temperature" }?.entityId
            val hum = ents.firstOrNull { it.domain == "sensor" && dc(it) == "humidity" }?.entityId
            val devices = (lights.drop(1) + ents.filter { it.domain == "media_player" } + ents.filter { it.domain in setOf("cover", "fan", "climate") } +
                ents.filter { it.domain == "switch" && it.entityId != main })
                .map { it.entityId }.distinct().take(3)
            if (main == null && temp == null && devices.isEmpty()) return@mapNotNull null
            val n = norm(name)
            val icon = areaIcons[id] ?: AREA_ICONS.firstOrNull { (keys, _) -> keys.any { n.contains(it) } }?.second ?: "mdi:home-outline"
            AreaChoice(id, name, icon, main, temp, hum, devices, selected = true)
        }

        // --- Personnes → membres (capteurs du téléphone associé)
        val persons = states.values.filter { it.domain == "person" }.sortedBy { it.name.lowercase() }.map { p ->
            val devices = p.attrList("device_trackers").mapNotNull { registry.entities[it]?.deviceId }.toSet()
            val siblings = states.values.filter { registry.entities[it.entityId]?.deviceId in devices && devices.isNotEmpty() }
            PersonChoice(
                entity = p.entityId,
                name = p.name,
                picture = p.attrString("entity_picture"),
                battery = siblings.firstOrNull { it.domain == "sensor" && it.attrString("device_class") == "battery" }?.entityId,
                steps = siblings.firstOrNull { it.domain == "sensor" && Regex("steps|pas").containsMatchIn(it.entityId) }?.entityId,
                charger = siblings.firstOrNull { Regex("charger|battery_state|is_charging").containsMatchIn(it.entityId) }?.entityId,
                // Personne sans traceur (compte technique, « Maison »…) : décochée par défaut.
                selected = p.attrList("device_trackers").isNotEmpty(),
            )
        }

        // --- Caméras → onglets (ceux de l'agencement fourni, libellés traduits)
        val camSwitcher = find(app.config.rawDashboard.value, "switcher").firstOrNull { BlockOverrides.cameraTabsKey(it) != null }
        val cameraTabs = (camSwitcher?.objs("pages").orEmpty().mapNotNull { p ->
            val id = p.s("id") ?: return@mapNotNull null
            CamTab(id, TAB_LABELS[id]?.let { app.getString(it) } ?: p.s("label") ?: id, p.s("icon") ?: "mdi:cctv")
        }).ifEmpty {
            listOf(
                CamTab("inside-camera", app.getString(R.string.setup_tab_inside), "mdi:home"),
                CamTab("outside-camera", app.getString(R.string.setup_tab_outside), "mdi:balcony"),
            )
        }.toMutableStateList()
        val insideTab = cameraTabs.firstOrNull { !it.id.contains("out") } ?: cameraTabs.first()
        val outsideTab = cameraTabs.firstOrNull { it.id.contains("out") } ?: insideTab
        // Une caméra par appareil : ses flux (fluide / net, SD / HD…) sont regroupés.
        val camStates = states.values.filter { it.domain == "camera" && registry.entities[it.entityId]?.technical != true }
        val cameras = camStates.groupBy { registry.entities[it.entityId]?.deviceId ?: it.entityId }.map { (device, group) ->
            val streams = group.map { it.entityId }.sortedBy { CameraStreams.previewRank(it) }
            val preview = streams.minBy { CameraStreams.previewRank(it) }
            val full = streams.minBy { CameraStreams.fullscreenRank(it) }.takeIf { it != preview && CameraStreams.fullscreenRank(it) < CameraStreams.fullscreenRank(preview) }
            val name = registry.devices[device]?.name?.takeIf { it.isNotBlank() } ?: states[preview]?.name ?: preview
            val where = norm(group.joinToString(" ") { it.name + " " + it.entityId } + " " + name + " " + registry.areaName(preview))
            CameraChoice(name, streams, preview, full, if (OUTSIDE.any { where.contains(it) }) outsideTab.id else insideTab.id)
        }.sortedBy { it.name.lowercase() }

        // --- Éléments principaux : entité de l'agencement si elle existe, sinon la plus probable.
        fun best(role: Role): String? {
            val tpl = template.filter { it.substringBefore('.') == role.domain && it in states }
            val pool = states.values.filter { it.domain == role.domain && !it.isUnavailable }
            fun score(e: EntityState, words: String) = if (Regex(words).containsMatchIn(e.text())) 0 else 1
            return when (role) {
                Role.Weather -> tpl.firstOrNull() ?: pool.minByOrNull { score(it, "home|maison|forecast") }?.entityId
                Role.Alarm -> tpl.firstOrNull() ?: pool.firstOrNull()?.entityId
                Role.Music -> musicEntities(app.config.rawDashboard.value).firstOrNull { it in states }
                    ?: pool.filter { it.attrString("device_class") != "tv" }.minByOrNull { score(it, "music|musique|spotify|sonos|speaker|enceinte") }?.entityId
                Role.Power -> powerEntities(app.config.rawDashboard.value).firstOrNull { it in states }
                    ?: pool.filter { it.attrString("device_class") == "power" }.minByOrNull { score(it, "total|maison|home|general|linky|compteur|grid|house") }?.entityId
                Role.OutdoorTemp -> app.config.dashboard.value.o("macs")?.s("temperature_sensor")?.takeIf { it in states }
                    ?: pool.filter { it.attrString("device_class") == "temperature" }.firstOrNull { Regex("ext|outdoor|outside|dehors|exterieur").containsMatchIn(it.text()) }?.entityId
                Role.Rain -> app.config.dashboard.value.o("macs")?.s("precipitation_sensor")?.takeIf { it in states }
                    ?: pool.firstOrNull { it.attrString("device_class") in setOf("precipitation", "precipitation_intensity") || Regex("rain|pluie|precipitation").containsMatchIn(it.text()) }?.entityId
            }
        }
        val roles = Role.entries.map { RoleChoice(it, best(it)) }
        return SetupPlan(coverage, coverage >= 0.6, areas, persons, cameras, cameraTabs, roles)
    }

    /** Candidats d'un élément principal (pour le changer dans l'assistant). */
    fun candidates(role: Role): List<String> = App.instance.ha.states.value.values.filter { e ->
        e.domain == role.domain && when (role) {
            Role.Power -> e.attrString("device_class") == "power"
            Role.OutdoorTemp -> e.attrString("device_class") == "temperature"
            else -> true
        }
    }.sortedBy { it.name.lowercase() }.map { it.entityId }

    /** Libellés d'onglets connus, traduits dans la langue de l'app. */
    private val TAB_LABELS = mapOf(
        "rooms" to R.string.setup_tab_rooms, "members" to R.string.setup_tab_members,
        "consumption" to R.string.setup_tab_consumption, "music" to R.string.setup_tab_music,
        "inside-camera" to R.string.setup_tab_inside, "outside-camera" to R.string.setup_tab_outside,
    )

    /** Applique le plan : réglages de blocs (structure et entités), comme s'ils avaient été faits en mode édition. */
    fun apply(ctx: Context, plan: SetupPlan) {
        val app = App.instance
        val config = app.config
        val raw = config.rawDashboard.value
        val states = app.ha.states.value
        val template = templateEntities(raw)

        // Onglets : libellés dans la langue de l'app (restent modifiables ensuite), pages masquées si sans objet.
        val hidePages = HashSet<String>()
        if (!plan.keepLayout) {
            if (plan.persons.none { it.selected }) hidePages += "members"
            if (plan.roles.first { it.role == Role.Music }.entity == null) hidePages += "music"
            if (plan.roles.first { it.role == Role.Power }.entity == null) hidePages += "consumption"
            // Carburant : capteurs propres à l'agencement fourni, masqué s'ils n'existent pas ici.
            val fuel = find(raw, "switcher").flatMap { it.objs("pages") }.firstOrNull { it.s("id") == "fuel" }
            if (fuel != null && templateEntities(fuel).none { it in states }) hidePages += "fuel"
        }
        find(raw, "switcher").forEach { sw ->
            val key = BlockOverrides.keyOf(sw) ?: return@forEach
            if (key.startsWith("tabs:")) return@forEach   // onglets de caméras : traités plus bas
            val pages = sw.objs("pages").filter { it.s("id") !in hidePages }.map { p ->
                val label = TAB_LABELS[p.s("id")]?.let { ctx.getString(it) }
                if (label != null && p.s("label") != null) JsonObject(p + ("label" to JsonPrimitive(label))) else p
            }
            config.setBlockField(key, "pages", JsonArray(pages).takeIf { it != sw["pages"] })
            if (sw.s("default") in hidePages) config.setBlockField(key, "default", pages.firstOrNull()?.s("id")?.let { JsonPrimitive(it) })
        }

        // Onglets de caméras : agencement gardé = libellés traduits ; sinon les onglets de l'assistant (ajoutés,
        // renommés ou supprimés) avec les caméras de la maison, chacune avec son flux de carte et de plein écran.
        find(raw, "switcher").filter { BlockOverrides.cameraTabsKey(it) != null }.forEach { sw ->
            val key = BlockOverrides.cameraTabsKey(sw)!!
            val pages = sw.objs("pages")
            val templateCam = pages.flatMap { it.objs("cards") }.firstOrNull()
            val newPages = if (plan.keepLayout) pages.map { p ->
                val label = TAB_LABELS[p.s("id")]?.let { ctx.getString(it) } ?: p.s("label")
                JsonObject(p + ("label" to JsonPrimitive(label ?: "")))
            } else plan.cameraTabs.map { t ->
                val base = pages.firstOrNull { it.s("id") == t.id } ?: JsonObject(mapOf("id" to JsonPrimitive(t.id)))
                val cams = plan.cameras.filter { it.tab == t.id }
                JsonObject(base + mapOf(
                    "label" to JsonPrimitive(t.label), "icon" to JsonPrimitive(t.icon),
                    "cards" to JsonArray(cams.map { c -> fr.arubinu.hadash.ui.newCamera(c.preview, templateCam, c.fullscreen) }),
                ))
            }
            config.setBlockField(key, "pages", JsonArray(newPages).takeIf { it != sw["pages"] })
            val ids = newPages.mapNotNull { it.s("id") }
            if (sw.s("default") !in ids) config.setBlockField(key, "default", ids.firstOrNull()?.let { JsonPrimitive(it) })
        }

        if (plan.keepLayout) return

        // Pièces : une par zone choisie (entité principale, 2 capteurs, 3 appareils au plus).
        find(raw, "room_grid").forEach { grid ->
            val key = BlockOverrides.keyOf(grid) ?: return@forEach
            val colors = listOf("first", "second", "third")
            val rooms = plan.areas.filter { it.selected }.map { a ->
                buildJsonObject {
                    put("name", a.name)
                    put("icon", a.icon)
                    a.main?.let { put("entity", it) }
                    put("info", JsonArray(listOfNotNull(
                        a.temperature?.let { t -> buildJsonObject { put("icon", "mdi:thermometer"); put("entity", t); put("decimals", 1); put("suffix", states[t]?.unit ?: "°C") } },
                        a.humidity?.let { h -> buildJsonObject { put("icon", "mdi:water-percent"); put("entity", h); put("suffix", states[h]?.unit ?: "%") } },
                    )))
                    put("buttons", JsonArray(a.devices.mapIndexed { i, d ->
                        buildJsonObject {
                            put("entity", d)
                            put("icon", states[d]?.attrString("icon") ?: deviceIcon(d))
                            put("color", colors[i % 3])
                        }
                    }))
                }
            }
            config.setBlockField(key, "rooms", JsonArray(rooms))
        }

        // Membres : une personne choisie = un membre (photo de la personne, capteurs de son téléphone).
        find(raw, "members").forEach { card ->
            val key = BlockOverrides.keyOf(card) ?: return@forEach
            val members = plan.persons.filter { it.selected }.map { p ->
                buildJsonObject {
                    put("id", p.entity.substringAfter('.'))
                    put("person", p.entity)
                    put("picture", "person")
                    put("home_sensor", p.entity)
                    p.steps?.let { put("steps_sensor", it) }
                    p.battery?.let { put("battery_sensor", it) }
                    p.charger?.let { put("charger_sensor", it) }
                    put("calendars", JsonArray(emptyList()))
                }
            }
            config.setBlockField(key, "members", JsonArray(members))
        }

        // Éléments principaux : remplacement des entités de l'agencement (ou bloc masqué s'il n'y en a pas).
        fun replace(domainFilter: (String) -> Boolean, target: String?, hideTypes: Set<String> = emptySet()) {
            template.filter { domainFilter(it) }.forEach { orig ->
                if (target != null) config.setBlockField(orig, "entity", JsonPrimitive(target).takeIf { target != orig })
                else if (orig !in states) config.setBlockField(orig, "hidden", JsonPrimitive(true))
            }
            if (target == null) hideTypes.forEach { t -> find(raw, t).forEach { c -> BlockOverrides.keyOf(c)?.let { config.setBlockField(it, "hidden", JsonPrimitive(true)) } } }
        }
        fun role(r: Role) = plan.roles.first { it.role == r }.entity
        replace({ it.startsWith("weather.") }, role(Role.Weather))
        replace({ it.startsWith("alarm_control_panel.") }, role(Role.Alarm), setOf("alarm"))
        val music = musicEntities(raw)
        val power = powerEntities(raw)
        replace({ it in music }, role(Role.Music))
        replace({ it in power }, role(Role.Power))

        // M.A.C.S. : capteurs de la maison.
        val macs = (config.blocks.value[MacsOptions.KEY]?.o("config") ?: JsonObject(emptyMap())).toMutableMap()
        fun macsSet(k: String, v: String?) { if (v != null) macs[k] = JsonPrimitive(v) else macs[k] = kotlinx.serialization.json.JsonNull }
        macsSet("weather_entity", role(Role.Weather))
        macsSet("temperature_sensor", role(Role.OutdoorTemp))
        macsSet("precipitation_sensor", role(Role.Rain))
        role(Role.Rain)?.let { r -> macs["precipitation_unit"] = JsonPrimitive("") ; if (states[r]?.unit == "%") macs["precipitation_unit"] = JsonPrimitive("%") }
        config.setBlockConfig(MacsOptions.KEY, JsonObject(macs))

        // Radar Windy : centré sur la maison (zone.home).
        val home = states["zone.home"]
        val lat = home?.attrDouble("latitude")
        val lon = home?.attrDouble("longitude")
        if (lat != null && lon != null) find(raw, "windy").forEach { w ->
            val key = BlockOverrides.keyOf(w) ?: return@forEach
            val url = w.s("url") ?: return@forEach
            val uri = android.net.Uri.parse(url)
            val b = uri.buildUpon().clearQuery()
            uri.queryParameterNames.forEach { n ->
                b.appendQueryParameter(n, when (n) { "lat" -> "%.4f".format(java.util.Locale.ROOT, lat); "lon" -> "%.4f".format(java.util.Locale.ROOT, lon); else -> uri.getQueryParameter(n) })
            }
            config.setBlockField(key, "url", JsonPrimitive(b.build().toString()))
        }
    }

    private fun deviceIcon(entity: String) = when (entity.substringBefore('.')) {
        "light" -> "mdi:lightbulb"
        "switch" -> "mdi:power-socket-eu"
        "media_player" -> "mdi:speaker"
        "fan" -> "mdi:fan"
        "cover" -> "mdi:window-shutter"
        "climate" -> "mdi:thermostat"
        else -> "mdi:toggle-switch"
    }
}
