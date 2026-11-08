package fr.pergens.hamacs.ui.cards

import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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
import fr.pergens.hamacs.App
import fr.pergens.hamacs.R
import fr.pergens.hamacs.config.d
import fr.pergens.hamacs.config.Card
import fr.pergens.hamacs.config.i
import fr.pergens.hamacs.config.objs
import fr.pergens.hamacs.config.s
import fr.pergens.hamacs.config.strs
import fr.pergens.hamacs.ha.CalendarEvent
import fr.pergens.hamacs.ha.StateSpan
import fr.pergens.hamacs.ha.calendarEvents
import fr.pergens.hamacs.ha.history
import fr.pergens.hamacs.ui.LocalDashState
import fr.pergens.hamacs.ui.components.BottomFadeScroll
import fr.pergens.hamacs.ui.components.DashCard
import fr.pergens.hamacs.ui.components.HaIcon
import fr.pergens.hamacs.ui.components.SectionTitle
import fr.pergens.hamacs.ui.components.editOutline
import fr.pergens.hamacs.ui.components.tapHold
import fr.pergens.hamacs.ui.entity
import fr.pergens.hamacs.ui.theme.Dash
import fr.pergens.hamacs.ui.theme.configColor
import fr.pergens.hamacs.ui.theme.parseCssColor
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
    val guardKey = fr.pergens.hamacs.config.BlockOverrides.MEMBERS_KEY
    val prefs = remember { App.instance.getSharedPreferences("members", android.content.Context.MODE_PRIVATE) }
    // Membre favori (mode édition) : affiché au démarrage et à chaque retour sur l'écran d'accueil. Sans favori, un
    // membre protégé par un code n'y reste pas affiché : il faut le redemander (code compris).
    val favorite = App.instance.config.blocks.collectAsState().value[guardKey]?.s("favorite")
    fun locked(id: String?) = id != null && (state.isProtected(guardKey, false) || state.isProtected("member:$id", false))
    fun reset(current: String?): String? = (favorite ?: current?.takeUnless { locked(it) }).also {
        prefs.edit().apply { if (it != null) putString("selected", it) else remove("selected") }.apply()
    }
    var selected by remember {
        val saved = prefs.getString("selected", null)
        val start = if (state.memberFavoriteApplied) saved else reset(saved)
        state.memberFavoriteApplied = true
        androidx.compose.runtime.mutableStateOf(start)
    }
    androidx.compose.runtime.SideEffect {
        state.onHome["members"] = { selected = reset(selected) }
    }
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
    // Aucun membre choisi : le premier qui n'est pas protégé (aucun si tous le sont).
    val member = members.firstOrNull { it.s("id") == selected } ?: members.firstOrNull { !locked(it.s("id")) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dash.shapes.gutter)) {
        // Avatars : autant que la largeur le permet sans se chevaucher ; au-delà, flèches et défilement par page.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = Dash.shapes.gutter
        val avatar = 80.dp
        val arrowW = 36.dp
        val fitAll = ((maxWidth + gap) / (avatar + gap)).toInt().coerceAtLeast(1)
        val paged = members.size > fitAll
        val perPage = if (!paged) members.size.coerceAtLeast(1)
            else ((maxWidth - (arrowW + gap) * 2 + gap) / (avatar + gap)).toInt().coerceIn(1, members.size)
        val selIndex = members.indexOfFirst { it.s("id") == member?.s("id") }.coerceAtLeast(0)
        var first by remember(perPage, members.size) {
            androidx.compose.runtime.mutableIntStateOf((selIndex - selIndex % perPage).coerceIn(0, (members.size - perPage).coerceAtLeast(0)))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            if (paged) MembersArrow("mdi:chevron-left", first > 0) { first = (first - perPage).coerceAtLeast(0) }
            AnimatedContent(
                targetState = first, modifier = Modifier.weight(1f),
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    val spec = tween<androidx.compose.ui.unit.IntOffset>(280, easing = FastOutSlowInEasing)
                    (slideInHorizontally(spec) { it / 3 * dir } + fadeIn(tween(280))) togetherWith
                        (slideOutHorizontally(spec) { -it / 3 * dir } + fadeOut(tween(180)))
                },
                label = "members-page",
            ) { start ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            members.subList(start, (start + perPage).coerceAtMost(members.size)).forEach { m ->
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
                        if (state.editMode) { state.editTarget = fr.pergens.hamacs.ui.EditTarget.Member("member:$id", guardKey); return@tapHold }
                        if (id == member?.s("id")) return@tapHold
                        // Code global (tous les membres) s'il est activé, sinon code propre au membre choisi (à défaut,
                        // le code d'accès).
                        val all = state.isProtected(guardKey, false)
                        val own = if (all) null else fr.pergens.hamacs.App.instance.config.memberCode(id)
                        state.guard(if (all) guardKey else "member:$id", code = own,
                            title = own?.let { fr.pergens.hamacs.App.instance.getString(fr.pergens.hamacs.R.string.member_code_of, person?.name ?: id) }) {
                            selected = id
                            prefs.edit().putString("selected", id).apply()
                        }
                    }),
                ) {
                    // Avatar créé sur la tablette, sinon photo de la personne (`"picture": "person"`, membres créés par
                    // l'assistant), sinon paire d'images `_active` / `_inactive` de `avatar_url`.
                    fr.pergens.hamacs.ui.avatar.MemberAvatar(
                        memberId = id, active = active,
                        personPicture = if (m.s("picture") == "person") state.ha.resolveUrl(person?.attrString("entity_picture")) else null,
                        fallbackUrl = state.ha.resolveUrl(avatarUrl.replace("{member}", id).replace("{state}", if (active) "active" else "inactive")),
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
            // Dernière page incomplète : cases vides pour garder la même taille d'avatar.
            repeat((start + perPage - members.size).coerceAtLeast(0)) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
        }
            }
            if (paged) MembersArrow("mdi:chevron-right", first + perPage < members.size) {
                first = (first + perPage).coerceAtMost(members.size - perPage)
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
        } else if (members.isNotEmpty()) {
            Text(
                stringResource(R.string.members_pick), Modifier.fillMaxWidth().padding(top = 24.dp),
                style = TextStyle(fontSize = 15.sp, color = Color.White.copy(alpha = 0.6f), textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            )
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
            InfoItem(stringResource(if (home?.isActive == false) R.string.member_left else R.string.member_arrived, time)) {
                fr.pergens.hamacs.ui.components.GlobeAnimation()
            }
            InfoItem(stringResource(R.string.member_steps, steps?.numeric?.toInt()?.toString() ?: "—")) {
                fr.pergens.hamacs.ui.components.RunnerAnimation()
            }
            InfoItem("${battery?.state ?: "—"}%") {
                fr.pergens.hamacs.ui.components.BatteryAnimation(battery?.numeric?.toInt(), charging)
            }
        }
    }
    SectionTitle(stringResource(R.string.presence_title))
    PresenceHistoryCard(
        buildJsonObject {
            put("entity", JsonPrimitive(homeId ?: ""))
            (m.s("name") ?: entity(m.s("person") ?: "person.${m.s("id")}")?.name)?.let { put("name", JsonPrimitive(it)) }
            put("hours", JsonPrimitive(24))
        },
        Modifier,
    )
    SectionTitle(stringResource(R.string.events_title))
    CalendarCard(buildJsonObject { put("entities", kotlinx.serialization.json.JsonArray(m.strs("calendars").map { JsonPrimitive(it) })) }, Modifier)
}

/** Animation (dessinée par l'app) suivie de sa valeur. */
@Composable
private fun InfoItem(text: String, animation: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        animation()
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
    // Toucher la barre : arrivées et départs de la période, avec leurs heures.
    var details by remember { mutableStateOf(false) }
    if (details) PresenceDetailsSheet(
        listOfNotNull(stringResource(R.string.presence_title), card.s("name") ?: current?.name).joinToString(" · "),
        spans, hours, on, off,
    ) { details = false }
    DashCard(modifier.fillMaxWidth().tapHold({ state.lastInteraction = System.currentTimeMillis(); details = true }), padding = 12.dp) {
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
    // Hauteur limitée au bas de l'écran moins la marge du bas (= niveau du bas des caméras) : au-delà, la liste
    // défile seule et s'efface en fondu au lieu d'être coupée net.
    BottomFadeScroll(modifier, card.d("bottom_margin", 24.0).dp, 8.dp) {
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

/**
 * Flèche de défilement des avatars, aux couleurs du menu en pilules : utilisable = comme la pilule active,
 * en bout de liste = comme une pilule inactive, icône atténuée.
 */
@Composable
private fun MembersArrow(icon: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Dash.colors
    Box(Modifier.size(width = 36.dp, height = 80.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(36.dp).background(if (enabled) c.menuActiveButtonBackground else c.menuBackground, CircleShape)
                .clip(CircleShape).tapHold(if (enabled) onClick else null),
            contentAlignment = Alignment.Center,
        ) { HaIcon(icon, size = 22.dp, tint = if (enabled) c.menuActiveButton else c.menuInactiveButton.copy(alpha = 0.5f)) }
    }
}
