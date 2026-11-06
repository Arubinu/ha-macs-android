package fr.pergens.hamacs.ui

import android.media.RingtoneManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import fr.pergens.hamacs.App
import fr.pergens.hamacs.R
import fr.pergens.hamacs.config.b
import fr.pergens.hamacs.config.s
import fr.pergens.hamacs.ui.theme.Dash
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Sonnette et détecteurs : une caméra peut s'ouvrir en plein écran quand une entité se déclenche (option du bloc
 * caméra : `event_entity`, `event_seconds`, `event_sound`), puis se refermer seule. Déclenchement : `on` pour un
 * capteur binaire, tout nouvel état pour un `event.*` / bouton, sinon tout changement d'état (hors indisponible).
 * Une caméra protégée par le code n'est pas ouverte.
 */
@Composable
fun CameraEventWatcher() {
    val app = App.instance
    val state = LocalDashState.current
    currentDashState = state
    LaunchedEffect(Unit) {
        // État précédent de toutes les entités : une entité choisie juste avant son premier changement est vue aussi.
        var previous: Map<String, fr.pergens.hamacs.ha.EntityState>? = null
        app.ha.states.collect { states ->
            val last = previous
            previous = states
            if (last == null) return@collect
            val watched = app.config.blocks.value.filter { (_, o) -> o.s("event_entity") != null }
            for ((camId, o) in watched) {
                val entity = o.s("event_entity") ?: continue
                val before = last[entity]?.state ?: continue
                val after = states[entity]?.state ?: continue
                if (before == after || after == "unavailable" || after == "unknown") continue
                val domain = entity.substringBefore('.')
                if (domain == "binary_sensor" && after != "on") continue
                if (o.b("protected") == true) continue
                open(camId, o)
            }
        }
    }
    // Fermeture automatique (si personne n'a touché l'écran entre-temps).
    val opened = state.player
    LaunchedEffect(opened, eventPlayer) {
        val ev = eventPlayer ?: return@LaunchedEffect
        if (opened != ev.url) { eventPlayer = null; return@LaunchedEffect }
        delay(ev.seconds * 1000L)
        if (state.player == ev.url && state.lastInteraction <= ev.at) state.player = null
        eventPlayer = null
    }
}

private class EventPlayer(val url: String, val seconds: Int, val at: Long)

private var eventPlayer by mutableStateOf<EventPlayer?>(null)

private fun open(camId: String, options: JsonObject) {
    val app = App.instance
    val state = currentDashState ?: return
    val card = findCard(app.config.rawDashboard.value, camId)
    val url = card?.s("fullscreen_url") ?: card?.s("fullscreen_entity") ?: card?.s("live_url") ?: camId
    val at = System.currentTimeMillis()
    // Réveille l'écran (veille, atténuation, écran noir de nuit) et affiche la caméra.
    state.lastInteraction = at
    state.player = url
    eventPlayer = EventPlayer(url, (options["event_seconds"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 60, at)
    if ((options["event_sound"] as? JsonPrimitive)?.content != "false") runCatching {
        RingtoneManager.getRingtone(app, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play()
    }
}

/** Carte caméra de l'agencement qui affiche [camId] (flux plein écran éventuel). */
private fun findCard(e: JsonElement, camId: String): JsonObject? = when (e) {
    is JsonObject -> if (e.s("entity") == camId && (e["fullscreen_entity"] != null || e["fullscreen_url"] != null || e["live_url"] != null)) e
        else e.values.firstNotNullOfOrNull { findCard(it, camId) }
    is JsonArray -> e.firstNotNullOfOrNull { findCard(it, camId) }
    else -> null
}

/** État du tableau de bord pour les déclenchements hors composition (fixé par [CameraEventWatcher]). */
internal var currentDashState: DashState? = null

/** Options « ouvrir sur un événement » de l'éditeur d'un bloc caméra. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CameraEventFields(optionKey: String, options: JsonObject?) {
    val app = App.instance
    var picking by remember { mutableStateOf(false) }
    val entity = options?.s("event_entity")
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.camera_event_title), style = TextStyle(fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Medium))
        Text(stringResource(R.string.camera_event_help), style = TextStyle(fontSize = 13.sp, color = Color(255, 255, 255, 150)))
        if (entity != null) {
            EntityCard(entity, onClick = { picking = true }) {
                EntityCardAction("mdi:close") { app.config.setBlockValue(optionKey, "event_entity", null) }
            }
            Text(stringResource(R.string.camera_event_duration), style = TextStyle(fontSize = 13.sp, color = Color(255, 255, 255, 150)))
            val seconds = (options["event_seconds"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 60
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 60, 120, 300).forEach { sec ->
                    FilterChip(
                        selected = seconds == sec,
                        onClick = { app.config.setBlockValue(optionKey, "event_seconds", JsonPrimitive(sec)) },
                        label = { Text(if (sec < 60) stringResource(R.string.idle_seconds, sec) else stringResource(R.string.idle_minutes, sec / 60)) },
                        modifier = Modifier.heightIn(min = 40.dp),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Dash.colors.accent.copy(alpha = 0.22f)),
                    )
                }
            }
            val sound = (options["event_sound"] as? JsonPrimitive)?.content != "false"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.camera_event_sound), Modifier.weight(1f), style = TextStyle(fontSize = 15.sp, color = Color.White))
                Switch(
                    sound, { app.config.setBlockOption(optionKey, "event_sound", if (it) null else false) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Dash.colors.accent, checkedThumbColor = Color.Black),
                )
            }
        } else {
            val shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
            Text(
                "+  " + stringResource(R.string.camera_event_choose),
                Modifier.fillMaxWidth().clip(shape).border(1.dp, Dash.colors.accent, shape).clickable { picking = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                style = TextStyle(fontSize = 15.sp, color = Dash.colors.accent, fontWeight = FontWeight.Medium, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            )
        }
    }
    if (picking) EntityPicker(null, onDismiss = { picking = false }) { id ->
        picking = false
        app.config.setBlockValue(optionKey, "event_entity", JsonPrimitive(id))
    }
}
