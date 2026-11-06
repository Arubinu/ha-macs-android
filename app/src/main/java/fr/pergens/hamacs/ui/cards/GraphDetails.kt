package fr.pergens.hamacs.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.pergens.hamacs.R
import fr.pergens.hamacs.ha.Fmt
import fr.pergens.hamacs.ha.history
import fr.pergens.hamacs.ui.LocalDashState
import fr.pergens.hamacs.ui.Sheet
import fr.pergens.hamacs.ui.components.HaIcon
import fr.pergens.hamacs.ui.components.tapHold
import fr.pergens.hamacs.ui.theme.Dash
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Courbe d'un graphique à plusieurs entités, pour la fenêtre de détail. */
internal class DetailLine(val id: String, val name: String, val color: Color)

/** Changement de valeur d'une entité : instant, ancienne et nouvelle valeur. */
private class Change(val at: Long, val from: Double, val to: Double)

private const val DETAIL_HOURS = 24 * 7
private val Muted = Color(0xFFA3A3AD)
private val Down = Color(0xFF4CD964)
private val Up = Color(0xFFFF6B6B)

/**
 * Détail d'un graphique à plusieurs courbes (prix des stations…) : chaque courbe avec sa couleur, sa valeur actuelle,
 * l'écart avec la meilleure et ses derniers changements sur 7 jours. Classement : du plus bas au plus haut pour
 * `state_from: min`, l'inverse pour `max`, sinon l'ordre de la carte.
 */
@Composable
internal fun GraphDetailsSheet(title: String, lines: List<DetailLine>, stateFrom: String?, decimals: Int, onDismiss: () -> Unit) {
    val state = LocalDashState.current
    val states by state.ha.states.collectAsState()
    val ids = lines.map { it.id }.distinct()
    val changes by produceState<Map<String, List<Change>>?>(null, ids) {
        value = runCatching {
            state.ha.history(ids, DETAIL_HOURS).mapValues { (_, spans) ->
                val nums = spans.sortedBy { it.start }.mapNotNull { s -> s.state.toDoubleOrNull()?.let { s.start to it } }
                nums.zipWithNext().filter { (a, b) -> b.second != a.second }.map { (a, b) -> Change(b.first, a.second, b.second) }.reversed()
            }
        }.getOrDefault(emptyMap())
    }
    val values = lines.associateWith { states[it.id]?.takeIf { e -> !e.isUnavailable }?.numeric }
    val sorted = when (stateFrom) {
        "min" -> lines.sortedWith(compareBy(nullsLast()) { values[it] })
        "max" -> lines.sortedWith(compareByDescending<DetailLine, Double?>(nullsFirst()) { values[it] })
        else -> lines
    }
    val best = if (stateFrom != null) sorted.firstOrNull()?.let { values[it] } else null
    // Une baisse est une bonne nouvelle quand on cherche le plus bas (prix), une hausse pour le plus haut.
    val accent = Dash.colors.accent
    fun deltaColor(d: Double) = when {
        stateFrom == "min" -> if (d < 0) Down else Up
        stateFrom == "max" -> if (d > 0) Down else Up
        else -> accent
    }
    val unit = lines.firstNotNullOfOrNull { states[it.id]?.unit }.orEmpty()
    fun fmt(v: Double) = Fmt.number(v, decimals)
    fun signed(d: Double) = (if (d > 0) "+" else "−") + fmt(kotlin.math.abs(d))

    Sheet(onDismiss, 0.5f) {
        Box(
            Modifier.align(Alignment.TopEnd).padding(top = 0.dp).size(44.dp).clip(CircleShape).tapHold(onDismiss),
            contentAlignment = Alignment.Center,
        ) { HaIcon("mdi:close", size = 26.dp, tint = Color(255, 255, 255, 200)) }
        Column(Modifier.fillMaxWidth()) {
            Text(title, Modifier.padding(end = 52.dp, bottom = 4.dp), style = TextStyle(fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Medium))
            Text(stringResource(R.string.graph_details_help), Modifier.padding(bottom = 14.dp), style = TextStyle(fontSize = 13.sp, color = Muted))
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                sorted.forEach { line ->
                    val v = values[line]
                    val isBest = best != null && v == best
                    val shape = RoundedCornerShape(14.dp)
                    Column(
                        Modifier.fillMaxWidth().clip(shape)
                            .background(if (isBest) Dash.colors.accent.copy(alpha = 0.12f) else Color(0xFF2A2A30), shape)
                            .border(1.dp, if (isBest) Dash.colors.accent.copy(alpha = 0.7f) else Color.Transparent, shape)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(line.color))
                            Text(
                                line.name, Modifier.padding(start = 10.dp).weight(1f),
                                style = TextStyle(fontSize = 17.sp, color = Color.White, fontWeight = FontWeight.Medium),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            if (isBest) Text(
                                stringResource(if (stateFrom == "max") R.string.graph_details_highest else R.string.graph_details_lowest),
                                Modifier.padding(end = 10.dp).clip(RoundedCornerShape(8.dp)).background(Dash.colors.accent).padding(horizontal = 8.dp, vertical = 2.dp),
                                style = TextStyle(fontSize = 12.sp, color = Color.Black, fontWeight = FontWeight.Medium),
                            )
                            else if (best != null && v != null) Text(
                                signed(v - best), Modifier.padding(end = 10.dp),
                                style = TextStyle(fontSize = 13.sp, color = Muted),
                            )
                            Text(
                                v?.let { fmt(it) + if (unit.isNotEmpty()) " $unit" else "" } ?: stringResource(R.string.graph_details_unavailable),
                                style = TextStyle(fontSize = 20.sp, color = if (v != null) Color.White else Muted, fontWeight = FontWeight.Light),
                            )
                        }
                        // Derniers changements (3 au plus) : quand, ancienne → nouvelle valeur, écart coloré.
                        val list = changes?.get(line.id)
                        when {
                            list == null -> Unit
                            list.isEmpty() -> Text(stringResource(R.string.graph_details_no_change), Modifier.padding(start = 22.dp), style = TextStyle(fontSize = 13.sp, color = Muted))
                            else -> list.take(3).forEach { c ->
                                Row(Modifier.padding(start = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(whenLabel(c.at), Modifier.width(130.dp), style = TextStyle(fontSize = 13.sp, color = Muted))
                                    Text("${fmt(c.from)} → ${fmt(c.to)}", Modifier.weight(1f), style = TextStyle(fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f)))
                                    Text(signed(c.to - c.from), style = TextStyle(fontSize = 13.sp, color = deltaColor(c.to - c.from), fontWeight = FontWeight.Medium))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** « Aujourd'hui 08:12 », « Hier 17:40 », sinon « 03/11 09:05 ». */
@Composable
private fun whenLabel(at: Long): String {
    val t = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    val time = DateTimeFormatter.ofPattern("HH:mm").format(t)
    val today = LocalDate.now()
    return when (t.toLocalDate()) {
        today -> stringResource(R.string.graph_details_today, time)
        today.minusDays(1) -> stringResource(R.string.graph_details_yesterday, time)
        else -> DateTimeFormatter.ofPattern("dd/MM HH:mm").format(t)
    }
}
