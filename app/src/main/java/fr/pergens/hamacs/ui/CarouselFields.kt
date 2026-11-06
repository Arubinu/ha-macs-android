package fr.pergens.hamacs.ui

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.pergens.hamacs.App
import fr.pergens.hamacs.R
import fr.pergens.hamacs.config.BlockOverrides
import fr.pergens.hamacs.config.i
import fr.pergens.hamacs.config.s
import fr.pergens.hamacs.ui.components.HaIcon
import fr.pergens.hamacs.ui.theme.Dash
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

private val Muted = Color(255, 255, 255, 150)
private val RowBg = Color(255, 255, 255, 10)

private fun JsonObject.flag(k: String, def: Boolean) = (this[k] as? JsonPrimitive)?.content?.let { it != "false" } ?: def

/** Nombre de courbes d'un graphique multi-entités (hors valeur principale et bande jour/nuit). */
private fun curveCount(card: JsonObject): Int? {
    val list = card["entities"] as? JsonArray ?: return null
    return list.withIndex().count { (i, e) ->
        val o = e as? JsonObject ?: return@count true
        o.s("y_axis") != "secondary" && !(i == 0 && !o.flag("show_graph", true))
    }
}

/** Cible d'édition d'une carte du carrousel (graphique : sa fenêtre dédiée). */
private fun targetOf(card: JsonObject): EditTarget? {
    val key = BlockOverrides.keyOf(card) ?: return null
    return when (card.s("type")) {
        "mini_graph", "power_graph" -> EditTarget.Graph("graph:${card.s("type")}:$key", key, card.i("height", if (card.s("type") == "power_graph") 300 else 220))
        else -> EditTarget.Block(key)
    }
}

/**
 * Fenêtre d'un carrousel [key] : ses cartes (ouvrir, monter, descendre, retirer, ajouter un graphique) et ses
 * options de défilement. Une carte ouverte depuis ici ramène au carrousel quand on la ferme.
 */
@Composable
fun CarouselFields(key: String) {
    val app = App.instance
    val state = LocalDashState.current
    val rawDash by app.config.rawDashboard.collectAsState()
    val source by app.config.sourceDashboard.collectAsState()
    val effectiveDash by app.config.dashboard.collectAsState()
    val original = remember(rawDash, key) { BlockOverrides.find(rawDash, key) } ?: return
    val base = BlockOverrides.find(source, key) ?: original
    val effective = BlockOverrides.find(effectiveDash, key) ?: base
    val originalCards = original["cards"] as? JsonArray ?: JsonArray(emptyList())
    val cards = ((base["cards"] as? JsonArray) ?: originalCards).mapNotNull { it as? JsonObject }
    val shownCards = ((effective["cards"] as? JsonArray) ?: originalCards).mapNotNull { it as? JsonObject }
    var picking by remember { mutableStateOf(false) }

    fun saveCards(list: List<JsonObject>) {
        // Clés d'origine des cartes conservées (`_key`) : leurs propres réglages restent attachés.
        val arr = JsonArray(list.map { JsonObject(it - "_key") })
        app.config.setBlockField(key, "cards", arr.takeIf { it != originalCards })
    }
    fun saveOption(field: String, value: JsonElement, default: JsonElement) =
        app.config.setBlockField(key, field, value.takeIf { it != (original[field] ?: default) })
    fun open(card: JsonObject) {
        val t = targetOf(card) ?: return
        state.openEditChild(EditTarget.Swipe(key), t)
    }

    // --- Cartes -------------------------------------------------------------------------------------------
    CarouselSection(stringResource(R.string.swipe_cards))
    Column(Modifier.fillMaxWidth().padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cards.forEachIndexed { i, c ->
            val shown = shownCards.getOrNull(i) ?: c
            val shape = RoundedCornerShape(14.dp)
            Row(
                Modifier.fillMaxWidth().clip(shape).background(RowBg, shape).border(1.dp, Color.White.copy(alpha = 0.08f), shape)
                    .clickable { open(c) }.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Position dans le carrousel, comme l'indicateur à points.
                Box(Modifier.size(26.dp).clip(CircleShape).background(Dash.colors.accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", style = TextStyle(fontSize = 13.sp, color = Dash.colors.accent, fontWeight = FontWeight.SemiBold))
                }
                HaIcon(if (c.s("type") == "mini_graph" || c.s("type") == "power_graph") "mdi:chart-line" else "mdi:card-outline", Modifier.padding(start = 12.dp), 22.dp, Color.White.copy(alpha = 0.8f))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(
                        shown.s("name") ?: c.s("type") ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Medium),
                    )
                    curveCount(shown)?.let { n ->
                        Text(pluralStringResource(R.plurals.swipe_curves, n, n), style = TextStyle(fontSize = 13.sp, color = Muted))
                    }
                }
                SmallIcon("mdi:chevron-up", enabled = i > 0) { saveCards(cards.toMutableList().apply { add(i - 1, removeAt(i)) }) }
                SmallIcon("mdi:chevron-down", enabled = i < cards.lastIndex) { saveCards(cards.toMutableList().apply { add(i + 1, removeAt(i)) }) }
                SmallIcon("mdi:delete-outline", enabled = cards.size > 1) { saveCards(cards.filterIndexed { j, _ -> j != i }) }
            }
        }
        AddButton(stringResource(R.string.swipe_add_card)) { picking = true }
        Text(stringResource(R.string.swipe_add_help), style = TextStyle(fontSize = 12.sp, color = Muted))
    }

    // --- Défilement ---------------------------------------------------------------------------------------
    CarouselSection(stringResource(R.string.swipe_scroll))
    Column(Modifier.fillMaxWidth().padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val auto = effective.flag("enable_auto_swipe", false)
        SwitchLine(stringResource(R.string.swipe_auto), auto) { saveOption("enable_auto_swipe", JsonPrimitive(it), JsonPrimitive(false)) }
        if (auto) {
            val seconds = (effective.i("auto_swipe_interval", 2000) / 1000f)
            var v by remember(seconds) { mutableFloatStateOf(seconds) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.swipe_interval), Modifier.weight(1f), style = TextStyle(fontSize = 15.sp, color = Color.White))
                Text(stringResource(R.string.swipe_seconds, v.roundToInt()), style = TextStyle(fontSize = 15.sp, color = Dash.colors.accent, fontWeight = FontWeight.SemiBold))
            }
            Slider(
                v, { v = it.roundToInt().toFloat() }, valueRange = 2f..30f, steps = 27,
                onValueChangeFinished = { saveOption("auto_swipe_interval", JsonPrimitive(v.roundToInt() * 1000), JsonPrimitive(2000)) },
                colors = SliderDefaults.colors(thumbColor = Dash.colors.accent, activeTrackColor = Dash.colors.accent),
            )
        }
        Text(stringResource(R.string.swipe_effect), Modifier.padding(top = 4.dp), style = TextStyle(fontSize = 15.sp, color = Color.White))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            val current = effective.s("swipe_effect") ?: "slide"
            listOf("slide" to R.string.swipe_effect_slide, "fade" to R.string.swipe_effect_fade, "instant" to R.string.swipe_effect_instant).forEach { (id, label) ->
                val sel = current == id
                val shape = RoundedCornerShape(16.dp)
                Text(
                    stringResource(label),
                    Modifier.clip(shape).background(if (sel) Dash.colors.accent else Color.Transparent, shape)
                        .border(1.dp, if (sel) Dash.colors.accent else Color.White.copy(alpha = 0.25f), shape)
                        .clickable { saveOption("swipe_effect", JsonPrimitive(id), JsonPrimitive("slide")) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    style = TextStyle(fontSize = 14.sp, color = if (sel) Color.Black else Color.White),
                )
            }
        }
        SwitchLine(stringResource(R.string.swipe_dots), effective.flag("show_pagination", true)) {
            saveOption("show_pagination", JsonPrimitive(it), JsonPrimitive(true))
        }
    }

    // --- Retour au carrousel d'origine ---------------------------------------------------------------------
    val set = app.config.rawBlocks.collectAsState().value[key]?.get("set") as? JsonObject
    if (set != null && set.isNotEmpty()) Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.CenterEnd) {
        TextLink(stringResource(R.string.swipe_reset)) {
            listOf("cards", "enable_auto_swipe", "auto_swipe_interval", "swipe_effect", "show_pagination").forEach { app.config.setBlockField(key, it, null) }
        }
    }

    // Nouvelle carte : on choisit sa valeur principale ; le reste (durée, agrégation, bande jour/nuit…) reprend
    // le premier graphique du carrousel, puis la fenêtre de la nouvelle carte s'ouvre pour y ajouter les courbes.
    if (picking) EntityPicker("sensor", onDismiss = { picking = false }) { id ->
        picking = false
        val template = cards.firstOrNull { it.s("type") == "mini_graph" && it["entities"] is JsonArray }
        val friendly = app.ha.states.value[id]?.name ?: id
        val header = JsonObject(mapOf(
            "entity" to JsonPrimitive(id), "name" to JsonPrimitive(friendly),
            "show_graph" to JsonPrimitive(false), "show_legend" to JsonPrimitive(false),
        ))
        val band = (template?.get("entities") as? JsonArray)?.mapNotNull { it as? JsonObject }?.firstOrNull { it.s("y_axis") == "secondary" }
        val newCard = JsonObject(
            (template?.let { it - "name" - "entities" - "_key" } ?: mapOf(
                "type" to JsonPrimitive("mini_graph"), "hours_to_show" to JsonPrimitive(24), "line_width" to JsonPrimitive(2),
                "show" to JsonObject(mapOf("fill" to JsonPrimitive(true), "icon" to JsonPrimitive(false), "labels" to JsonPrimitive(false))),
            )) + mapOf("name" to JsonPrimitive(friendly), "entities" to JsonArray(listOfNotNull(header, band))),
        )
        saveCards(cards + newCard)
        open(newCard)
    }
}

@Composable
private fun CarouselSection(text: String) {
    Text(text, Modifier.padding(top = 10.dp, bottom = 2.dp), style = TextStyle(fontSize = 13.sp, color = Dash.colors.accent, fontWeight = FontWeight.SemiBold))
}

@Composable
private fun SwitchLine(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = TextStyle(fontSize = 15.sp, color = Color.White))
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Dash.colors.accent, checkedThumbColor = Color.Black))
    }
}

@Composable
private fun SmallIcon(icon: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { HaIcon(icon, size = 22.dp, tint = if (enabled) Muted else Color.White.copy(alpha = 0.15f)) }
}
