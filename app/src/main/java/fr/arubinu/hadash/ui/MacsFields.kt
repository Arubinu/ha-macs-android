package fr.arubinu.hadash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.arubinu.hadash.App
import fr.arubinu.hadash.R
import fr.arubinu.hadash.config.b
import fr.arubinu.hadash.config.d
import fr.arubinu.hadash.config.i
import fr.arubinu.hadash.config.o
import fr.arubinu.hadash.config.s
import fr.arubinu.hadash.macs.MacsOptions
import fr.arubinu.hadash.ui.theme.Dash
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

private val Muted = Color(255, 255, 255, 150)

/** Options de M.A.C.S. : `macs` de dashboard.json, réglages de l'app, et enregistrement d'une option. */
private class MacsData(val base: JsonObject, val local: JsonObject) {
    val cfg = JsonObject(base + local)
    /** null = valeur de dashboard.json (ou rien) ; une valeur égale à dashboard.json retire la surcharge. */
    fun set(key: String, value: JsonElement?) {
        val next = when {
            value == null || value == base[key] -> if (value == null && base[key] != null && base[key] !is JsonNull) local + (key to JsonNull) else local - key
            else -> local + (key to value)
        }
        App.instance.config.setBlockConfig(MacsOptions.KEY, JsonObject(next).takeIf { it.isNotEmpty() })
    }
    fun text(key: String, v: String?) = set(key, v?.trim()?.takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) })
    fun number(key: String, v: String?) = set(key, v?.replace(',', '.')?.trim()?.toDoubleOrNull()?.let { JsonPrimitive(it) })
}

@Composable
private fun rememberMacs(): MacsData {
    val app = App.instance
    val dash by app.config.dashboard.collectAsState()
    val blocks by app.config.blocks.collectAsState()
    val base = dash.o("macs") ?: JsonObject(emptyMap())
    val local = blocks[MacsOptions.KEY]?.o("config") ?: JsonObject(emptyMap())
    return remember(base, local) { MacsData(base, local) }
}

/**
 * Réglages de M.A.C.S., comme l'éditeur de sa carte : humeur et satellite Assist, capteurs météo (entité, unité,
 * plage), conditions météo, batterie et charge, conversation, luminosité automatique et animations. Sans capteur,
 * M.A.C.S. utilise les entités de son intégration (`number.macs_temperature`…).
 */
@Composable
fun MacsFields() {
    val m = rememberMacs()
    val c = m.cfg
    var pick by remember { mutableStateOf<Pair<String, String?>?>(null) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // --- Humeur
        Section(stringResource(R.string.macs_section_mood))
        Indented {
            EntityRow(stringResource(R.string.macs_mood_entity), c.s("mood_entity") ?: "select.macs_mood", c.s("mood_entity").let { it != null && it != "select.macs_mood" }, { pick = "mood_entity" to "select" }) { m.text("mood_entity", null) }
            EntityRow(stringResource(R.string.macs_satellite), c.s("assist_satellite"), true, { pick = "assist_satellite" to "assist_satellite" }) { m.text("assist_satellite", null) }
            Help(stringResource(R.string.macs_satellite_help))
            SwitchLine(stringResource(R.string.macs_low_battery_sad), stringResource(R.string.macs_low_battery_sad_help), c.b("low_battery_sad", true)) {
                m.set("low_battery_sad", JsonPrimitive(it))
            }
        }

        // --- Capteurs météo
        Sensor(m, MacsOptions.Kind.Temperature, "temperature_sensor", stringResource(R.string.macs_section_temperature),
            listOf("" to stringResource(R.string.macs_unit_auto), "c" to "°C", "f" to "°F")) { pick = it to "sensor" }
        Sensor(m, MacsOptions.Kind.Wind, "wind_sensor", stringResource(R.string.macs_section_wind),
            listOf("" to stringResource(R.string.macs_unit_auto), "kph" to "km/h", "mps" to "m/s", "mph" to "mph", "knots" to stringResource(R.string.macs_unit_knots))) { pick = it to "sensor" }
        Sensor(m, MacsOptions.Kind.Precipitation, "precipitation_sensor", stringResource(R.string.macs_section_precipitation),
            listOf("" to stringResource(R.string.macs_unit_auto), "%" to stringResource(R.string.macs_unit_chance), "mm" to "mm", "in" to "in")) { pick = it to "sensor" }
        Section(stringResource(R.string.macs_section_conditions))
        Indented {
            EntityRow(stringResource(R.string.macs_weather_entity), c.s("weather_entity"), true, { pick = "weather_entity" to "weather" }) { m.text("weather_entity", null) }
            Help(stringResource(R.string.macs_weather_help))
        }

        // --- Batterie
        Section(stringResource(R.string.macs_section_battery))
        Indented {
            val mode = c.s("battery") ?: "device"
            Chips(
                listOf("device" to stringResource(R.string.macs_battery_device), "sensor" to stringResource(R.string.macs_battery_sensor),
                    "integration" to stringResource(R.string.macs_battery_integration), "none" to stringResource(R.string.macs_battery_none)),
                mode,
            ) { m.text("battery", it) }
            Help(stringResource(when (mode) {
                "sensor" -> R.string.macs_battery_sensor_help
                "integration" -> R.string.macs_battery_integration_help
                "none" -> R.string.macs_battery_none_help
                else -> R.string.macs_battery_device_help
            }))
            if (mode == "sensor") {
                EntityRow(stringResource(R.string.macs_battery_level), c.s("battery_sensor"), true, { pick = "battery_sensor" to "sensor" }) { m.text("battery_sensor", null) }
                UnitRange(m, MacsOptions.Kind.Battery, c.s("battery_sensor"), listOf("%" to "%", "v" to stringResource(R.string.macs_unit_volts)))
                EntityRow(stringResource(R.string.macs_charging), c.s("charging_sensor"), true, { pick = "charging_sensor" to "binary_sensor" }) { m.text("charging_sensor", null) }
            }
        }

        // --- Conversation
        Section(stringResource(R.string.macs_section_conversation))
        Indented {
            var turns by remember(c.i("max_turns", 2)) { mutableFloatStateOf(c.i("max_turns", 2).toFloat()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label(stringResource(R.string.macs_max_turns))
                    Help(stringResource(R.string.macs_max_turns_help))
                }
                Text(turns.roundToInt().toString(), style = TextStyle(fontSize = 15.sp, color = Dash.colors.accent, fontWeight = FontWeight.SemiBold))
            }
            Slider(
                turns, { turns = it.roundToInt().toFloat() }, valueRange = 0f..6f, steps = 5,
                onValueChangeFinished = { m.set("max_turns", JsonPrimitive(turns.roundToInt())) },
                colors = SliderDefaults.colors(thumbColor = Dash.colors.accent, activeTrackColor = Dash.colors.accent, inactiveTrackColor = Color.White.copy(alpha = 0.15f)),
            )
        }

        // --- Luminosité automatique et animations
        Section(stringResource(R.string.macs_section_display))
        Indented {
            val auto = c.b("auto_brightness_enabled")
            SwitchLine(stringResource(R.string.macs_auto_brightness), stringResource(R.string.macs_auto_brightness_help), auto) {
                m.set("auto_brightness_enabled", JsonPrimitive(it))
            }
            if (auto) {
                NumberField(c.d("auto_brightness_timeout_minutes")?.let(::fmt).orEmpty(), stringResource(R.string.macs_auto_brightness_timeout) + " (5)", "5") { m.number("auto_brightness_timeout_minutes", it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(c.d("auto_brightness_min")?.let(::fmt).orEmpty(), stringResource(R.string.macs_auto_brightness_min) + " (0)", "0", Modifier.weight(1f)) { m.number("auto_brightness_min", it) }
                    NumberField(c.d("auto_brightness_max")?.let(::fmt).orEmpty(), stringResource(R.string.macs_auto_brightness_max) + " (100)", "100", Modifier.weight(1f)) { m.number("auto_brightness_max", it) }
                }
                SwitchLine(stringResource(R.string.macs_pause_animations), null, c.b("auto_brightness_pause_animations", true)) {
                    m.set("auto_brightness_pause_animations", JsonPrimitive(it))
                }
            }
            val states by App.instance.ha.states.collectAsState()
            if (states["switch.macs_animations_enabled"] == null) {
                SwitchLine(stringResource(R.string.macs_animations), stringResource(R.string.macs_animations_help), c.b("animations_enabled", true)) {
                    m.set("animations_enabled", JsonPrimitive(it))
                }
            } else Help(stringResource(R.string.macs_animations_entity))
        }

        if (m.local.isNotEmpty()) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            TextLink(stringResource(R.string.macs_reset)) { App.instance.config.setBlockConfig(MacsOptions.KEY, null) }
        }
    }

    pick?.let { (key, domain) ->
        EntityPicker(domain, onDismiss = { pick = null }) { id ->
            pick = null
            m.text(key, id)
        }
    }
}

private fun fmt(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

/** Capteur numérique : entité, unité (auto = celle du capteur) et plage ramenée à 0–100. */
@Composable
private fun Sensor(m: MacsData, kind: MacsOptions.Kind, key: String, title: String, units: List<Pair<String, String>>, onPick: (String) -> Unit) {
    Section(title)
    Indented {
        val id = m.cfg.s(key)
        EntityRow(stringResource(R.string.macs_sensor), id, true, { onPick(key) }) { m.text(key, null) }
        // Température et vent : sans capteur, l'entité météo fournit la valeur.
        if (id.isNullOrBlank()) Help(stringResource(if (kind == MacsOptions.Kind.Temperature || kind == MacsOptions.Kind.Wind) R.string.macs_sensor_weather_help else R.string.macs_sensor_none_help))
        else UnitRange(m, kind, id, units)
    }
}

@Composable
private fun UnitRange(m: MacsData, kind: MacsOptions.Kind, sensor: String?, units: List<Pair<String, String>>) {
    val c = m.cfg
    val sensorUnit = App.instance.ha.states.collectAsState().value[sensor]?.unit
    val unit = MacsOptions.unit(kind, c.s(kind.unitKey), sensorUnit)
    val (dMin, dMax) = MacsOptions.defaultRange(kind, unit)
    Label(stringResource(R.string.macs_unit))
    Chips(units, c.s(kind.unitKey).orEmpty()) { m.text(kind.unitKey, it) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(c.d(kind.minKey)?.let(::fmt).orEmpty(), stringResource(R.string.macs_min) + " (" + fmt(dMin) + ")", fmt(dMin), Modifier.weight(1f)) { m.number(kind.minKey, it) }
        NumberField(c.d(kind.maxKey)?.let(::fmt).orEmpty(), stringResource(R.string.macs_max) + " (" + fmt(dMax) + ")", fmt(dMax), Modifier.weight(1f)) { m.number(kind.maxKey, it) }
    }
    Help(stringResource(R.string.macs_range_help))
}

/** Entité choisie (carte, toucher pour changer, ✕ pour retirer) ou bouton pour la choisir. */
@Composable
private fun EntityRow(label: String, id: String?, clearable: Boolean, onPick: () -> Unit, onClear: () -> Unit) {
    if (id.isNullOrBlank()) {
        Column {
            Text(label, Modifier.padding(start = 4.dp, bottom = 2.dp), style = TextStyle(fontSize = 13.sp, color = Muted, fontWeight = FontWeight.Medium))
            AddButton(stringResource(R.string.edit_entity_pick), onPick)
        }
    } else EntityCard(id, label = label, onClick = onPick) {
        if (clearable) EntityCardAction("mdi:close", onClick = onClear)
    }
}

@Composable
private fun Section(text: String) =
    Text(text, Modifier.padding(top = 10.dp, bottom = 2.dp), style = TextStyle(fontSize = 13.sp, color = Dash.colors.accent, fontWeight = FontWeight.SemiBold))

@Composable
private fun Indented(content: @Composable () -> Unit) =
    Column(Modifier.fillMaxWidth().padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }

@Composable
private fun Label(text: String) = Text(text, style = TextStyle(fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Medium))

@Composable
private fun Help(text: String) = Text(text, style = TextStyle(fontSize = 12.sp, color = Muted, lineHeight = 16.sp))

@Composable
private fun SwitchLine(title: String, help: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(!checked) }.heightIn(min = 56.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Label(title)
            help?.let { Help(it) }
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Dash.colors.accent, checkedThumbColor = Color.Black))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (id, label) ->
            val sel = id == selected
            val shape = RoundedCornerShape(16.dp)
            Text(
                label,
                Modifier.clip(shape).background(if (sel) Dash.colors.accent else Color.Transparent, shape)
                    .border(1.dp, if (sel) Dash.colors.accent else Color.White.copy(alpha = 0.25f), shape)
                    .clickable { onSelect(id) }.padding(horizontal = 12.dp, vertical = 6.dp),
                style = TextStyle(fontSize = 14.sp, color = if (sel) Color.Black else Color.White),
            )
        }
    }
}

/** Champ numérique enregistré un instant après la dernière frappe ; vide = valeur par défaut ([hint]). */
@Composable
private fun NumberField(value: String, label: String, hint: String?, modifier: Modifier = Modifier.fillMaxWidth(), onSave: (String?) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    LaunchedEffect(text) {
        if (text == value) return@LaunchedEffect
        delay(700)
        onSave(text.takeIf { it.isNotBlank() })
    }
    OutlinedTextField(
        text, { text = it }, modifier, label = { Text(label) }, singleLine = true,
        placeholder = hint?.let { { Text(it, color = Color.White.copy(alpha = 0.3f)) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Dash.colors.accent),
    )
}
