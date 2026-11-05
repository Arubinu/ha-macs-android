package fr.arubinu.hadash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.arubinu.hadash.App
import fr.arubinu.hadash.R
import fr.arubinu.hadash.config.BlockOverrides
import fr.arubinu.hadash.config.s
import fr.arubinu.hadash.ui.components.HaIcon
import fr.arubinu.hadash.ui.theme.Dash
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val Muted = Color(255, 255, 255, 150)
private val RowBg = Color(255, 255, 255, 10)
private val Danger = Color(0xFFFF6B6B)

private fun JsonObject.with(k: String, v: JsonElement?) = JsonObject(if (v == null) this - k else this + (k to v))
private fun JsonObject.cards() = (this["cards"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

/**
 * Nouvelle caméra : réglages repris de la première caméra existante (rafraîchissement, durée du direct…),
 * batterie et capteurs de mouvement trouvés sur le même appareil dans Home Assistant.
 */
internal fun newCamera(entity: String, template: JsonObject?, fullscreen: String? = CameraStreams.fullscreenFor(entity)): JsonObject {
    val app = App.instance
    val registry = app.alerts.registry.entities
    val device = registry[entity]?.deviceId
    val states = app.ha.states.value
    val siblings = if (device == null) emptyList() else registry.filter { it.value.deviceId == device }.keys.toList()
    val battery = siblings.firstOrNull { id ->
        id.startsWith("sensor.") && (states[id]?.attrString("device_class") == "battery" || id.contains("batter"))
    }
    val motion = siblings.filter { id ->
        id.startsWith("binary_sensor.") && states[id]?.attrString("device_class") in setOf("motion", "occupancy", "presence")
    }
    val base = template?.let { JsonObject(it - "entity" - "battery" - "motion" - "_key" - "fullscreen_entity" - "live_url" - "fullscreen_url" - "image_url") }
        ?: JsonObject(mapOf("type" to JsonPrimitive("camera"), "refresh" to JsonPrimitive(60)))
    return JsonObject(base + buildMap {
        put("entity", JsonPrimitive(entity))
        fullscreen?.takeIf { it != entity }?.let { put("fullscreen_entity", JsonPrimitive(it)) }
        battery?.let { put("battery", JsonPrimitive(it)) }
        if (motion.isNotEmpty()) put("motion", JsonArray(motion.map { JsonPrimitive(it) }))
    })
}

/**
 * Flux d'une caméra physique : les intégrations (Reolink, Frigate, ONVIF…) créent souvent plusieurs entités
 * `camera.*` par appareil (fluide / net, SD / HD, instantanés). La carte affiche le flux léger, le plein écran le
 * plus défini.
 */
object CameraStreams {
    private fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
    private val SNAP = Regex("snapshot|instantan")
    private val LOW = Regex("fluent|fluide|sub|low|basse|mobile|(^|[^a-z])(sd|ld|lq)([^a-z]|$)")
    private val HIGH = Regex("clear|clair|(^|[^a-z])net([^a-z]|$)|main|high|haute|(^|[^a-z])(hd|hq|4k|uhd)([^a-z]|$)")

    private fun text(id: String): String {
        val name = App.instance.ha.states.value[id]?.name.orEmpty()
        return norm("$id $name")
    }

    /** Rang pour la carte (0 = flux léger). */
    fun previewRank(id: String): Int = text(id).let { t -> when { SNAP.containsMatchIn(t) -> 3; LOW.containsMatchIn(t) -> 0; HIGH.containsMatchIn(t) -> 2; else -> 1 } }

    /** Rang pour le plein écran (0 = flux le plus défini). */
    fun fullscreenRank(id: String): Int = text(id).let { t -> when { SNAP.containsMatchIn(t) -> 3; HIGH.containsMatchIn(t) -> 0; LOW.containsMatchIn(t) -> 2; else -> 1 } }

    /** Entités `camera.*` du même appareil que [entity] (lui compris). */
    fun siblings(entity: String): List<String> {
        val registry = App.instance.alerts.registry.entities
        val device = registry[entity]?.deviceId ?: return listOf(entity)
        return registry.filter { it.value.deviceId == device && it.key.startsWith("camera.") && !it.value.technical }.keys.sorted()
            .ifEmpty { listOf(entity) }
    }

    /** Flux plein écran proposé pour [entity] : le plus défini de l'appareil, s'il est meilleur que lui. */
    fun fullscreenFor(entity: String): String? {
        val best = siblings(entity).minByOrNull { fullscreenRank(it) } ?: return null
        return best.takeIf { it != entity && fullscreenRank(it) < fullscreenRank(entity) }
    }

    /** Libellé court d'un flux : son nom sans celui de l'appareil (« Argus 1 Fluide » → « Fluide »). */
    fun label(id: String, device: String?): String {
        val name = App.instance.ha.states.value[id]?.name ?: id
        if (device.isNullOrBlank()) return name
        val rest = if (name.startsWith(device, ignoreCase = true)) name.substring(device.length).trim(' ', ':', '-', '·') else name
        return rest.ifBlank { name }.replaceFirstChar { it.uppercase() }
    }
}

/** Onglets de caméras [key] : base actuelle, liste d'origine et enregistrement. */
private class TabsData(val key: String, val original: JsonObject, val originalPages: JsonArray, val pages: List<JsonObject>, val defaultTab: String?) {
    fun save(list: List<JsonObject>) {
        val arr = JsonArray(list.map { p -> JsonObject(p - "_key").with("cards", JsonArray(p.cards().map { JsonObject(it - "_key") })) })
        App.instance.config.setBlockField(key, "pages", arr.takeIf { it != originalPages })
    }
    fun setPage(i: Int, p: JsonObject) = save(pages.mapIndexed { j, x -> if (j == i) p else x })
    fun setDefault(id: String?) = App.instance.config.setBlockField(key, "default", id?.takeIf { it != original.s("default") }?.let { JsonPrimitive(it) })
}

@Composable
private fun rememberTabs(key: String): TabsData? {
    val app = App.instance
    val rawDash by app.config.rawDashboard.collectAsState()
    val source by app.config.sourceDashboard.collectAsState()
    val original = remember(rawDash, key) { BlockOverrides.find(rawDash, key) } ?: return null
    val base = BlockOverrides.find(source, key) ?: original
    val originalPages = original["pages"] as? JsonArray ?: return null
    val pages = ((base["pages"] as? JsonArray) ?: originalPages).mapNotNull { it as? JsonObject }
    return TabsData(key, original, originalPages, pages, base.s("default") ?: pages.firstOrNull()?.s("id"))
}

/**
 * Onglets de caméras [key] (`tabs:<id>`) : liste des onglets (icône, nom, nombre de caméras, onglet par défaut,
 * ordre, suppression, ajout). Toucher un onglet ouvre sa fenêtre ; on revient ici en la fermant.
 */
@Composable
fun TabsFields(key: String) {
    val app = App.instance
    val state = LocalDashState.current
    val t = rememberTabs(key) ?: return

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        t.pages.forEachIndexed { i, page ->
            val id = page.s("id") ?: return@forEachIndexed
            val isDefault = id == t.defaultTab
            val n = page.cards().size
            val shape = RoundedCornerShape(14.dp)
            Row(
                Modifier.fillMaxWidth().clip(shape).background(RowBg, shape)
                    .border(1.dp, if (isDefault) Dash.colors.accent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.08f), shape)
                    .clickable { state.openEditChild(EditTarget.Tabs(key), EditTarget.Tab(key, i)) }
                    .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Dash.colors.menuActiveButtonBackground), contentAlignment = Alignment.Center) {
                    HaIcon(page.s("icon") ?: "mdi:cctv", size = 20.dp)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(page.s("label") ?: id, style = TextStyle(fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Medium))
                    Text(pluralStringResource(R.plurals.tabs_cameras, n, n), style = TextStyle(fontSize = 13.sp, color = Muted))
                }
                // Étoile : onglet affiché par défaut.
                TabIcon(if (isDefault) "mdi:star" else "mdi:star-outline", true, if (isDefault) Dash.colors.accent else Muted) { t.setDefault(id) }
                TabIcon("mdi:chevron-up", i > 0) { t.save(t.pages.toMutableList().apply { add(i - 1, removeAt(i)) }) }
                TabIcon("mdi:chevron-down", i < t.pages.lastIndex) { t.save(t.pages.toMutableList().apply { add(i + 1, removeAt(i)) }) }
                TabIcon("mdi:delete-outline", t.pages.size > 1, Danger) {
                    t.save(t.pages.filterIndexed { j, _ -> j != i })
                    if (isDefault) t.setDefault(t.pages.firstOrNull { it.s("id") != id }?.s("id"))
                }
            }
        }
        AddButton(stringResource(R.string.tabs_add_tab)) {
            t.save(t.pages + JsonObject(mapOf(
                "id" to JsonPrimitive("tab-${System.currentTimeMillis()}"), "icon" to JsonPrimitive("mdi:cctv"),
                "label" to JsonPrimitive(app.getString(R.string.tabs_new_name)), "cards" to JsonArray(emptyList()),
            )))
            // Le nouvel onglet s'ouvre directement pour y ajouter ses caméras.
            state.openEditChild(EditTarget.Tabs(key), EditTarget.Tab(key, t.pages.size))
        }
        val set = app.config.rawBlocks.collectAsState().value[key]?.get("set") as? JsonObject
        if (set != null && set.isNotEmpty()) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            TextLink(stringResource(R.string.tabs_reset)) {
                app.config.setBlockField(key, "pages", null)
                app.config.setBlockField(key, "default", null)
            }
        }
    }
}

/**
 * Onglet n° [index] des onglets [key] : icône, nom, onglet par défaut, et ses caméras (réglages, ordre, retrait,
 * ajout avec batterie et capteurs de mouvement trouvés automatiquement).
 */
@Composable
fun TabFields(key: String, index: Int) {
    val state = LocalDashState.current
    val t = rememberTabs(key) ?: return
    val page = t.pages.getOrNull(index) ?: return
    val id = page.s("id") ?: return
    val cams = page.cards()
    val isDefault = id == t.defaultTab
    var pickIcon by remember { mutableStateOf(false) }
    var pickCamera by remember { mutableStateOf(false) }

    TabSection(stringResource(R.string.tabs_section_tab))
    Column(Modifier.fillMaxWidth().padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(Dash.colors.menuActiveButtonBackground).clickable { pickIcon = true },
                contentAlignment = Alignment.Center,
            ) { HaIcon(page.s("icon") ?: "mdi:cctv", size = 24.dp) }
            TabName(page.s("label") ?: "", Modifier.weight(1f).padding(start = 12.dp)) { name ->
                t.setPage(index, page.with("label", name.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) }))
            }
        }
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).clickable { t.setDefault(id) }.padding(vertical = 2.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HaIcon(if (isDefault) "mdi:star" else "mdi:star-outline", size = 18.dp, tint = if (isDefault) Dash.colors.accent else Muted)
            Text(stringResource(R.string.tabs_default), Modifier.padding(start = 6.dp), style = TextStyle(fontSize = 13.sp, color = if (isDefault) Dash.colors.accent else Muted))
        }
    }

    TabSection(stringResource(R.string.tabs_section_cameras))
    Column(Modifier.fillMaxWidth().padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cams.forEachIndexed { c, cam ->
            val camId = cam.s("entity") ?: "—"
            // Réglages de la caméra (entités, code, flux permanent), puis retour à cet onglet.
            EntityCard(camId, onClick = { state.openEditChild(EditTarget.Tab(key, index), EditTarget.Camera(camId)) }) {
                TabIcon("mdi:chevron-up", c > 0) { t.setPage(index, page.with("cards", JsonArray(cams.toMutableList().apply { add(c - 1, removeAt(c)) }))) }
                TabIcon("mdi:chevron-down", c < cams.lastIndex) { t.setPage(index, page.with("cards", JsonArray(cams.toMutableList().apply { add(c + 1, removeAt(c)) }))) }
                TabIcon("mdi:delete-outline", true) { t.setPage(index, page.with("cards", JsonArray(cams.filterIndexed { j, _ -> j != c }))) }
            }
        }
        AddButton(stringResource(R.string.tabs_add_camera)) { pickCamera = true }
    }

    if (pickIcon) IconPicker(onDismiss = { pickIcon = false }) { ic ->
        pickIcon = false
        t.setPage(index, page.with("icon", ic?.let { JsonPrimitive(it) }))
    }
    if (pickCamera) EntityPicker("camera", onDismiss = { pickCamera = false }) { camId ->
        pickCamera = false
        val template = t.pages.flatMap { it.cards() }.firstOrNull()
        t.setPage(index, page.with("cards", JsonArray(cams + newCamera(camId, template))))
    }
}

@Composable
private fun TabSection(text: String) {
    Text(text, Modifier.padding(top = 10.dp, bottom = 2.dp), style = TextStyle(fontSize = 13.sp, color = Dash.colors.accent, fontWeight = FontWeight.SemiBold))
}

/** Nom d'onglet modifiable sur place, enregistré un instant après la dernière frappe. */
@Composable
private fun TabName(value: String, modifier: Modifier, onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    LaunchedEffect(text) {
        if (text == value) return@LaunchedEffect
        delay(600)
        onSave(text.trim())
    }
    Column(modifier) {
        Text(stringResource(R.string.tabs_name), style = TextStyle(fontSize = 12.sp, color = Muted))
        BasicTextField(
            text, { text = it }, singleLine = true,
            textStyle = TextStyle(fontSize = 17.sp, color = Color.White, fontWeight = FontWeight.Medium),
            cursorBrush = SolidColor(Dash.colors.accent),
        )
    }
}

@Composable
private fun TabIcon(icon: String, enabled: Boolean, tint: Color = Muted, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { HaIcon(icon, size = 22.dp, tint = if (enabled) tint else Color.White.copy(alpha = 0.15f)) }
}
