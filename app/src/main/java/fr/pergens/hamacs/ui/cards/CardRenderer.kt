package fr.pergens.hamacs.ui.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import fr.pergens.hamacs.R
import fr.pergens.hamacs.config.Card
import fr.pergens.hamacs.config.b
import fr.pergens.hamacs.config.d
import fr.pergens.hamacs.config.i
import fr.pergens.hamacs.config.objs
import fr.pergens.hamacs.config.s
import fr.pergens.hamacs.ui.components.CenterRule
import fr.pergens.hamacs.ui.components.SectionTitle
import fr.pergens.hamacs.ui.theme.Dash

/**
 * Point d'entrée modulaire : chaque objet `{"type": …}` de dashboard.json est rendu par la
 * fonction correspondante. Ajouter un type de carte = ajouter une ligne ici.
 */
@Composable
fun RenderCard(card: Card, modifier: Modifier = Modifier) {
    // Bloc masqué (assistant de configuration : élément absent de la maison).
    if (card.b("hidden")) return
    val m = card.d("margin_top")?.let { modifier.padding(top = it.dp) } ?: modifier
    when (card.s("type")) {
        "vertical" -> VerticalStack(card, m)
        "horizontal" -> HorizontalStack(card, m)
        "spacer" -> Spacer(m.height(card.d("height", 12.0).dp))
        "title" -> SectionTitle(card.s("text", ""), m)
        "rule" -> CenterRule(m)
        "switcher" -> SwitcherCard(card, m)
        "room" -> RoomCard(card, m)
        "room_grid" -> RoomGrid(card, m)
        "alarm" -> AlarmCard(card, m)
        "clock_weather" -> ClockWeatherCard(card, m)
        "macs" -> MacsCard(card, m)
        "camera" -> CameraCard(card, m)
        "camera_detections" -> CameraDetectionsCard(card, m)
        "members" -> MembersCard(card, m)
        "power_graph" -> PowerGraphCard(card, m)
        "mini_graph" -> MiniGraphCard(card, m)
        "swipe" -> SwipeCard(card, m)
        "media_player" -> MediaPlayerCard(card, m)
        "webview", "windy" -> WebViewCard(card, m)
        "calendar" -> CalendarCard(card, m)
        "presence_history" -> PresenceHistoryCard(card, m)
        "remote" -> RemoteCard(card, m)
        "clock" -> ClockCard(card, m)
        "ticker", "alert_ticker" -> fr.pergens.hamacs.ui.cards.alerts.AlertTickerCard(card, m)
        "button" -> RoundButton(card, m)
        else -> Box(m.fillMaxWidth().padding(8.dp)) {
            Text(stringResource(R.string.unknown_card, card.s("type") ?: "?"), color = Color(0xFFFF8080), fontSize = 12.sp)
        }
    }
}

@Composable
private fun VerticalStack(card: Card, modifier: Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(card.d("gap")?.dp ?: Dash.shapes.gutter)) {
        card.objs("cards").forEach { RenderCard(it) }
    }
}

@Composable
private fun HorizontalStack(card: Card, modifier: Modifier) {
    val cards = card.objs("cards")
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(card.d("gap")?.dp ?: Dash.shapes.gutter)) {
        cards.forEach { c -> RenderCard(c, Modifier.weight(c.d("weight", 1.0).toFloat())) }
    }
}

/** Utilitaire partagé : découpe une liste en lignes de [columns] éléments. */
internal fun <T> List<T>.rows(columns: Int): List<List<T>> = chunked(columns.coerceAtLeast(1))

internal fun Card.columns(default: Int) = i("columns", default)
