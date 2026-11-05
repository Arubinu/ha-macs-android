package fr.arubinu.hadash.ui.cards

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.res.stringResource
import fr.arubinu.hadash.App
import fr.arubinu.hadash.R
import fr.arubinu.hadash.config.Card
import fr.arubinu.hadash.config.i
import fr.arubinu.hadash.config.objs
import fr.arubinu.hadash.config.s
import fr.arubinu.hadash.config.strs
import fr.arubinu.hadash.ha.CalendarEvent
import fr.arubinu.hadash.ha.StateSpan
import fr.arubinu.hadash.ha.calendarEvents
import fr.arubinu.hadash.ha.history
import fr.arubinu.hadash.ui.LocalDashState
import fr.arubinu.hadash.ui.components.DashCard
import fr.arubinu.hadash.ui.components.HaIcon
import fr.arubinu.hadash.ui.components.SectionTitle
import fr.arubinu.hadash.ui.components.editOutline
import fr.arubinu.hadash.ui.components.tapHold
import fr.arubinu.hadash.ui.entity
import fr.arubinu.hadash.ui.theme.Dash
import fr.arubinu.hadash.ui.theme.configColor
import fr.arubinu.hadash.ui.theme.parseCssColor
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Vue « Membres » : avatars sélectionnables (input_select), bandeau d'infos du membre choisi,
 * historique de présence 24 h et événements à venir.
 */
@Composable
fun MembersCard(card: Card, modifier: Modifier) {
    val state = LocalDashState.current
    // Membre affiché : choisi sur la tablette (mémorisé dans l'app), le premier par défaut. Le code d'accès
    // éventuel du sélecteur est porté par la clé du bloc.
    val guardKey = fr.arubinu.hadash.config.BlockOverrides.MEMBERS_KEY
    val prefs = remember { App.instance.getSharedPreferences("members", android.content.Context.MODE_PRIVATE) }
    var selected by remember { androidx.compose.runtime.mutableStateOf(prefs.getString("selected", null)) }
    val members = card.objs("members")
    val avatarUrl = card.s("avatar_url") ?: "/local/images/avatars/mini/{member}_{state}.jpg"
    // Icône de la zone où se trouve chaque membre : celle des zones de Home Assistant (l'état d'une personne
    // est le nom de sa zone).
    val allStates = state.ha.states.collectAsState()
    val zones by remember {
        androidx.compose.runtime.derivedStateOf {
            val st = allStates.value
            JsonObject(st.values.filter { it.domain == "zone" }.associate { it.name to JsonPrimitive(it.attrString("icon") ?: "mdi:map-marker") })
        }
    }
    val member = members.firstOrNull { it.s("id") == selected } ?: members.firstOrNull()

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dash.shapes.gutter)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dash.shapes.gutter)) {
            members.forEach { m ->
                val id = m.s("id") ?: return@forEach
                val active = id == member?.s("id")
                val person = entity(m.s("person") ?: "person.$id")
                val pulse = if (active) {
                    rememberInfiniteTransition(label = "pulse").animateFloat(
                        1f, 0.8f, infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Reverse), label = "p",
                    ).value
                } else 1f
                DashCard(
                    Modifier.weight(1f).height(80.dp).then(Modifier.editOutline(state.editMode, androidx.compose.foundation.shape.RoundedCornerShape(Dash.shapes.cardRadius))).tapHold({
                        // Mode édition : réglages du membre (personne, capteurs) et code d'accès du sélecteur.
                        if (state.editMode) { state.editTarget = fr.arubinu.hadash.ui.EditTarget.Member("member:$id", guardKey); return@tapHold }
                        if (id == member?.s("id")) return@tapHold
                        state.guard(guardKey) {
                            selected = id
                            prefs.edit().putString("selected", id).apply()
                        }
                    }),
                ) {
                    AsyncImage(
                        // `"picture": "person"` : photo de la personne dans Home Assistant (membres créés par l'assistant).
                        model = if (m.s("picture") == "person") state.ha.resolveUrl(person?.attrString("entity_picture"))
                            else state.ha.resolveUrl(avatarUrl.replace("{member}", id).replace("{state}", if (active) "active" else "inactive")),
                        contentDescription = id,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.align(Alignment.Center).size(80.dp).alpha(pulse),
                    )
                    val loc = person?.state
                    val zoneIcon = when {
                        loc == "home" -> "mdi:home"
                        loc == null -> "mdi:progress-question"
                        else -> zones[loc]?.jsonPrimitive?.content ?: "mdi:home-off"
                    }
                    Box(
                        Modifier.align(Alignment.Center).offset(x = 20.dp, y = 23.dp).size(24.dp).background(Color.Black, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { HaIcon(zoneIcon, size = 16.dp) }
                }
            }
        }
        if (member != null) {
            val ids = members.mapNotNull { it.s("id") }
            AnimatedContent(
                targetState = member,
                contentKey = { it.s("id") },
                transitionSpec = {
                    val dir = if (ids.indexOf(targetState.s("id")) >= ids.indexOf(initialState.s("id"))) 1 else -1
                    val spec = tween<androidx.compose.ui.unit.IntOffset>(320, easing = FastOutSlowInEasing)
                    (slideInHorizontally(spec) { it / 5 * dir } + fadeIn(tween(320))) togetherWith
                        (slideOutHorizontally(spec) { -it / 5 * dir } + fadeOut(tween(200)))
                },
                label = "member",
            ) { m ->
                Column(verticalArrangement = Arrangement.spacedBy(Dash.shapes.gutter)) { MemberDetails(card, m) }
            }
        }
    }
}

@Composable
private fun MemberDetails(card: Card, m: Card) {
    val state = LocalDashState.current
    // Présence : la personne elle-même (état « home »), sauf capteur `home_sensor` explicite.
    val homeId = m.s("home_sensor") ?: m.s("person") ?: m.s("id")?.let { "person.$it" }
    val home = entity(homeId)
    val steps = entity(m.s("steps_sensor"))
    val battery = entity(m.s("battery_sensor"))
    val charger = entity(m.s("charger_sensor"))
    // Animations embarquées dans l'app.
    val icons = "file:///android_asset/images/icons/"
    // Heure d'arrivée / de départ : dernier passage maison ↔ dehors dans l'historique (l'état d'une personne
    // change aussi en passant d'une zone à une autre, hors de la maison).
    var since by remember(homeId) { mutableStateOf<Long?>(null) }
    val atHome = home?.isActive
    LaunchedEffect(homeId, atHome) {
        if (homeId == null || atHome == null) return@LaunchedEffect
        since = runCatching {
            val spans = state.ha.history(listOf(homeId), 72)[homeId].orEmpty().filter { it.state !in setOf("unknown", "unavailable") }
            fun isHome(s: String) = s == "home" || s == "on"
            spans.indices.lastOrNull { i -> i > 0 && isHome(spans[i].state) == atHome && isHome(spans[i - 1].state) != atHome }?.let { spans[it].start }
        }.getOrNull()
    }
    val time = (since ?: home?.lastChanged)?.let {
        DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
    } ?: "—"
    val charging = charger != null && charger.state.lowercase() !in setOf("none", "not charging", "unknown", "unavailable", "discharging")

    DashCard(Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
        Row(Modifier.fillMaxWidth().align(Alignment.Center).padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            InfoGif(
                state.ha.resolveUrl(icons + "earth.gif"), "mdi:earth",
                stringResource(if (home?.isActive == false) R.string.member_left else R.string.member_arrived, time),
            )
            InfoGif(state.ha.resolveUrl(icons + "running.gif"), "mdi:run", stringResource(R.string.member_steps, steps?.numeric?.toInt()?.toString() ?: "—"))
            InfoGif(
                state.ha.resolveUrl(icons + (if (charging) "battery-charged.gif" else "half-battery.gif")),
                if (charging) "mdi:battery-charging" else "mdi:battery-50",
                "${battery?.state ?: "—"}%",
            )
        }
    }
    SectionTitle(stringResource(R.string.presence_title))
    PresenceHistoryCard(
        buildJsonObject {
            put("entity", JsonPrimitive(homeId ?: ""))
            put("hours", JsonPrimitive(24))
        },
        Modifier,
    )
    SectionTitle(stringResource(R.string.events_title))
    CalendarCard(buildJsonObject { put("entities", kotlinx.serialization.json.JsonArray(m.strs("calendars").map { JsonPrimitive(it) })) }, Modifier)
}

@Composable
private fun InfoGif(url: String?, fallbackIcon: String, text: String) {
    // Animation absente (autre installation) : icône à la place.
    var failed by androidx.compose.runtime.remember(url) { androidx.compose.runtime.mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (failed) fr.arubinu.hadash.ui.components.HaIcon(fallbackIcon, size = 26.dp, tint = Dash.colors.accent)
        else AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(28.dp), onError = { failed = true })
        Text(text, Modifier.padding(start = 10.dp), style = TextStyle(fontSize = 15.sp, color = Color.White), maxLines = 1)
    }
}

/** Barre d'historique en segments (horizontal-waterfall-history-card). */
@Composable
fun PresenceHistoryCard(card: Card, modifier: Modifier) {
    val state = LocalDashState.current
    val id = card.s("entity") ?: return
    val hours = card.i("hours", 24)
    val segments = card.i("segments", 48)
    val on = configColor(card.s("color_on"), Dash.colors.accent.copy(alpha = 0.8f))
    val off = configColor(card.s("color_off"), Dash.colors.accent.copy(alpha = 0.4f))
    var spans by remember(id) { mutableStateOf<List<StateSpan>>(emptyList()) }
    val current = entity(id)
    LaunchedEffect(id, current?.state) {
        runCatching { spans = state.ha.history(listOf(id), hours)[id].orEmpty() }
    }
    DashCard(modifier.fillMaxWidth(), padding = 12.dp) {
        Column {
            Canvas(Modifier.fillMaxWidth().height(30.dp)) {
                val now = System.currentTimeMillis()
                val start = now - hours * 3_600_000L
                val gap = 2.dp.toPx()
                val w = (size.width - gap * (segments - 1)) / segments
                for (i in 0 until segments) {
                    val t = start + (i + 0.5) * (now - start) / segments
                    val s = spans.lastOrNull { it.start <= t }?.state ?: spans.firstOrNull()?.state
                    val color = when (s) {
                        "on", "home" -> on
                        null, "unknown", "unavailable" -> Color(255, 255, 255, 20)
                        else -> off
                    }
                    drawRoundRect(color, Offset(i * (w + gap), 0f), Size(w, size.height), CornerRadius(2.dp.toPx()))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("${hours}h", Modifier.weight(1f), style = TextStyle(fontSize = 11.sp, color = Color.White))
                Text(stringResource(R.string.presence_now), style = TextStyle(fontSize = 11.sp, color = Color.White))
            }
        }
    }
}

/** Liste d'événements (calendar-card-pro) : date à gauche, titre, horaire et compte à rebours. */
@Composable
fun CalendarCard(card: Card, modifier: Modifier) {
    val state = LocalDashState.current
    val ids = card.strs("entities")
    val days = card.i("days", 7)
    var events by remember(ids) { mutableStateOf<List<CalendarEvent>>(emptyList()) }
    LaunchedEffect(ids) {
        while (true) {
            runCatching { events = state.ha.calendarEvents(App.instance.http, ids, days) }
            delay(15 * 60_000L)
        }
    }
    val fr = Locale.getDefault()
    val today = LocalDate.now()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (events.isEmpty()) {
            Text(stringResource(R.string.events_none), Modifier.fillMaxWidth(), style = TextStyle(fontSize = 14.sp, color = Color(255, 255, 255, 140), textAlign = TextAlign.Center))
        }
        events.groupBy { it.start.toLocalDate() }.forEach { (date, evs) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.width(54.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(date.format(DateTimeFormatter.ofPattern("EEE", fr)).trimEnd('.').replaceFirstChar { it.uppercase() }, style = TextStyle(fontSize = 15.sp, color = Color.White))
                    Text(date.dayOfMonth.toString(), style = TextStyle(fontSize = 26.sp, color = Color.White, fontWeight = FontWeight.Bold, lineHeight = 28.sp))
                    Text(date.format(DateTimeFormatter.ofPattern("MMM", fr)).trimEnd('.').uppercase(), style = TextStyle(fontSize = 14.sp, color = Color.White))
                }
                Box(Modifier.padding(horizontal = 10.dp).width(3.dp).height(80.dp).clip(CircleShape).background(configColor(card.s("line_color"), Dash.colors.accent)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    evs.forEach { e ->
                        Row(verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text(e.summary, style = TextStyle(fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Medium))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    HaIcon("mdi:clock-outline", size = 14.dp, tint = Color(255, 255, 255, 160))
                                    Text(
                                        if (e.allDay) stringResource(R.string.event_all_day)
                                        else e.start.format(DateTimeFormatter.ofPattern("HH:mm")) + " – " + e.end.format(DateTimeFormatter.ofPattern("HH:mm")),
                                        Modifier.padding(start = 4.dp),
                                        style = TextStyle(fontSize = 14.sp, color = Color(255, 255, 255, 160)),
                                    )
                                }
                            }
                            val d = ChronoUnit.DAYS.between(today, date)
                            Text(
                                when (d) {
                                    0L -> stringResource(R.string.event_today)
                                    1L -> stringResource(R.string.event_tomorrow)
                                    else -> stringResource(R.string.event_in_days, d.toInt())
                                },
                                style = TextStyle(fontSize = 14.sp, color = Color(255, 255, 255, 160)),
                            )
                        }
                    }
                }
            }
        }
    }
}
