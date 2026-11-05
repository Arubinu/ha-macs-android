package fr.arubinu.hadash.ui

import android.net.Uri
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import fr.arubinu.hadash.config.BlockOverrides
import fr.arubinu.hadash.config.s
import fr.arubinu.hadash.ui.components.HaIcon
import fr.arubinu.hadash.ui.theme.Dash
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

private val Muted = Color(255, 255, 255, 150)

/** Calques de la carte Windy : (identifiant, paramètre `overlay`, paramètre `product`, libellé). */
private val WINDY_LAYERS = listOf(
    Triple("radar", "radar", R.string.windy_radar),
    Triple("satellite", "satellite", R.string.windy_satellite),
    Triple("rain", "ecmwf", R.string.windy_rain),
    Triple("wind", "ecmwf", R.string.windy_wind),
    Triple("gust", "ecmwf", R.string.windy_gust),
    Triple("temp", "ecmwf", R.string.windy_temp),
    Triple("clouds", "ecmwf", R.string.windy_clouds),
    Triple("thunder", "ecmwf", R.string.windy_thunder),
    Triple("snowcover", "ecmwf", R.string.windy_snow),
    Triple("pressure", "ecmwf", R.string.windy_pressure),
)

/** Remplace (ou ajoute) des paramètres dans l'adresse, sans toucher aux autres. */
private fun withParams(url: String, changes: Map<String, String>): String {
    val uri = Uri.parse(url)
    val b = uri.buildUpon().clearQuery()
    val names = uri.queryParameterNames
    names.forEach { n -> b.appendQueryParameter(n, changes[n] ?: uri.getQueryParameter(n)) }
    changes.filterKeys { it !in names }.forEach { (k, v) -> b.appendQueryParameter(k, v) }
    return b.build().toString()
}

/**
 * Réglages d'une page web embarquée [key] : pour Windy, calque (mode), zoom et position (zone de Home
 * Assistant ou coordonnées) ; l'adresse est reconstruite automatiquement. « Rétablir » revient à dashboard.json.
 */
@Composable
fun WebFields(key: String) {
    val app = App.instance
    val source by app.config.sourceDashboard.collectAsState()
    val raw by app.config.rawBlocks.collectAsState()
    val obj = remember(source, key) { BlockOverrides.find(source, key) } ?: return
    val original = obj.s("url") ?: return
    val current = ((raw[key]?.get("set") as? JsonObject)?.get("url") as? JsonPrimitive)?.content ?: original
    fun save(url: String) = app.config.setBlockField(key, "url", url.takeIf { it != original }?.let { JsonPrimitive(it) })

    if (obj.s("type") != "windy") {
        // Autre page : adresse modifiable directement.
        var text by remember(current) { mutableStateOf(current) }
        OutlinedTextField(
            text, { text = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.web_url)) }, singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Dash.colors.accent),
        )
        Row { SmallButton(stringResource(R.string.web_apply)) { save(text.trim()) }; if (current != original) SmallButton(stringResource(R.string.edit_entity_reset)) { save(original) } }
        return
    }

    val uri = Uri.parse(current)
    val overlay = uri.getQueryParameter("overlay") ?: "wind"
    val zoom = uri.getQueryParameter("zoom")?.toFloatOrNull() ?: 8f
    val lat = uri.getQueryParameter("lat") ?: ""
    val lon = uri.getQueryParameter("lon") ?: ""
    var pickZone by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Mode (calque), avec le retour aux valeurs de dashboard.json
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.windy_layer), Modifier.weight(1f), style = TextStyle(fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Medium))
            if (current != original) SmallButton(stringResource(R.string.edit_entity_reset)) { save(original) }
        }
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WINDY_LAYERS.forEach { (id, product, label) ->
                val sel = overlay == id
                val shape = RoundedCornerShape(16.dp)
                Text(
                    stringResource(label),
                    Modifier.clip(shape).background(if (sel) Dash.colors.accent else Color.Transparent, shape)
                        .border(1.dp, if (sel) Dash.colors.accent else Color.White.copy(alpha = 0.25f), shape)
                        .clickable { save(withParams(current, mapOf("overlay" to id, "product" to product))) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = TextStyle(fontSize = 14.sp, color = if (sel) Color.Black else Color.White),
                )
            }
        }
        // Zoom
        var z by remember(zoom) { mutableFloatStateOf(zoom) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.windy_zoom), Modifier.weight(1f), style = TextStyle(fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Medium))
            Text(z.roundToInt().toString(), style = TextStyle(fontSize = 15.sp, color = Dash.colors.accent, fontWeight = FontWeight.SemiBold))
        }
        Slider(
            z, { z = it.roundToInt().toFloat() }, valueRange = 3f..15f, steps = 11,
            onValueChangeFinished = { save(withParams(current, mapOf("zoom" to z.roundToInt().toString()))) },
            colors = SliderDefaults.colors(thumbColor = Dash.colors.accent, activeTrackColor = Dash.colors.accent),
        )
        // Position
        Text(stringResource(R.string.windy_location), style = TextStyle(fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Medium))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { pickZone = true }.heightIn(min = 48.dp).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HaIcon("mdi:map-marker-radius", size = 22.dp, tint = Dash.colors.accent)
            Text(stringResource(R.string.windy_pick_zone), Modifier.weight(1f).padding(start = 10.dp), style = TextStyle(fontSize = 14.sp, color = Color.White))
            HaIcon("mdi:chevron-right", size = 22.dp, tint = Muted)
        }
        var latText by remember(lat) { mutableStateOf(lat) }
        var lonText by remember(lon) { mutableStateOf(lon) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CoordField(latText, { latText = it }, stringResource(R.string.windy_lat), Modifier.weight(1f))
            CoordField(lonText, { lonText = it }, stringResource(R.string.windy_lon), Modifier.weight(1f))
        }
        SmallButton(stringResource(R.string.web_apply), Modifier.fillMaxWidth()) {
                val la = latText.replace(',', '.').toDoubleOrNull()
                val lo = lonText.replace(',', '.').toDoubleOrNull()
                if (la != null && lo != null) save(withParams(current, mapOf("lat" to "%.4f".format(java.util.Locale.ROOT, la), "lon" to "%.4f".format(java.util.Locale.ROOT, lo))))
        }
    }

    if (pickZone) EntityPicker("zone", onDismiss = { pickZone = false }) { id ->
        pickZone = false
        val z = app.ha.states.value[id]
        val la = z?.attrDouble("latitude")
        val lo = z?.attrDouble("longitude")
        if (la != null && lo != null) save(withParams(current, mapOf("lat" to "%.4f".format(java.util.Locale.ROOT, la), "lon" to "%.4f".format(java.util.Locale.ROOT, lo))))
    }
}

@Composable
private fun CoordField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier) {
    OutlinedTextField(
        value, onChange, modifier, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Dash.colors.accent),
    )
}

@Composable
private fun SmallButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Text(
        text, modifier.clip(shape).border(1.dp, Dash.colors.accent, shape).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        style = TextStyle(fontSize = 14.sp, color = Dash.colors.accent, fontWeight = FontWeight.Medium, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
    )
}
