package fr.arubinu.hadash.ui.cards.alerts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.arubinu.hadash.App
import fr.arubinu.hadash.config.b
import fr.arubinu.hadash.config.d
import fr.arubinu.hadash.config.i
import fr.arubinu.hadash.config.o
import fr.arubinu.hadash.config.objs
import fr.arubinu.hadash.config.s
import fr.arubinu.hadash.config.strs
import fr.arubinu.hadash.ha.EntityState
import fr.arubinu.hadash.ui.LocalDashState
import fr.arubinu.hadash.ui.theme.Dash
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private val Panel = Color(0xFF16161A)
private val Field = Color(0xFF202026)
private val Muted = Color(0x99FFFFFF)

private fun JsonObject.with(key: String, value: JsonElement?): JsonObject =
    if (value == null || (value is JsonPrimitive && value.isString && value.content.isEmpty())) JsonObject(this - key) else JsonObject(this + (key to value))

private fun str(v: String?) = v?.let { JsonPrimitive(it) }
private fun num(v: String?): JsonElement? = v?.trim()?.replace(',', '.')?.takeIf { it.isNotEmpty() }?.let { t -> t.toLongOrNull()?.let { JsonPrimitive(it) } ?: t.toDoubleOrNull()?.let { JsonPrimitive(it) } }

/**
 * Éditeur de l'AlertTicker Card, calqué sur l'éditeur visuel de la carte d'origine : sections Général,
 * Mise en page, Alertes, Tout est OK, Synthèse vocale et Overlay, avec aperçu en direct.
 */
@Composable
fun AlertTickerEditor(cardKey: String, onClose: () -> Unit) {
    val app = App.instance
    val hub = app.alerts
    val i18n = hub.i18n
    val blocks by app.config.blocks.collectAsState()
    val dashboard by app.config.dashboard.collectAsState()
    val base = remember(dashboard) { findCard(dashboard, cardKey) ?: JsonObject(emptyMap()) }
    var draft by remember { mutableStateOf(blocks[cardKey]?.o("config") ?: base) }
    var section by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableIntStateOf(-1) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val previewEngine = remember { AlertEngine(app.ha, hub.store, hub.registry, hub.templates) }
    val states by app.ha.states.collectAsState()
    hub.store.version.collectAsState().value
    val preview = remember(draft, states, hub.store.version.value) {
        val cfg = if (editing >= 0 && draft.b("test_mode")) draft else draft
        previewEngine.compute(cfg, states, sideEffects = false)
    }
    fun set(key: String, v: JsonElement?) { draft = draft.with(key, v) }
    val alerts = draft.objs("alerts")
    fun setAlerts(list: List<JsonObject>) { draft = JsonObject(draft + ("alerts" to JsonArray(list))) }
    fun updateAlert(i: Int, key: String, v: JsonElement?) { setAlerts(alerts.mapIndexed { k, a -> if (k == i) a.with(key, v) else a }) }

    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF0E0E11)).padding(20.dp)) {
            // En-tête
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔔", fontSize = 24.sp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("AlertTicker Card", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    Text(message ?: i18n.e("hub_welcome"), color = if (message != null) Dash.colors.accent else Muted, fontSize = 13.sp)
                }
                ActionChip("⤓ Home Assistant") {
                    scope.launch {
                        message = runCatching { importFromHa(app, base) }.fold({ imported ->
                            if (imported == null) i18n.a("import_none") else {
                                draft = mergeImport(draft, imported); i18n.a("import_done")
                            }
                        }, { i18n.a("import_failed", it.message) })
                    }
                }
                Spacer(Modifier.width(8.dp))
                ActionChip(if (app.config.blocks.value[cardKey]?.o("config") != null) i18n.a("reset_chip") else null) {
                    draft = base; editing = -1; message = i18n.a("reset_done")
                }
                Spacer(Modifier.width(8.dp))
                ActionChip(i18n.a("cancel")) { onClose() }
                Spacer(Modifier.width(8.dp))
                ActionChip(i18n.a("save"), primary = true) {
                    val clean = JsonObject(draft - "test_mode" - "_preview_index")
                    app.config.setBlockConfig(cardKey, if (clean == base) null else clean)
                    onClose()
                }
            }
            Spacer(Modifier.size(14.dp))
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                // Sections
                Column(Modifier.width(250.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        Triple("general", "⚙️", "tab_general") to "hub_desc_general",
                        Triple("layout", "🎨", "tab_layout") to "hub_desc_layout",
                        Triple("alerts", "🚨", "tab_alerts") to "hub_desc_alerts",
                        Triple("allclear", "✅", "tab_allclear") to "hub_desc_allclear",
                        Triple("tts", "🗣️", "tab_tts") to "hub_desc_tts",
                        Triple("overlay", "🔔", "tab_overlay") to "hub_desc_overlay",
                    ).forEach { (sec, desc) ->
                        val sel = (section ?: "alerts") == sec.first
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (sel) Color(0xFF26262E) else Panel)
                                .clickable { section = sec.first; editing = -1 }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(sec.second, fontSize = 20.sp)
                            Column(Modifier.padding(start = 10.dp)) {
                                Text(i18n.e(sec.third), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Text(i18n.e(desc), color = Muted, fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(Modifier.size(10.dp))
                    SwitchRow(i18n.e("test_mode"), i18n.e("test_mode_desc"), draft.b("test_mode")) { set("test_mode", if (it) JsonPrimitive(true) else null) }
                    // Alertes mises en pause (💤) ou masquées : réactivation immédiate, même si la barre est désactivée.
                    if (preview.snoozedCount > 0) ActionChip("💤 ${preview.snoozedCount} ${i18n.t("alerts_snoozed")} · ↩ ${i18n.t("snooze_reset")}") {
                        hub.store.resetSnooze(); message = i18n.a("snoozed_resumed")
                    }
                    if (preview.dismissedCount > 0) ActionChip("🔕 ${preview.dismissedCount} ${i18n.t("alerts_dismissed")} · ↩ ${i18n.t("dismiss_reset")}") {
                        hub.store.resetDismissed(); message = i18n.a("dismissed_restored")
                    }
                    Text(i18n.a("credit"), color = Color(0x55FFFFFF), fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                }
                // Contenu + aperçu
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF050507)).padding(18.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.widthIn(max = (draft.d("max_width") ?: 460.0).dp).fillMaxWidth()) {
                            AtcView(draft, preview, previewEngine, cardKey, previewIndex = if (draft.b("test_mode") && editing >= 0) editing else null)
                        }
                    }
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(end = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        when (section ?: "alerts") {
                            "general" -> GeneralTab(draft, ::set)
                            "layout" -> LayoutTab(draft, ::set)
                            "allclear" -> AllClearTab(draft, ::set, states)
                            "tts" -> TtsTab(draft, ::set, states)
                            "overlay" -> OverlayTab(draft, ::set)
                            else -> if (editing in alerts.indices) AlertPanel(
                                index = editing, alert = alerts[editing], states = states,
                                onBack = { editing = -1 },
                                update = { k, v -> updateAlert(editing, k, v) },
                            ) else AlertsList(alerts, ::setAlerts) { editing = it }
                        }
                    }
                }
            }
        }
    }
}

private fun findCard(el: JsonElement, key: String): JsonObject? = when (el) {
    is JsonObject -> if (el.s("type") in AlertHub.TYPES && AlertHub.cardKey(el) == key) el else el.values.firstNotNullOfOrNull { findCard(it, key) }
    is JsonArray -> el.firstNotNullOfOrNull { findCard(it, key) }
    else -> null
}

/** Cherche la première `custom:alert-ticker-card` des tableaux de bord de Home Assistant. */
private suspend fun importFromHa(app: App, base: JsonObject): JsonObject? {
    val ha = app.ha
    val dashboards = (ha.call(buildJsonObject { put("type", "lovelace/dashboards/list") }) as? JsonArray)?.mapNotNull { (it as? JsonObject)?.s("url_path") } ?: emptyList()
    val preferred = base.s("lovelace_dashboard")
    for (path in listOfNotNull(preferred) + dashboards + listOf<String?>(null)) {
        val cfg = runCatching {
            ha.call(buildJsonObject { put("type", "lovelace/config"); path?.let { put("url_path", it) } }, 30_000)
        }.getOrNull() ?: continue
        fun find(e: JsonElement): JsonObject? = when (e) {
            is JsonObject -> if (e.s("type") == "custom:alert-ticker-card") e else e.values.firstNotNullOfOrNull { find(it) }
            is JsonArray -> e.firstNotNullOfOrNull { find(it) }
            else -> null
        }
        find(cfg)?.let { return it }
    }
    return null
}

/** Reprend les options de la carte HA en gardant le placement propre à l'app (position, marge, id…). */
private fun mergeImport(draft: JsonObject, imported: JsonObject): JsonObject {
    val keep = setOf("type", "id", "position", "margin", "max_width", "radius", "large_button_size", "large_button_right", "content_padding_right", "counter_top", "counter_right", "lovelace_dashboard")
    return JsonObject(draft.filterKeys { it in keep } + imported.filterKeys { it != "type" && it != "card_mod" })
}

// ------------------------------------------------------------------------------------------------
// Sections globales
// ------------------------------------------------------------------------------------------------

@Composable
private fun GeneralTab(c: JsonObject, set: (String, JsonElement?) -> Unit) {
    val t = App.instance.alerts.i18n
    Section(t.e("section_cycling")) {
        NumberRow(t.e("cycle_interval"), t.e("cycle_interval_help"), c.s("cycle_interval")) { set("cycle_interval", num(it)) }
        SelectRow(t.e("cycle_animation"), c.s("cycle_animation") ?: "fold",
            listOf("none", "fold", "slide", "fade", "flip", "zoom", "glitch", "bounce", "swing", "blur", "split", "roll", "curtain").map { it to t.e("anim_$it") }) { set("cycle_animation", str(it)) }
        SwitchRow(t.e("disable_animation"), t.e("disable_animation_help"), c.b("disable_animation")) { set("disable_animation", if (it) JsonPrimitive(true) else null) }
    }
    Section(t.e("section_snooze")) {
        SnoozeDuration(t.e("snooze_default_duration"), t.e("snooze_default_duration_help"), c["snooze_default_duration"], t.e("snooze_option_menu"), null) { set("snooze_default_duration", it) }
        SwitchRow(t.e("swipe_to_snooze"), null, c.b("swipe_to_snooze")) { set("swipe_to_snooze", if (it) JsonPrimitive(true) else null) }
        SwitchRow(t.e("show_snooze_bar"), null, c.b("show_snooze_bar", true)) { set("show_snooze_bar", if (it) null else JsonPrimitive(false)) }
        SwitchRow(t.e("show_snooze_button"), null, c.b("show_snooze_button", true)) { set("show_snooze_button", if (it) null else JsonPrimitive(false)) }
        SwitchRow(t.e("show_history_button"), null, c.b("show_history_button", true)) { set("show_history_button", if (it) null else JsonPrimitive(false)) }
        SwitchRow(t.e("show_counter"), null, c.b("show_counter", true)) { set("show_counter", if (it) null else JsonPrimitive(false)) }
        SwitchRow(t.e("show_dismiss_bar"), null, c.b("show_dismiss_bar", true)) { set("show_dismiss_bar", if (it) null else JsonPrimitive(false)) }
    }
    Section(t.e("section_tts")) {
        SwitchRow(t.e("tts_master_toggle"), t.e("tts_master_toggle_help"), c.b("tts_enabled", true)) { set("tts_enabled", if (it) null else JsonPrimitive(false)) }
    }
    Section(t.e("section_push_notify")) {
        SwitchRow(t.e("push_notify_master_toggle"), t.e("push_notify_master_toggle_help"), c.b("push_notify_enabled", true)) { set("push_notify_enabled", if (it) null else JsonPrimitive(false)) }
    }
    Section("🔊 " + t.e("sound_url")) {
        TextRow(t.e("sound_url"), t.e("sound_url_help"), c.s("sound_url")) { set("sound_url", str(it)) }
    }
    Section(t.e("section_history")) {
        NumberRow(t.e("history_max_events"), t.e("history_max_events_help"), c.s("history_max_events")) { set("history_max_events", num(it)) }
    }
}

@Composable
private fun LayoutTab(c: JsonObject, set: (String, JsonElement?) -> Unit) {
    val t = App.instance.alerts.i18n
    Section(t.e("section_layout")) {
        SwitchRow(t.e("ha_theme"), null, c.b("ha_theme")) { set("ha_theme", if (it) JsonPrimitive(true) else null) }
        SwitchRow(t.e("vertical"), null, c.b("vertical")) { set("vertical", if (it) JsonPrimitive(true) else null) }
        SwitchRow(t.e("text_align_center"), null, c.s("text_align") == "center") { set("text_align", if (it) JsonPrimitive("center") else null) }
        SwitchRow(t.e("large_buttons"), null, c.b("large_buttons")) { set("large_buttons", if (it) JsonPrimitive(true) else null) }
        NumberRow(t.e("card_height"), t.e("card_height_help"), c.s("card_height")) { set("card_height", num(it)) }
        NumberRow(t.e("font_scale"), t.e("font_scale_help"), c.s("font_scale")) { set("font_scale", num(it)) }
        SwitchRow(t.e("card_border"), t.e("card_border_help"), c.b("card_border")) { set("card_border", if (it) JsonPrimitive(true) else null) }
        SwitchRow(t.e("severity_border"), t.e("severity_border_help"), c.b("severity_border", true)) { set("severity_border", if (it) null else JsonPrimitive(false)) }
        TextRow(t.e("card_background"), t.e("card_background_help"), c.s("card_background")) { set("card_background", str(it)) }
    }
    Section(t.a("placement_section")) {
        SelectRow(t.a("position"), c.s("position") ?: "top_end", listOf(
            "top_start" to t.a("pos_top_start"), "top_center" to t.a("pos_top_center"), "top_end" to t.a("pos_top_end"),
            "bottom_start" to t.a("pos_bottom_start"), "bottom_center" to t.a("pos_bottom_center"), "bottom_end" to t.a("pos_bottom_end"),
        )) { set("position", str(it)) }
        NumberRow(t.a("margin"), null, c.s("margin")) { set("margin", num(it)) }
        NumberRow(t.a("max_width"), null, c.s("max_width")) { set("max_width", num(it)) }
        NumberRow(t.a("radius"), t.a("default_value", 12), c.s("radius")) { set("radius", num(it)) }
        NumberRow(t.a("large_button_size"), t.a("default_value", 30), c.s("large_button_size")) { set("large_button_size", num(it)) }
        NumberRow(t.a("large_button_right"), t.a("default_value", 8), c.s("large_button_right")) { set("large_button_right", num(it)) }
        NumberRow(t.a("content_padding_right"), t.a("default_value", 88), c.s("content_padding_right")) { set("content_padding_right", num(it)) }
        NumberRow(t.a("counter_top"), t.a("default_value", 5), c.s("counter_top")) { set("counter_top", num(it)) }
        NumberRow(t.a("counter_right"), t.a("default_value", 7), c.s("counter_right")) { set("counter_right", num(it)) }
    }
}

@Composable
private fun AllClearTab(c: JsonObject, set: (String, JsonElement?) -> Unit, states: Map<String, EntityState>) {
    val t = App.instance.alerts.i18n
    Section(t.e("tab_allclear")) {
        SwitchRow(t.e("show_when_clear"), null, c.b("show_when_clear")) { set("show_when_clear", JsonPrimitive(it)) }
        if (!c.b("show_when_clear")) { Help(t.e("all_clear_disabled_help")); return@Section }
        SelectRow(t.e("clear_display_mode_label"), c.s("clear_display_mode") ?: "message", listOf(
            "message" to t.e("clear_mode_message"), "clock" to t.e("clear_mode_clock"), "weather" to t.e("clear_mode_weather"),
            "weather_clock" to t.e("clear_mode_weather_clock"), "forecast" to t.e("clear_mode_forecast"), "weather_forecast" to t.e("clear_mode_weather_forecast"),
        )) { set("clear_display_mode", str(it)) }
        val mode = c.s("clear_display_mode") ?: "message"
        if (mode != "message") {
            if (mode != "clock") {
                EntityRow(t.e("clear_weather_entity_label"), c.s("clear_weather_entity"), states, "weather.") { set("clear_weather_entity", str(it)) }
                SubTitle(t.e("clear_weather_sensors_title"))
                listOf("clear_weather_temperature_entity", "clear_weather_humidity_entity", "clear_weather_temp_high_entity", "clear_weather_temp_low_entity", "clear_weather_aqi_entity").forEach { k ->
                    EntityRow(t.e(k), c.s(k), states, "sensor.") { set(k, str(it)) }
                }
                SelectRow(t.e("clear_weather_style_label"), c.s("clear_weather_style") ?: "default",
                    listOf("default", "frosted", "solid", "minimal", "stage", "split", "cinematic", "wallpanel").map { it to t.e("style_$it") }) { set("clear_weather_style", str(it)) }
                NumberRow(t.e("weather_forecast_interval"), t.e("weather_forecast_interval_help"), c.s("weather_forecast_interval")) { set("weather_forecast_interval", num(it)) }
            }
            SwitchRow(t.e("clear_clock_show_date"), null, c.b("clear_clock_show_date", true)) { set("clear_clock_show_date", if (it) null else JsonPrimitive(false)) }
            SelectRow(t.e("clear_clock_date_label"), c.s("clear_clock_date_position") ?: "below", listOf("below" to t.e("date_position_below"), "above" to t.e("date_position_above"))) { set("clear_clock_date_position", str(it)) }
            SwitchRow(t.e("clear_clock_12h"), null, c.b("clear_clock_12h")) { set("clear_clock_12h", if (it) JsonPrimitive(true) else null) }
            SwitchRow(t.e("clear_clock_show_seconds"), null, c.b("clear_clock_show_seconds", true)) { set("clear_clock_show_seconds", if (it) null else JsonPrimitive(false)) }
            SelectRow(t.e("clear_clock_style_label"), c.s("clear_clock_style") ?: "default", listOf("default", "aurora", "gold", "matrix").map { it to t.e("style_$it") }) { set("clear_clock_style", str(it)) }
            SubTitle(t.e("clear_clock_colors_title"))
            TextRow(t.e("clear_clock_color"), null, c.s("clear_clock_color")) { set("clear_clock_color", str(it)) }
            TextRow(t.e("clear_clock_date_color"), null, c.s("clear_clock_date_color")) { set("clear_clock_date_color", str(it)) }
            TextRow(t.e("clear_clock_background"), null, c.s("clear_clock_background")) { set("clear_clock_background", str(it)) }
            SwitchRow(t.e("clear_disable_animation"), t.e("clear_disable_animation_help"), c.b("clear_disable_animation")) { set("clear_disable_animation", if (it) JsonPrimitive(true) else null) }
            SwitchRow(t.e("show_widget_in_cycle"), null, c.b("show_widget_in_cycle")) { set("show_widget_in_cycle", if (it) JsonPrimitive(true) else null) }
        } else {
            TextRow(t.e("clear_message"), null, c.s("clear_message")) { set("clear_message", str(it)) }
            TextRow(t.e("clear_badge_label"), null, c.s("clear_badge_label")) { set("clear_badge_label", str(it)) }
            ThemeRow(t.e("clear_theme"), c.s("clear_theme") ?: "success") { set("clear_theme", str(it)) }
            TextRow(t.e("clear_icon_label"), null, c.s("clear_icon")) { set("clear_icon", str(it)) }
            TextRow(t.e("clear_icon_color_label"), t.e("icon_color_help"), c.s("clear_icon_color")) { set("clear_icon_color", str(it)) }
            TextRow(t.e("clear_icon_size_label"), t.e("icon_size_help"), c.s("clear_icon_size")) { set("clear_icon_size", str(it)) }
        }
        ActionEditor(t.e("clear_tap_action_section"), c.o("clear_tap_action"), states) { set("clear_tap_action", it) }
        ActionEditor(t.e("clear_double_tap_action_section"), c.o("clear_double_tap_action"), states) { set("clear_double_tap_action", it) }
        ActionEditor(t.e("clear_hold_action_section"), c.o("clear_hold_action"), states) { set("clear_hold_action", it) }
    }
}

@Composable
private fun TtsTab(c: JsonObject, set: (String, JsonElement?) -> Unit, states: Map<String, EntityState>) {
    val t = App.instance.alerts.i18n
    Section(t.e("section_tts")) {
        Help("${t.e("tts_how_works")} — ${t.e("tts_how_standard")}\n${t.e("tts_how_alexa")}\n${t.e("tts_how_peralert")}")
        SwitchRow(t.e("tts_master_toggle"), t.e("tts_master_toggle_help"), c.b("tts_enabled", true)) { set("tts_enabled", if (it) null else JsonPrimitive(false)) }
        EntityRow(t.e("tts_entity_global"), c.s("tts_entity"), states, "media_player.", t.e("tts_entity_global_help")) { set("tts_entity", str(it)) }
        EntityRow(t.e("tts_engine_global"), c.s("tts_engine"), states, "tts.", t.e("tts_engine_global_help")) { set("tts_engine", str(it)) }
        SuggestRow(t.e("tts_notify_service"), t.e("tts_notify_service_help"), c.s("tts_notify_service"), notifyOptions()) { set("tts_notify_service", str(it)) }
        SelectRow(t.e("tts_notify_type"), c.s("tts_notify_type") ?: "tts", listOf("tts" to t.e("tts_notify_type_tts"), "announce" to t.e("tts_notify_type_announce"))) { set("tts_notify_type", str(it)) }
        TextRow(t.a("tts_language"), t.a("tts_language_hint"), c.s("tts_language")) { set("tts_language", str(it)) }
    }
}

@Composable
private fun OverlayTab(c: JsonObject, set: (String, JsonElement?) -> Unit) {
    val t = App.instance.alerts.i18n
    Section(t.e("section_overlay")) {
        Help(t.e("overlay_how_works"))
        SwitchRow(t.e("overlay_mode"), t.e("overlay_mode_help"), c.b("overlay_mode")) { set("overlay_mode", if (it) JsonPrimitive(true) else null) }
        SelectRow(t.e("overlay_position"), c.s("overlay_position") ?: "top", listOf("top" to t.e("overlay_pos_top"), "center" to t.e("overlay_pos_center"), "bottom" to t.e("overlay_pos_bottom"))) { set("overlay_position", str(it)) }
        NumberRow(t.e("overlay_duration"), t.e("overlay_duration_help"), c.s("overlay_duration")) { set("overlay_duration", num(it)) }
        NumberRow(t.e("overlay_scale"), t.e("overlay_scale_help"), c.s("overlay_scale")) { set("overlay_scale", num(it)) }
        NumberRow(t.e("overlay_min_priority"), t.e("overlay_min_priority_help"), c.s("overlay_min_priority")) { set("overlay_min_priority", num(it)) }
        SwitchRow(t.e("overlay_dismissible"), t.e("overlay_dismissible_help"), c.b("overlay_dismissible", true)) { set("overlay_dismissible", if (it) null else JsonPrimitive(false)) }
        SwitchRow(t.e("overlay_rotation"), t.e("overlay_rotation_help"), c.b("overlay_rotation")) { set("overlay_rotation", if (it) JsonPrimitive(true) else null) }
        NumberRow(t.e("overlay_rotation_interval"), t.e("overlay_rotation_interval_help"), c.s("overlay_rotation_interval")) { set("overlay_rotation_interval", num(it)) }
    }
}

// ------------------------------------------------------------------------------------------------
// Alertes
// ------------------------------------------------------------------------------------------------

@Composable
private fun AlertsList(alerts: List<JsonObject>, setAlerts: (List<JsonObject>) -> Unit, onEdit: (Int) -> Unit) {
    val t = App.instance.alerts.i18n
    var templates by remember { mutableStateOf(false) }
    Section(t.e("alerts_list")) {
        if (alerts.isEmpty()) Help(t.e("no_alerts"))
        alerts.forEachIndexed { i, a ->
            val off = !a.b("enabled", true)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Field).clickable { onEdit(i) }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(a.s("icon")?.takeIf { !it.contains(':') } ?: AtcThemes[a.s("theme")].emoji, fontSize = 20.sp)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text("${t.e("alert_num")} ${i + 1} · P${a.i("priority") ?: 3} · ${a.s("theme") ?: "emergency"}" + if (off) " · ${t.e("alert_disabled")}" else "",
                        color = if (off) Color(0x66FFFFFF) else Muted, fontSize = 12.sp)
                    Text(a.s("name")?.let { "$it — " }.orEmpty() + (a.s("message")?.takeIf { it.isNotEmpty() } ?: "—"), color = if (off) Color(0x66FFFFFF) else Color.White, fontSize = 15.sp, maxLines = 1)
                    Text(a.s("entity") ?: a.s("entity_filter")?.let { "🔎 $it" } ?: a.s("device_class")?.let { "🏷️ $it" } ?: "", color = Muted, fontSize = 12.sp, maxLines = 1)
                }
                IconBtn("▲", i > 0) { setAlerts(alerts.toMutableList().also { l -> l.add(i - 1, l.removeAt(i)) }) }
                IconBtn("▼", i < alerts.size - 1) { setAlerts(alerts.toMutableList().also { l -> l.add(i + 1, l.removeAt(i)) }) }
                IconBtn("⧉") { setAlerts(alerts.toMutableList().also { l -> l.add(i + 1, a) }) }
                IconBtn(if (off) "▶" else "⏸") { setAlerts(alerts.mapIndexed { k, x -> if (k == i) x.with("enabled", if (off) null else JsonPrimitive(false)) else x }) }
                IconBtn("🗑") { setAlerts(alerts.filterIndexed { k, _ -> k != i }) }
                IconBtn("✎") { onEdit(i) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip("+ " + t.e("add_alert"), primary = true) {
                setAlerts(alerts + buildJsonObject {
                    put("entity", ""); put("operator", "="); put("state", "on"); put("message", ""); put("priority", 1); put("theme", "emergency")
                })
                onEdit(alerts.size)
            }
            Box {
                ActionChip(t.e("add_from_template")) { templates = true }
                DropdownMenu(templates, { templates = false }, containerColor = Field) {
                    t.templates.forEach { (_, tpl) ->
                        DropdownMenuItem(
                            text = { Column { Text("${tpl.s("icon") ?: ""} ${t.templateName(tpl)}", color = Color.White); Text(t.templateDesc(tpl), color = Muted, fontSize = 12.sp) } },
                            onClick = { templates = false; setAlerts(alerts + tpl.objs("alerts")) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlertPanel(
    index: Int,
    alert: JsonObject,
    states: Map<String, EntityState>,
    onBack: () -> Unit,
    update: (String, JsonElement?) -> Unit,
) {
    val t = App.instance.alerts.i18n
    val a = alert
    fun bool(key: String, v: Boolean, default: Boolean = false) = update(key, if (v == default) null else JsonPrimitive(v))
    Row(verticalAlignment = Alignment.CenterVertically) {
        ActionChip("◀ " + t.e("back")) { onBack() }
        Text("  ${t.e("alert_num")} ${index + 1}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
    Section(t.e("alert_num") + " ${index + 1}") {
        Help(t.e("timer_placeholder_hint"))
        SelectRow(t.e("alert_priority"), (a.i("priority") ?: 1).toString(), (1..4).map { it.toString() to t.e("priority_$it") }) { update("priority", JsonPrimitive(it.toInt())) }
        ThemeRow(t.e("alert_theme"), a.s("theme") ?: "emergency") { update("theme", JsonPrimitive(it)) }
        TextRow(t.e("alert_name"), t.e("alert_name_help"), a.s("name"), placeholder = t.e("alert_name_placeholder")) { update("name", str(it)) }
    }
    val filterMode = a.s("entity").isNullOrEmpty() && (a.s("entity_filter") != null || a.s("device_class") != null || a["label_filter"] != null || a["area_filter"] != null)
    var mode by remember(index) { mutableStateOf(if (filterMode) "filter" else "entity") }
    Section(if (mode == "entity") t.e("mode_entity") else t.e("mode_filter")) {
        SelectRow(t.a("mode"), mode, listOf("entity" to t.e("mode_entity"), "filter" to t.e("mode_filter"))) {
            mode = it
            if (it == "filter") update("entity", null) else { update("entity_filter", null) }
        }
        Help(if (mode == "entity") t.e("mode_entity_help") else t.e("mode_filter_help"))
        if (mode == "entity") {
            EntityRow(t.e("alert_entity"), a.s("entity"), states) { update("entity", str(it)) }
            SuggestRow(t.e("alert_attribute"), t.e("alert_attribute_help"), a.s("attribute"), attributeOptions(states, a.s("entity"))) { update("attribute", str(it)) }
            a.s("entity")?.let { states[it] }?.let { es ->
                Help("${t.e("current_state")} : ${rawValue(es, a.s("attribute")).ifEmpty { "\"\"" }}")
            }
        } else {
            TextRow(t.e("entity_filter"), t.e("entity_filter_help"), a.s("entity_filter")) { update("entity_filter", str(it)) }
            FilterMatches(a, states, update)
            SuggestRow(t.e("device_class"), t.e("device_class_help"), a.s("device_class"), deviceClassOptions(states)) { update("device_class", str(it)) }
            SuggestRow(t.e("label_filter"), t.e("label_filter_help"), a.s("label_filter"), App.instance.alerts.registry.labels.entries.sortedBy { it.value.lowercase() }.map { it.key to it.value }) { update("label_filter", str(it)) }
            SuggestRow(t.e("area_filter"), t.e("area_filter_help"), a.s("area_filter"), App.instance.alerts.registry.areas.entries.sortedBy { it.value.lowercase() }.map { it.key to it.value }) { update("area_filter", str(it)) }
            SwitchRow(t.e("show_filter_name"), null, a.b("show_filter_name", true)) { bool("show_filter_name", it, true) }
            SwitchRow(t.e("show_filter_state"), null, a.b("show_filter_state")) { bool("show_filter_state", it) }
            if (a.s("theme") == "battery") SwitchRow(t.e("battery_trim_name"), null, a.b("battery_trim_name")) { bool("battery_trim_name", it) }
        }
    }
    if (a.s("theme") == "music") Section("🎵 " + t.e("music_player_controls")) {
        Help(t.e("music_player_controls_help"))
        SwitchRow(t.e("music_player_controls"), null, a.b("show_player_controls")) { bool("show_player_controls", it) }
        TextRow(t.e("music_player_color"), null, a.s("music_player_color")) { update("music_player_color", str(it)) }
        SwitchRow(t.e("music_compact_layout"), null, a.b("music_compact_layout")) { bool("music_compact_layout", it) }
        SwitchRow(t.e("music_compact_show_badge"), null, a.b("music_compact_show_badge", true)) { bool("music_compact_show_badge", it, true) }
        listOf("music_show_art", "music_show_title", "music_show_artist", "music_show_controls", "music_show_power", "music_show_player_picker").forEach { k ->
            SwitchRow(t.e(k), null, a.b(k, true)) { bool(k, it, true) }
        }
    }
    if (mode == "filter") Section(t.e("group_section")) {
        SwitchRow(t.e("group"), null, a.b("group")) { bool("group", it) }
        if (a.b("group")) {
            NumberRow(t.e("group_min"), t.e("group_min_help"), a.s("group_min")) { update("group_min", num(it)) }
            TextRow(t.e("group_message"), t.e("group_message_help"), a.s("group_message")) { update("group_message", str(it)) }
            TextRow(t.e("group_secondary_text"), t.e("group_secondary_text_help"), a.s("group_secondary_text")) { update("group_secondary_text", str(it)) }
            TextRow(t.e("group_expanded_message"), t.e("group_expanded_message_help"), a.s("group_expanded_message")) { update("group_expanded_message", str(it)) }
            ActionEditor(t.e("group_tap_action_section"), a.o("group_tap_action"), states) { update("group_tap_action", it) }
            ActionEditor(t.e("group_hold_action_section"), a.o("group_hold_action"), states) { update("group_hold_action", it) }
        }
    }
    Section(t.e("conditions_section")) {
        SwitchRow(t.e("on_change"), t.e("on_change_help"), a.b("on_change")) { bool("on_change", it) }
        OperatorRow(a.s("operator") ?: "=") { update("operator", JsonPrimitive(it)) }
        TextRow(t.e("alert_state"), t.e("alert_state_help"), a["state"]?.let { jsString(it) }) { update("state", it?.let { v -> JsonPrimitive(v) } ?: JsonPrimitive("")) }
        val conds = a.objs("conditions")
        if (conds.isNotEmpty()) SelectRow(t.e("conditions_logic"), a.s("conditions_logic") ?: "and", listOf("and" to t.e("logic_and"), "or" to t.e("logic_or"))) { update("conditions_logic", str(it)) }
        conds.forEachIndexed { ci, cnd ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF1A1A20)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                fun setCond(k: String, v: JsonElement?) = update("conditions", JsonArray(conds.mapIndexed { j, x -> if (j == ci) x.with(k, v) else x }))
                EntityRow(t.e("condition_entity"), cnd.s("entity"), states) { setCond("entity", str(it)) }
                SuggestRow(t.e("condition_attribute"), null, cnd.s("attribute"), attributeOptions(states, cnd.s("entity"))) { setCond("attribute", str(it)) }
                OperatorRow(cnd.s("operator") ?: "=") { setCond("operator", JsonPrimitive(it)) }
                TextRow(t.e("alert_state"), null, cnd["state"]?.let { jsString(it) }) { setCond("state", str(it)) }
                ActionChip("🗑 " + t.e("delete_item")) { update("conditions", JsonArray(conds.filterIndexed { j, _ -> j != ci }).takeIf { it.isNotEmpty() }) }
            }
        }
        ActionChip("+ " + t.e("add_condition")) {
            update("conditions", JsonArray(conds + buildJsonObject { put("entity", ""); put("operator", "="); put("state", "on") }))
        }
    }
    Section(t.e("auto_dismiss_section")) {
        NumberRow(t.e("trigger_delay"), t.e("trigger_delay_help"), a.s("trigger_delay")) { update("trigger_delay", num(it)) }
        NumberRow(t.e("auto_dismiss_after"), t.e("auto_dismiss_after_help"), a.s("auto_dismiss_after")) { update("auto_dismiss_after", num(it)) }
    }
    Section(t.e("alert_message")) {
        TextRow(t.e("alert_message"), t.e("alert_message_help"), a.s("message"), multiline = true, placeholder = App.instance.alerts.i18n.defaultMessage(a.s("theme") ?: "emergency")) { update("message", str(it) ?: JsonPrimitive("")) }
        Help(t.e("message_placeholder_hint"))
        SwitchRow(t.e("use_ha_icon"), null, a.b("use_ha_icon")) { bool("use_ha_icon", it) }
        SwitchRow(t.e("use_entity_picture"), null, a.b("use_entity_picture")) { bool("use_entity_picture", it) }
        TextRow(t.e("icon_image"), t.e("icon_image_help"), a.s("icon_image")) { update("icon_image", str(it)) }
        TextRow(t.e("alert_icon"), t.e("alert_icon_help"), a.s("icon")) { update("icon", str(it)) }
        TextRow(t.e("icon_size"), t.e("icon_size_help"), a.s("icon_size")) { update("icon_size", str(it)) }
        TextRow(t.e("icon_color"), t.e("icon_color_help"), a.s("icon_color")) { update("icon_color", str(it)) }
        TextRow(t.e("accent_color"), t.e("accent_color_help"), a.s("color")) { update("color", str(it)) }
        SwitchRow(t.e("show_badge"), null, a.b("show_badge", true)) { bool("show_badge", it, true) }
        TextRow(t.e("badge_label"), t.e("badge_label_help"), a.s("badge_label")) { update("badge_label", str(it)) }
        EntityRow(t.e("secondary_entity"), a.s("secondary_entity"), states, help = t.e("secondary_entity_help")) { update("secondary_entity", str(it)) }
        SuggestRow(t.e("secondary_attribute"), null, a.s("secondary_attribute"), attributeOptions(states, a.s("secondary_entity"))) { update("secondary_attribute", str(it)) }
        SwitchRow(t.e("show_secondary_name"), null, a.b("show_secondary_name")) { bool("show_secondary_name", it) }
        TextRow(t.e("secondary_text"), t.e("secondary_text_help"), a.s("secondary_text")) { update("secondary_text", str(it)) }
        SelectRow(t.e("secondary_value_align"), a.s("secondary_value_align") ?: "below", listOf("below" to t.e("secondary_value_align_below"), "right" to t.e("secondary_value_align_right"))) {
            update("secondary_value_align", if (it == "below") null else JsonPrimitive(it))
        }
        TextRow(t.a("history_message"), t.a("history_message_help"), a.s("history_message")) { update("history_message", str(it)) }
    }
    Section(t.e("section_snooze")) {
        SnoozeDuration(t.e("snooze_duration"), t.e("snooze_duration_help"), a["snooze_duration"], t.e("snooze_duration_menu"), t.e("snooze_duration_global")) { update("snooze_duration", it) }
        SwitchRow(t.e("persistent"), t.e("persistent_help"), a.b("persistent")) { bool("persistent", it) }
        SwitchRow(t.e("alert_overlay"), t.e("alert_overlay_help"), a.b("overlay", true)) { bool("overlay", it, true) }
        SwitchRow(t.e("disable_animation"), t.e("disable_animation_alert_help"), a.b("disable_animation")) { bool("disable_animation", it) }
    }
    Section("🔊 " + t.e("alert_sound")) {
        SwitchRow(t.e("alert_sound"), t.e("sound_enabled_help"), a.b("sound")) { bool("sound", it) }
        TextRow(t.e("alert_sound_url"), t.e("alert_sound_url_help"), a.s("sound_url")) { update("sound_url", str(it)) }
    }
    Section(t.e("section_tts")) {
        SwitchRow(t.e("alert_tts"), t.e("alert_tts_help"), a.b("tts")) { bool("tts", it) }
        if (a.b("tts")) {
            EntityRow(t.e("alert_tts_entity"), a.s("tts_entity"), states, "media_player.") { update("tts_entity", str(it)) }
            EntityRow(t.e("alert_tts_engine"), a.s("tts_engine"), states, "tts.") { update("tts_engine", str(it)) }
            SuggestRow(t.e("alert_tts_notify_service"), null, a.s("tts_notify_service"), notifyOptions()) { update("tts_notify_service", str(it)) }
            SelectRow(t.e("alert_tts_notify_type"), a.s("tts_notify_type") ?: "", listOf("" to "—", "tts" to t.e("tts_notify_type_tts"), "announce" to t.e("tts_notify_type_announce"))) { update("tts_notify_type", str(it)) }
            TextRow(t.e("alert_tts_message"), t.e("alert_tts_message_help"), a.s("tts_message")) { update("tts_message", str(it)) }
        }
    }
    Section(t.e("section_push_notify")) {
        SwitchRow(t.e("alert_push_notify"), t.e("alert_push_notify_help"), a.b("push_notify")) { bool("push_notify", it) }
        if (a.b("push_notify")) {
            SuggestRow(t.e("alert_push_notify_service"), null, a.s("push_notify_service"), notifyOptions()) { update("push_notify_service", str(it)) }
            TextRow(t.e("alert_push_notify_title"), t.e("alert_push_notify_title_help"), a.s("push_notify_title")) { update("push_notify_title", str(it)) }
            TextRow(t.e("alert_push_notify_message"), t.e("alert_push_notify_message_help"), a.s("push_notify_message")) { update("push_notify_message", str(it)) }
            JsonRow(t.e("alert_push_notify_data"), t.e("alert_push_notify_data_help"), a["push_notify_data"]) { update("push_notify_data", it) }
        }
    }
    Section("📷 " + t.e("alert_camera_entity")) {
        EntityRow(t.e("alert_camera_entity"), a.s("camera_entity"), states, "camera.", t.e("alert_camera_entity_help")) { update("camera_entity", str(it)) }
        SwitchRow(t.e("alert_camera_live"), t.e("alert_camera_live_help"), a.b("camera_live")) { bool("camera_live", it) }
        SwitchRow(t.e("alert_camera_in_card"), t.e("alert_camera_in_card_help"), a.b("camera_in_card")) { bool("camera_in_card", it) }
    }
    Section(t.e("time_range_section")) {
        Help(t.e("time_range_help"))
        val tr = a.o("time_range") ?: JsonObject(emptyMap())
        fun setTr(k: String, v: String?) { val n = tr.with(k, str(v)); update("time_range", n.takeIf { it.isNotEmpty() }) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { TextRow(t.e("time_range_from"), null, tr.s("from")) { setTr("from", it) } }
            Box(Modifier.weight(1f)) { TextRow(t.e("time_range_to"), null, tr.s("to")) { setTr("to", it) } }
        }
    }
    ActionEditor(t.e("tap_action_section"), a.o("tap_action"), states) { update("tap_action", it) }
    ActionEditor(t.e("double_tap_action_section"), a.o("double_tap_action"), states) { update("double_tap_action", it) }
    ActionEditor(t.e("hold_action_section"), a.o("hold_action"), states) { update("hold_action", it) }
    ActionEditor(t.e("snooze_action_section"), a.o("snooze_action"), states) { update("snooze_action", it) }
    Section(t.e("section_automation")) {
        EntityRow(t.e("active_state_entity"), a.s("active_state_entity"), states, "input_boolean.", t.e("active_state_entity_help")) { update("active_state_entity", str(it)) }
    }
    Section(t.e("card_override_section")) {
        Help(t.e("card_override_help") + "\nDans l'app : une carte du tableau de bord (ex. {\"type\": \"button\", \"entity\": \"this.entity_id\"}).")
        JsonRow(t.e("card_override_section"), null, a["card"]) { update("card", it) }
    }
    ActionChip(if (a.b("enabled", true)) t.e("disable_alert") else t.e("enable_alert")) { bool("enabled", !a.b("enabled", true), true) }
}

/** Entités correspondant au filtre, avec exclusion au toucher et « Inverser la sélection ». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterMatches(a: JsonObject, states: Map<String, EntityState>, update: (String, JsonElement?) -> Unit) {
    val t = App.instance.alerts.i18n
    val filter = a.s("entity_filter")?.takeIf { it.isNotEmpty() } ?: return
    val matcher = AlertEngine.filterMatcher(filter)
    val dc = a.s("device_class")?.takeIf { it.isNotEmpty() }
    val matched = states.values.filter { e -> (dc == null || e.attrString("device_class") == dc) && (matcher(e.entityId) || matcher(e.attrString("friendly_name") ?: "")) }.sortedBy { it.entityId }
    val excluded = a.strs("entity_filter_exclude").toSet()
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (matched.isEmpty()) t.e("entity_filter_zero") else "${matched.size} ${t.e("entity_filter_count")} · ${excluded.size} ${t.e("entity_filter_excluded")}",
            Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = !open }.background(Field).padding(horizontal = 10.dp, vertical = 6.dp),
            color = Dash.colors.accent, fontSize = 13.sp,
        )
        if (open && matched.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            ActionChip(t.e("entity_filter_invert")) {
                val inv = matched.map { it.entityId }.filter { it !in excluded }
                update("entity_filter_exclude", JsonArray(inv.map { JsonPrimitive(it) }).takeIf { it.isNotEmpty() })
            }
        }
    }
    if (open) {
        Help(t.e("entity_filter_exclude_tip"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            matched.forEach { e ->
                val ex = e.entityId in excluded
                Text(
                    "${e.entityId}  ${e.state}",
                    Modifier.clip(RoundedCornerShape(8.dp)).background(if (ex) Color(0x33FF5252) else Color(0x2240C4FF))
                        .clickable {
                            val n = if (ex) excluded - e.entityId else excluded + e.entityId
                            update("entity_filter_exclude", JsonArray(n.sorted().map { JsonPrimitive(it) }).takeIf { it.isNotEmpty() })
                        }.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = if (ex) Color(0x99FF8A80) else Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Composants de formulaire
// ------------------------------------------------------------------------------------------------

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        content()
    }
}

@Composable private fun SubTitle(text: String) = Text(text, color = Dash.colors.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
@Composable private fun Help(text: String) = Text(text, color = Muted, fontSize = 12.sp, lineHeight = 16.sp)

@Composable
private fun ActionChip(label: String?, primary: Boolean = false, onClick: () -> Unit) {
    if (label == null) return
    Text(
        label,
        Modifier.clip(RoundedCornerShape(18.dp)).background(if (primary) Dash.colors.accent else Field).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        color = if (primary) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun IconBtn(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) Color.White else Color(0x33FFFFFF), fontSize = 15.sp)
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Dash.colors.accent, unfocusedBorderColor = Color(0x33FFFFFF),
    focusedLabelColor = Dash.colors.accent, unfocusedLabelColor = Muted, cursorColor = Dash.colors.accent, focusedContainerColor = Field, unfocusedContainerColor = Field,
)

/** Champ texte qui n'écrit dans la configuration qu'après une courte pause (saisie fluide). */
@Composable
private fun TextRow(label: String, help: String?, value: String?, multiline: Boolean = false, placeholder: String? = null, number: Boolean = false, onChange: (String?) -> Unit) {
    var text by remember(label) { mutableStateOf(value ?: "") }
    LaunchedEffect(value) { if ((value ?: "") != text) text = value ?: "" }
    LaunchedEffect(text) {
        delay(350)
        if (text != (value ?: "")) onChange(text.ifEmpty { null })
    }
    Column {
        OutlinedTextField(
            text, { text = it },
            Modifier.fillMaxWidth().then(if (multiline) Modifier.heightIn(min = 80.dp) else Modifier),
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it, color = Color(0x55FFFFFF)) } },
            singleLine = !multiline,
            colors = fieldColors(),
            keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        )
        help?.let { Help(it) }
    }
}

@Composable
private fun NumberRow(label: String, help: String?, value: String?, onChange: (String?) -> Unit) = TextRow(label, help, value, number = true, onChange = onChange)

@Composable
private fun JsonRow(label: String, help: String?, value: JsonElement?, onChange: (JsonElement?) -> Unit) {
    val pretty = remember(value) { value?.let { Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), it) } }
    var error by remember { mutableStateOf<String?>(null) }
    TextRow(label, error ?: help, pretty, multiline = true) { txt ->
        if (txt.isNullOrBlank()) { error = null; onChange(null) }
        else runCatching { Json.parseToJsonElement(txt) }.onSuccess { error = null; onChange(it) }.onFailure { error = App.instance.alerts.i18n.a("json_invalid", it.message) }
    }
}

@Composable
private fun SwitchRow(title: String, help: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Color.White, fontSize = 14.sp)
            help?.let { Help(it) }
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Dash.colors.accent))
    }
}

@Composable
private fun SelectRow(label: String, value: String, options: List<Pair<String, String>>, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, color = Muted, fontSize = 12.sp)
        Box {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Field).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(8.dp))
                    .clickable { open = true }.padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(options.firstOrNull { it.first == value }?.second ?: value, Modifier.weight(1f), color = Color.White, fontSize = 15.sp)
                Text("▾", color = Muted)
            }
            DropdownMenu(open, { open = false }, containerColor = Field, modifier = Modifier.heightIn(max = 420.dp)) {
                options.forEach { (k, l) -> DropdownMenuItem(text = { Text(l, color = if (k == value) Dash.colors.accent else Color.White) }, onClick = { open = false; onChange(k) }) }
            }
        }
    }
}

@Composable
private fun OperatorRow(value: String, onChange: (String) -> Unit) {
    val t = App.instance.alerts.i18n
    SelectRow(t.e("alert_operator"), value, listOf(
        "=" to t.e("op_eq"), "!=" to t.e("op_ne"), ">" to t.e("op_gt"), "<" to t.e("op_lt"), ">=" to t.e("op_gte"), "<=" to t.e("op_lte"),
        "contains" to t.e("op_contains"), "not_contains" to t.e("op_not_contains"), "older" to t.e("op_older"), "newer" to t.e("op_newer"),
    ), onChange)
}

/** Thèmes groupés par catégorie, avec leur description (comme l'éditeur d'origine). */
@Composable
private fun ThemeRow(label: String, value: String, onChange: (String) -> Unit) {
    val t = App.instance.alerts.i18n
    val groups = listOf(Category.Critical to "critical", Category.Warning to "warning", Category.Info to "info", Category.Ok to "ok", Category.Style to "style", Category.Timer to "timer")
    val options = groups.flatMap { (cat, key) ->
        val head = "—" to "── ${if (cat == Category.Timer) "⏱️ " + t.e("timer_theme_category") else t.groupLabel(key)} ──"
        listOf(head) + AtcThemes.all.filter { it.category == cat }.map { th ->
            th.id to "${th.emoji} ${th.id.replaceFirstChar { it.uppercase() }}" + (t.themeDesc(th.id)?.let { " ($it)" } ?: "")
        }
    }
    SelectRow(label, value, options) { if (it != "—") onChange(it) }
}

/** Durée de snooze : menu, valeur globale ou nombre d'heures. */
@Composable
private fun SnoozeDuration(label: String, help: String, value: JsonElement?, menuLabel: String, globalLabel: String?, onChange: (JsonElement?) -> Unit) {
    val current = when {
        value == null -> if (globalLabel != null) "global" else "menu"
        value is kotlinx.serialization.json.JsonNull -> "menu"
        else -> "hours"
    }
    val opts = listOfNotNull(globalLabel?.let { "global" to it }, "menu" to menuLabel, "hours" to App.instance.alerts.i18n.a("fixed_hours"))
    SelectRow(label, current, opts) {
        onChange(when (it) { "global" -> null; "menu" -> if (globalLabel != null) kotlinx.serialization.json.JsonNull else null; else -> JsonPrimitive(8) })
    }
    if (current == "hours") NumberRow(App.instance.alerts.i18n.a("hours"), help, jsString(value)) { onChange(num(it) ?: JsonPrimitive(1)) } else Help(help)
}

/**
 * Saisie libre avec liste de choix : [options] = (valeur enregistrée, description). La liste se filtre pendant la
 * frappe ; une valeur absente de la liste reste possible.
 */
@Composable
private fun SuggestRow(label: String, help: String?, value: String?, options: List<Pair<String, String>>, onChange: (String?) -> Unit) {
    var text by remember(label) { mutableStateOf(value ?: "") }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if ((value ?: "") != text) text = value ?: "" }
    val q = text.lowercase()
    val shown = options.filter { (v, d) -> q.isEmpty() || v.lowercase().contains(q) || d.lowercase().contains(q) || options.any { it.first == text } }.take(60)
    Column {
        Box {
            OutlinedTextField(
                text, { text = it; open = true }, Modifier.fillMaxWidth(),
                label = { Text(label) }, singleLine = true, colors = fieldColors(),
                trailingIcon = if (options.isEmpty()) null else ({ Text("▾", Modifier.clickable { open = !open }.padding(8.dp), color = Muted) }),
            )
            DropdownMenu(open && shown.isNotEmpty(), { open = false; if (text != (value ?: "")) onChange(text.ifEmpty { null }) },
                containerColor = Field, modifier = Modifier.heightIn(max = 360.dp)) {
                shown.forEach { (v, d) ->
                    DropdownMenuItem(
                        text = { Column { Text(d.ifEmpty { v }, color = Color.White, fontSize = 14.sp); if (d.isNotEmpty() && d != v) Text(v, color = Muted, fontSize = 11.sp) } },
                        onClick = { text = v; open = false; onChange(v) },
                    )
                }
            }
        }
        LaunchedEffect(text) { delay(600); if (!open && text != (value ?: "")) onChange(text.ifEmpty { null }) }
        help?.let { Help(it) }
    }
}

/** Services et pipelines vocaux de Home Assistant, chargés une fois. */
private object HaLookups {
    @Volatile var services: Map<String, List<String>>? = null
    @Volatile var pipelines: List<Pair<String, String>>? = null

    suspend fun loadServices(): Map<String, List<String>> = services ?: runCatching {
        val r = App.instance.ha.call(buildJsonObject { put("type", "get_services") }, 30_000) as JsonObject
        r.mapValues { (_, v) -> (v as? JsonObject)?.keys?.sorted().orEmpty() }.toSortedMap()
    }.getOrDefault(emptyMap()).also { if (it.isNotEmpty()) services = it }

    suspend fun loadPipelines(): List<Pair<String, String>> = pipelines ?: runCatching {
        val r = App.instance.ha.call(buildJsonObject { put("type", "assist_pipeline/pipeline/list") }, 30_000) as JsonObject
        (r["pipelines"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { p -> (p.s("id") ?: return@mapNotNull null) to (p.s("name") ?: "") } }
    }.getOrDefault(emptyList()).also { if (it.isNotEmpty()) pipelines = it }
}

@Composable
private fun rememberServices(): Map<String, List<String>> =
    androidx.compose.runtime.produceState(HaLookups.services ?: emptyMap()) { value = HaLookups.loadServices() }.value

/** Services de notification (`notify.xxx`, enregistrés sans le préfixe). */
@Composable
private fun notifyOptions(): List<Pair<String, String>> = rememberServices()["notify"].orEmpty().map { it to "notify.$it" }

/** Tous les services, `domaine.service`. */
@Composable
private fun serviceOptions(): List<Pair<String, String>> = rememberServices().flatMap { (d, list) -> list.map { "$d.$it" to "" } }

@Composable
private fun pipelineOptions(): List<Pair<String, String>> =
    androidx.compose.runtime.produceState(HaLookups.pipelines ?: emptyList()) { value = HaLookups.loadPipelines() }.value

/** Attributs de l'entité [entity] (valeur actuelle en description). */
private fun attributeOptions(states: Map<String, EntityState>, entity: String?): List<Pair<String, String>> =
    entity?.let { states[it] }?.attributes?.entries?.sortedBy { it.key }?.map { (k, v) -> k to "$k = ${(v as? JsonPrimitive)?.content ?: v.toString()}".take(80) }.orEmpty()

/** Classes d'appareil présentes dans Home Assistant. */
private fun deviceClassOptions(states: Map<String, EntityState>): List<Pair<String, String>> =
    states.values.mapNotNull { it.attrString("device_class") }.distinct().sorted().map { it to "" }

/** Destinations de navigation du tableau de bord : popups et pages des sélecteurs (`#id`). */
private fun navigationOptions(): List<Pair<String, String>> {
    val dash = App.instance.config.dashboard.value
    val out = ArrayList<Pair<String, String>>()
    dash.o("popups")?.forEach { (id, p) -> out += "#$id" to ((p as? JsonObject)?.s("name") ?: id) }
    fun walk(e: JsonElement) {
        when (e) {
            is JsonObject -> {
                if (e.s("type") == "switcher") e.objs("pages").forEach { p -> p.s("id")?.let { out += "#$it" to (p.s("label") ?: it) } }
                e.values.forEach { walk(it) }
            }
            is JsonArray -> e.forEach { walk(it) }
            else -> Unit
        }
    }
    walk(dash)
    return out.distinctBy { it.first }
}

/** Sélecteur d'entité : saisie libre avec suggestions parmi les entités de Home Assistant. */
@Composable
private fun EntityRow(label: String, value: String?, states: Map<String, EntityState>, prefix: String? = null, help: String? = null, onChange: (String?) -> Unit) {
    var text by remember(label) { mutableStateOf(value ?: "") }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if ((value ?: "") != text) text = value ?: "" }
    val suggestions = remember(text, states.size) {
        val q = text.lowercase()
        states.values.asSequence()
            .filter { prefix == null || it.entityId.startsWith(prefix) }
            .filter { q.isEmpty() || it.entityId.contains(q) || (it.attrString("friendly_name")?.lowercase()?.contains(q) == true) }
            .take(40).toList()
    }
    Column {
        Box {
            OutlinedTextField(
                text, { text = it; open = true }, Modifier.fillMaxWidth(),
                label = { Text(label) }, singleLine = true, colors = fieldColors(),
                trailingIcon = { Text("▾", Modifier.clickable { open = !open }.padding(8.dp), color = Muted) },
            )
            DropdownMenu(open && suggestions.isNotEmpty(), { open = false; if (text != (value ?: "")) onChange(text.ifEmpty { null }) },
                containerColor = Field, modifier = Modifier.heightIn(max = 360.dp)) {
                suggestions.forEach { e ->
                    DropdownMenuItem(
                        text = { Column { Text(e.attrString("friendly_name") ?: e.entityId, color = Color.White, fontSize = 14.sp); Text("${e.entityId} · ${e.state}", color = Muted, fontSize = 11.sp) } },
                        onClick = { text = e.entityId; open = false; onChange(e.entityId) },
                    )
                }
            }
        }
        LaunchedEffect(text) { delay(600); if (!open && text != (value ?: "")) onChange(text.ifEmpty { null }) }
        help?.let { Help(it) }
    }
}

/** Action au toucher / maintien / double toucher / snooze (format Lovelace). */
@Composable
private fun ActionEditor(title: String, action: JsonObject?, states: Map<String, EntityState>, onChange: (JsonObject?) -> Unit) {
    val t = App.instance.alerts.i18n
    val a = action ?: JsonObject(emptyMap())
    val kind = a.s("action") ?: "none"
    fun set(k: String, v: JsonElement?) { val n = a.with(k, v); onChange(if (n.s("action") == null || n.s("action") == "none" && n.size <= 1) null else n) }
    Section(title) {
        SelectRow(t.e("action_type"), kind, listOf(
            "none" to t.e("action_none"), "call-service" to t.e("action_call_service"), "navigate" to t.e("action_navigate"),
            "more-info" to t.e("action_more_info"), "url" to t.e("action_url"), "toggle" to "Toggle", "assist" to t.e("action_assist"),
            "dismiss" to t.e("action_dismiss"), "undismiss" to t.e("action_undismiss"),
        )) { set("action", if (it == "none") null else JsonPrimitive(it)) }
        when (kind) {
            "call-service", "perform-action" -> {
                SuggestRow(t.e("action_service"), null, a.s("service") ?: a.s("perform_action"), serviceOptions()) { set("service", str(it)) }
                EntityRow(t.e("action_target"), a.o("target")?.s("entity_id"), states) { set("target", it?.let { e -> buildJsonObject { put("entity_id", e) } }) }
                JsonRow(t.e("action_service_data"), null, a["data"] ?: a["service_data"]) { set("data", it) }
            }
            "navigate" -> SuggestRow(t.e("action_navigate_path"), null, a.s("navigation_path"), navigationOptions()) { set("navigation_path", str(it)) }
            "url" -> TextRow(t.e("action_url_path"), null, a.s("url_path")) { set("url_path", str(it)) }
            "more-info", "toggle" -> EntityRow(t.e("alert_entity"), a.s("entity"), states) { set("entity", str(it)) }
            "assist" -> {
                SuggestRow(t.e("action_pipeline_id"), t.e("action_pipeline_id_help"), a.s("pipeline_id"), pipelineOptions()) { set("pipeline_id", str(it)) }
                SwitchRow(t.e("action_start_listening"), null, a.b("start_listening")) { set("start_listening", if (it) JsonPrimitive(true) else null) }
            }
        }
        if (kind != "none") {
            SwitchRow(t.e("action_confirmation"), t.e("action_confirmation_help"), a["confirmation"] != null) {
                set("confirmation", if (it) buildJsonObject { put("text", t.e("action_confirmation_placeholder")) } else null)
            }
            a.o("confirmation")?.let { c -> TextRow(t.e("action_confirmation_text"), null, c.s("text")) { v -> set("confirmation", buildJsonObject { put("text", v ?: "") }) } }
        }
    }
}
