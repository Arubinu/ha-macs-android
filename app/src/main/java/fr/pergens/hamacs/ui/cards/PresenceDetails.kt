package fr.pergens.hamacs.ui.cards

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.pergens.hamacs.R
import fr.pergens.hamacs.ha.StateSpan
import fr.pergens.hamacs.ui.Sheet
import fr.pergens.hamacs.ui.components.HaIcon
import fr.pergens.hamacs.ui.components.tapHold
import fr.pergens.hamacs.ui.theme.Dash
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Muted = Color(0xFFA3A3AD)

/** Période continue à la maison ([home]) ou ailleurs ([zone] : nom de la zone, null si simplement absent). */
internal data class Period(val start: Long, val end: Long, val home: Boolean, val zone: String?)

private fun isHome(s: String) = s == "home" || s == "on"

/** Périodes des [hours] dernières heures, les états inconnus prolongeant la période précédente. */
internal fun periods(spans: List<StateSpan>, hours: Int, now: Long): List<Period> {
    val from = now - hours * 3_600_000L
    val known = spans.sortedBy { it.start }.filter { it.state !in setOf("unknown", "unavailable") }
    val out = mutableListOf<Period>()
    known.forEachIndexed { i, s ->
        val start = maxOf(s.start, from)
        val end = known.getOrNull(i + 1)?.start ?: now
        if (end <= from) return@forEachIndexed
        val home = isHome(s.state)
        val zone = s.state.takeIf { !home && it != "not_home" && it != "off" }
        val last = out.lastOrNull()
        if (last != null && last.home == home && last.zone == zone) out[out.lastIndex] = Period(last.start, end, home, zone)
        else out += Period(start, end, home, zone)
    }
    return out
}

internal fun duration(ms: Long): String {
    val min = (ms / 60_000L).coerceAtLeast(1)
    return if (min >= 60) "%d h %02d".format(min / 60, min % 60) else "$min min"
}

/**
 * Détail de la barre « Présence au domicile » : arrivées et départs des dernières [hours] heures, de la plus récente
 * à la plus ancienne, avec la durée de chaque période et le temps passé à la maison.
 */
@Composable
internal fun PresenceDetailsSheet(title: String, spans: List<StateSpan>, hours: Int, onColor: Color, offColor: Color, onDismiss: () -> Unit) {
    val now = System.currentTimeMillis()
    val list = periods(spans, hours, now)
    val hm = DateTimeFormatter.ofPattern("HH:mm")
    fun time(t: Long) = hm.format(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()))
    Sheet(onDismiss, 0.45f) {
        Box(
            Modifier.align(Alignment.TopEnd).size(44.dp).clip(CircleShape).tapHold(onDismiss),
            contentAlignment = Alignment.Center,
        ) { HaIcon("mdi:close", size = 26.dp, tint = Color(255, 255, 255, 200)) }
        Column(Modifier.fillMaxWidth()) {
            Text(title, Modifier.padding(end = 52.dp, bottom = 4.dp), style = TextStyle(fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Medium))
            val atHome = list.filter { it.home }.sumOf { it.end - it.start }
            Text(
                if (list.isEmpty()) stringResource(R.string.presence_details_empty)
                else stringResource(R.string.presence_details_total, duration(atHome), hours),
                Modifier.padding(bottom = 14.dp), style = TextStyle(fontSize = 13.sp, color = Muted),
            )
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                list.asReversed().forEachIndexed { i, p ->
                    val shape = RoundedCornerShape(14.dp)
                    Row(
                        Modifier.fillMaxWidth().clip(shape).background(Color(0xFF2A2A30), shape).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(if (p.home) onColor else offColor), contentAlignment = Alignment.Center) {
                            HaIcon(if (p.home) "mdi:home" else "mdi:home-export-outline", size = 20.dp, tint = Color.Black)
                        }
                        Column(Modifier.padding(start = 14.dp).weight(1f)) {
                            Text(
                                when {
                                    p.home -> stringResource(R.string.presence_details_home)
                                    p.zone != null -> p.zone.replaceFirstChar { it.uppercase() }
                                    else -> stringResource(R.string.presence_details_away)
                                },
                                style = TextStyle(fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Medium),
                            )
                            // Début coupé par la fenêtre de 24 h : « depuis » au lieu d'une heure d'arrivée inexacte.
                            val startText = if (p.start <= now - hours * 3_600_000L + 60_000L) "…" else time(p.start)
                            val endText = if (i == 0) stringResource(R.string.presence_now).lowercase() else time(p.end)
                            Text("$startText → $endText", style = TextStyle(fontSize = 14.sp, color = Muted))
                        }
                        Text(duration(p.end - p.start), Modifier.width(80.dp), style = TextStyle(fontSize = 15.sp, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.End))
                    }
                }
            }
        }
    }
}
