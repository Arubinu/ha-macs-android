package fr.pergens.hamacs.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import fr.pergens.hamacs.App
import fr.pergens.hamacs.R
import fr.pergens.hamacs.detections.Detection
import fr.pergens.hamacs.detections.DetectionConfig
import fr.pergens.hamacs.detections.DetectionScanner
import fr.pergens.hamacs.detections.HistoryLogic
import fr.pergens.hamacs.detections.HistoryLogic.Period
import fr.pergens.hamacs.ui.components.HaIcon
import fr.pergens.hamacs.ui.components.tapHold
import fr.pergens.hamacs.ui.theme.Audiowide
import fr.pergens.hamacs.ui.theme.Dash
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Muted = Color(255, 255, 255, 150)
private val Faint = Color(255, 255, 255, 18)
private val PanelBackground = Color(0xFF0D0D0F)

/** Fenêtre de l'historique : jusqu'à un an de captures, relues toutes les 2 min. */
private const val HISTORY_MINUTES = 366 * 24 * 60

/** Fermeture si personne ne touche l'écran pendant ce délai (retour au tableau de bord). */
private const val IDLE_CLOSE_MS = 3 * 60_000L

private fun String.capitalized() = replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

/**
 * Historique des détections de la zone des détections ([card] : sa configuration). Période (aujourd'hui, hier,
 * 7 ou 30 jours, un jour choisi), filtre par caméra, histogramme d'activité (toucher une barre : ce créneau), grille
 * des captures groupées par heure ou par jour, et la capture choisie en grand (lecture sur place ou plein écran).
 */
@Composable
fun DetectionHistoryDialog(card: JsonObject, onDismiss: () -> Unit) {
    val state = LocalDashState.current
    val cfg = remember(card) { DetectionConfig(card) }
    var all by remember { mutableStateOf<List<Detection>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(cfg) {
        if (cfg.type == "entities") return@LaunchedEffect
        while (true) {
            runCatching { DetectionScanner.scan(cfg, HISTORY_MINUTES).detections }
                .onSuccess { all = it; error = null }
                .onFailure { if (all == null) error = it.message ?: it.javaClass.simpleName }
            delay(120_000)
        }
    }

    var period by remember { mutableStateOf(Period.Today) }
    var day by remember { mutableStateOf<LocalDate?>(null) }
    var camera by remember { mutableStateOf<String?>(null) }
    var bucket by remember { mutableStateOf<HistoryLogic.Bucket?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }

    // Inactivité : fermeture (le tableau de bord, puis l'accueil, reprennent la main).
    var touchedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(touchedAt, state.player) {
        if (state.player != null) return@LaunchedEffect
        delay(IDLE_CLOSE_MS)
        onDismiss()
    }

    val today = LocalDate.now()
    val (start, end) = HistoryLogic.range(period, day, today)
    val hourly = HistoryLogic.hourly(start, end)
    val list = all.orEmpty()
    val inPeriod = list.filter { !it.time.isBefore(start) && it.time.isBefore(end) }
    val perCamera = inPeriod.groupingBy { it.camera }.eachCount()
    val cameras = (cfg.cameras.map { it.name }.filter { it.isNotBlank() } + perCamera.keys).distinct()
    val byCamera = if (camera == null) inPeriod else inPeriod.filter { it.camera == camera }
    val buckets = HistoryLogic.histogram(byCamera.map { it.time }, start, end)
    val shown = bucket?.let { b -> byCamera.filter { !it.time.isBefore(b.start) && it.time.isBefore(b.end) } } ?: byCamera
    val selected = shown.firstOrNull { it.id == selectedId } ?: shown.firstOrNull()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ImmersiveWindow()
        Row(
            Modifier.fillMaxSize().background(Dash.colors.background).pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        touchedAt = System.currentTimeMillis()
                    }
                }
            },
        ) {
            // --- Liste ------------------------------------------------------------------------------------------
            Column(Modifier.weight(1.65f).fillMaxHeight().padding(start = 28.dp, top = 24.dp, end = 20.dp)) {
                Header(byCamera.size, start, end, hourly, onDismiss)
                Spacer(Modifier.height(18.dp))
                PeriodPills(period, day) { p ->
                    if (p == Period.Day) picking = true
                    else { period = p; day = null; bucket = null }
                }
                if (cameras.size > 1) {
                    Spacer(Modifier.height(12.dp))
                    CameraChips(cameras, perCamera, inPeriod.size, camera) { camera = it; bucket = null }
                }
                Spacer(Modifier.height(14.dp))
                ActivityChart(buckets, hourly, bucket) { b -> bucket = if (bucket == b) null else b }
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when {
                        cfg.type == "entities" -> Message("mdi:history", stringResource(R.string.det_history_entities))
                        all == null && error != null -> Message("mdi:image-off-outline", stringResource(R.string.det_test_error, error ?: ""))
                        all == null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Dash.colors.accent, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
                            Text(stringResource(R.string.det_history_loading), Modifier.padding(top = 14.dp), style = TextStyle(fontSize = 14.sp, color = Muted))
                        }
                        shown.isEmpty() -> Message("mdi:cctv", stringResource(R.string.det_history_none))
                        else -> CaptureGrid(shown, hourly, selected?.id) { selectedId = it.id }
                    }
                }
            }
            // --- Capture choisie --------------------------------------------------------------------------------
            Preview(
                selected, shown,
                Modifier.weight(1f).fillMaxHeight().background(PanelBackground),
            ) { selectedId = it.id }
        }

        if (picking) DayPicker(
            days = HistoryLogic.days(list.map { it.time }).toSet(),
            initial = day ?: today,
            onPick = { d -> period = Period.Day; day = d; bucket = null; picking = false },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun Header(count: Int, start: LocalDateTime, end: LocalDateTime, hourly: Boolean, onClose: () -> Unit) {
    val c = Dash.colors
    val locale = Locale.getDefault()
    val span = if (hourly) start.format(DateTimeFormatter.ofPattern("EEEE d MMMM", locale)).capitalized()
    else start.format(DateTimeFormatter.ofPattern("d MMM", locale)) + " – " + end.minusDays(1).format(DateTimeFormatter.ofPattern("d MMM", locale))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            HaIcon("mdi:history", size = 26.dp, tint = c.accent)
        }
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(stringResource(R.string.det_history_title), style = TextStyle(fontFamily = Audiowide, fontSize = 22.sp, color = Color.White))
            Text(
                pluralStringResource(R.plurals.det_count, count, count) + " · " + span,
                style = TextStyle(fontSize = 14.sp, color = Muted), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        RoundButton("mdi:close", onClick = onClose)
    }
}

/** Choix de la période : pilules comme les menus du tableau de bord. */
@Composable
private fun PeriodPills(period: Period, day: LocalDate?, onSelect: (Period) -> Unit) {
    val c = Dash.colors
    val outer = RoundedCornerShape(Dash.shapes.menuRadius)
    val dayLabel = day?.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))?.capitalized()
    val options = listOf(
        Triple(Period.Today, "mdi:calendar-today", stringResource(R.string.det_period_today)),
        Triple(Period.Yesterday, "mdi:calendar-arrow-left", stringResource(R.string.det_period_yesterday)),
        Triple(Period.Week, "mdi:calendar-week", stringResource(R.string.det_period_week)),
        Triple(Period.Month, "mdi:calendar-month", stringResource(R.string.det_period_month)),
        Triple(Period.Day, "mdi:calendar-search", dayLabel ?: stringResource(R.string.det_period_day)),
    )
    Row(
        Modifier.clip(outer).background(c.menuBackground, outer).padding(Dash.shapes.menuBorder).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { (p, icon, label) ->
            val active = p == period
            val bg by animateColorAsState(if (active) c.menuActiveButtonBackground else c.menuInactiveButtonBackground, tween(250), label = "period")
            val pill = RoundedCornerShape(Dash.shapes.menuRadius)
            Row(
                Modifier.clip(pill).background(bg, pill).tapHold({ onSelect(p) }).heightIn(min = 40.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HaIcon(icon, size = 18.dp, tint = if (active) c.menuActiveButton else c.menuInactiveButton.copy(alpha = 0.8f))
                Text(
                    label, Modifier.padding(start = 8.dp), maxLines = 1,
                    style = TextStyle(fontSize = 15.sp, color = if (active) c.menuActiveButton else c.menuInactiveButton.copy(alpha = 0.8f)),
                )
            }
        }
    }
}

/** Filtre par caméra (avec le nombre de détections de la période). */
@Composable
private fun CameraChips(cameras: List<String>, counts: Map<String, Int>, total: Int, current: String?, onSelect: (String?) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(stringResource(R.string.det_all_cameras), total, current == null) { onSelect(null) }
        cameras.forEach { name -> Chip(name, counts[name] ?: 0, current == name) { onSelect(if (current == name) null else name) } }
    }
}

@Composable
private fun Chip(label: String, count: Int, active: Boolean, onClick: () -> Unit) {
    val c = Dash.colors
    val shape = RoundedCornerShape(16.dp)
    val bg by animateColorAsState(if (active) c.accent.copy(alpha = 0.18f) else Faint, tween(200), label = "chip")
    Row(
        Modifier.clip(shape).background(bg, shape).border(1.dp, if (active) c.accent.copy(alpha = 0.8f) else Color.Transparent, shape)
            .tapHold(onClick).heightIn(min = 36.dp).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = TextStyle(fontSize = 14.sp, color = if (active) Color.White else Color.White.copy(alpha = 0.85f)), maxLines = 1)
        Text(
            count.toString(), Modifier.padding(start = 8.dp),
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (active) c.accent else Muted),
        )
    }
}

/** Activité de la période : barres par heure (une journée) ou par jour ; une barre touchée filtre ce créneau. */
@Composable
private fun ActivityChart(buckets: List<HistoryLogic.Bucket>, hourly: Boolean, selected: HistoryLogic.Bucket?, onSelect: (HistoryLogic.Bucket) -> Unit) {
    val c = Dash.colors
    val max = (buckets.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
    val locale = Locale.getDefault()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Dash.shapes.roundedRadius)).background(Color(255, 255, 255, 10))
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(84.dp), horizontalArrangement = Arrangement.spacedBy(if (buckets.size > 24) 3.dp else 5.dp), verticalAlignment = Alignment.Bottom) {
            buckets.forEach { b ->
                val active = selected == b
                val color by animateColorAsState(
                    when {
                        active -> c.accent
                        selected != null -> c.accent.copy(alpha = 0.22f)
                        b.count > 0 -> c.accent.copy(alpha = 0.6f)
                        else -> Color(255, 255, 255, 22)
                    },
                    tween(200), label = "bar",
                )
                Box(
                    Modifier.weight(1f).fillMaxHeight().tapHold(if (b.count > 0) ({ onSelect(b) }) else null),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    val h = if (b.count == 0) 3.dp else (8 + 76f * b.count / max).dp
                    Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 1.dp, bottomEnd = 1.dp)).background(color))
                }
            }
        }
        // Repères : 0 h, 6 h, 12 h, 18 h (journée) ; sinon quelques dates.
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            val step = when {
                hourly -> 6
                buckets.size > 10 -> 5
                else -> 1
            }
            buckets.forEachIndexed { i, b ->
                Box(Modifier.weight(1f)) {
                    if (i % step == 0) Text(
                        if (hourly) "${b.start.hour} h" else b.start.format(DateTimeFormatter.ofPattern(if (buckets.size > 10) "d/M" else "EEE d", locale)),
                        style = TextStyle(fontSize = 11.sp, color = Muted), maxLines = 1, softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun Message(icon: String, text: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        HaIcon(icon, size = 44.dp, tint = Color(255, 255, 255, 110))
        Text(
            text, Modifier.padding(top = 12.dp).width(420.dp),
            style = TextStyle(fontSize = 15.sp, color = Muted, textAlign = TextAlign.Center, lineHeight = 21.sp),
        )
    }
}

/** Grille des captures, groupées par heure (une journée) ou par jour, la plus récente d'abord. */
@Composable
private fun CaptureGrid(captures: List<Detection>, hourly: Boolean, selectedId: String?, onSelect: (Detection) -> Unit) {
    val locale = Locale.getDefault()
    val groups = captures.groupBy { if (hourly) it.time.withMinute(0).withSecond(0).withNano(0) else it.time.toLocalDate().atStartOfDay() }
    val grid = rememberLazyGridState()
    // Fondu au noir en bas (et en haut une fois défilée), comme les autres listes défilantes du tableau de bord.
    val bg = Dash.colors.background
    Box(Modifier.fillMaxSize()) {
    LazyVerticalGrid(
        GridCells.Adaptive(210.dp), Modifier.fillMaxSize(), grid,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        groups.forEach { (at, ds) ->
            item(key = "h$at", span = { GridItemSpan(maxLineSpan) }) {
                val title = if (hourly) "%02d:00 – %02d:00".format(at.hour, (at.hour + 1) % 24)
                else at.format(DateTimeFormatter.ofPattern("EEEE d MMMM", locale)).capitalized()
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White))
                    Text(
                        pluralStringResource(R.plurals.det_count, ds.size, ds.size), Modifier.padding(start = 10.dp),
                        style = TextStyle(fontSize = 13.sp, color = Muted),
                    )
                    Box(Modifier.padding(start = 14.dp).weight(1f).height(1.dp).background(Color(255, 255, 255, 30)))
                }
            }
            items(ds, key = { it.id }) { d -> CaptureTile(d, d.id == selectedId) { onSelect(d) } }
        }
    }
    if (grid.canScrollForward) Box(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(64.dp).background(Brush.verticalGradient(listOf(bg.copy(alpha = 0f), bg))),
    )
    if (grid.canScrollBackward) Box(
        Modifier.align(Alignment.TopCenter).fillMaxWidth().height(24.dp).background(Brush.verticalGradient(listOf(bg, bg.copy(alpha = 0f)))),
    )
    }
}

@Composable
private fun CaptureTile(d: Detection, selected: Boolean, onClick: () -> Unit) {
    val c = Dash.colors
    val shape = RoundedCornerShape(14.dp)
    val image by produceState<String?>(null, d.id) { value = runCatching { d.image() }.getOrNull() }
    val border by animateColorAsState(if (selected) c.accent else Color.Transparent, tween(200), label = "sel")
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).background(Faint).border(3.dp, border, shape).tapHold(onClick),
    ) {
        AsyncImage(image, d.camera, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0, 0, 0, 190))))
                .padding(start = 10.dp, end = 10.dp, top = 14.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (d.video != null) HaIcon("mdi:play-circle-outline", Modifier.padding(end = 5.dp), 15.dp)
            Text(d.camera, Modifier.weight(1f), style = TextStyle(fontSize = 13.sp, color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(d.time.format(DateTimeFormatter.ofPattern("HH:mm")), style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White))
        }
    }
}

/** Capture choisie en grand : lecture sur place (muette), plein écran avec le son, précédente / suivante. */
@Composable
private fun Preview(d: Detection?, items: List<Detection>, modifier: Modifier, onSelect: (Detection) -> Unit) {
    val state = LocalDashState.current
    val c = Dash.colors
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.Center) {
        if (d == null) {
            Message("mdi:motion-sensor", stringResource(R.string.det_history_pick))
            return@Column
        }
        val index = items.indexOfFirst { it.id == d.id }
        val scope = rememberCoroutineScope()
        val image by produceState<String?>(null, d.id) { value = runCatching { d.image() }.getOrNull() }
        var videoUrl by remember(d.id) { mutableStateOf<String?>(null) }
        var playing by remember(d.id) { mutableStateOf(false) }
        var shownFrame by remember(d.id) { mutableStateOf(false) }
        var failed by remember(d.id) { mutableStateOf(false) }
        suspend fun video(): String? = videoUrl ?: d.video?.let { v -> runCatching { v() }.getOrNull() }?.also { videoUrl = it }

        val shape = RoundedCornerShape(Dash.shapes.roundedRadius)
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).background(Faint)) {
            AsyncImage(image, d.camera, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (playing && videoUrl != null) fr.pergens.hamacs.ui.cards.InlineLive(
                videoUrl, "history:${d.camera}",
                Modifier.fillMaxSize().graphicsLayer { alpha = if (shownFrame) 1f else 0f },
                onFirstFrame = { shownFrame = true },
                onError = { failed = true; playing = false },
                onEnded = { playing = false; shownFrame = false },
            )
            if (playing && !shownFrame) CircularProgressIndicator(Modifier.align(Alignment.Center).size(40.dp), color = Color.White, strokeWidth = 3.dp)
        }
        Spacer(Modifier.height(18.dp))
        Text(d.camera, style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium, color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            d.time.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy · HH:mm:ss", Locale.getDefault())).capitalized(),
            Modifier.padding(top = 2.dp), style = TextStyle(fontSize = 15.sp, color = Muted),
        )
        if (failed || d.video == null) Text(
            stringResource(if (failed) R.string.video_unreadable else R.string.det_history_no_video),
            Modifier.padding(top = 8.dp), style = TextStyle(fontSize = 13.sp, color = Color(0xFFFFB74D)),
        )
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Liste de la plus récente à la plus ancienne : « précédente » remonte le temps.
            RoundButton("mdi:chevron-left", enabled = index < items.lastIndex) { items.getOrNull(index + 1)?.let(onSelect) }
            val canPlay = d.video != null && !failed
            ActionPill(
                if (playing) "mdi:stop" else "mdi:play",
                stringResource(if (playing) R.string.det_history_stop else R.string.det_history_play),
                canPlay, primary = true, modifier = Modifier.weight(1f),
            ) {
                if (playing) { playing = false; shownFrame = false }
                else scope.launch { if (video() != null) playing = true else failed = true }
            }
            // Plein écran avec le son : bouton rond (libellé long dans certaines langues).
            RoundButton("mdi:fullscreen", enabled = canPlay) {
                scope.launch {
                    val url = video() ?: run { failed = true; return@launch }
                    playing = false
                    state.player = url
                }
            }
            RoundButton("mdi:chevron-right", enabled = index > 0) { items.getOrNull(index - 1)?.let(onSelect) }
        }
        Text(
            "${index + 1} / ${items.size}", Modifier.fillMaxWidth().padding(top = 14.dp),
            style = TextStyle(fontSize = 13.sp, color = Muted, textAlign = TextAlign.Center),
        )
    }
}

@Composable
private fun RoundButton(icon: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(Color(0x22FFFFFF)).tapHold(if (enabled) onClick else null),
        contentAlignment = Alignment.Center,
    ) { HaIcon(icon, size = 26.dp, tint = if (enabled) Color.White else Color(255, 255, 255, 70)) }
}

@Composable
private fun ActionPill(icon: String, label: String, enabled: Boolean, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Dash.colors
    val shape = RoundedCornerShape(24.dp)
    val bg = when {
        !enabled -> Faint
        primary -> c.accent
        else -> Color(0x22FFFFFF)
    }
    val fg = when {
        !enabled -> Color(255, 255, 255, 70)
        primary -> Color.Black
        else -> Color.White
    }
    Row(
        modifier.height(48.dp).clip(shape).background(bg, shape).tapHold(if (enabled) onClick else null).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        HaIcon(icon, size = 22.dp, tint = fg)
        Text(label, Modifier.padding(start = 8.dp), style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = fg), maxLines = 1)
    }
}

/** Calendrier : seuls les jours qui ont des détections peuvent être choisis. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPicker(days: Set<LocalDate>, initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val c = Dash.colors
    fun millis(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    fun dateOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
    val picker = rememberDatePickerState(
        initialSelectedDateMillis = millis(initial),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = dateOf(utcTimeMillis) in days
            override fun isSelectableYear(year: Int) = days.any { it.year == year }
        },
    )
    val colors = DatePickerDefaults.colors(
        containerColor = Color(0xFF1C1C21),
        titleContentColor = Muted, headlineContentColor = Color.White,
        weekdayContentColor = Muted, subheadContentColor = Muted, navigationContentColor = Color.White,
        yearContentColor = Color.White, currentYearContentColor = c.accent,
        selectedYearContentColor = Color.Black, selectedYearContainerColor = c.accent,
        dayContentColor = Color.White, disabledDayContentColor = Color(255, 255, 255, 60),
        selectedDayContentColor = Color.Black, selectedDayContainerColor = c.accent,
        todayContentColor = c.accent, todayDateBorderColor = c.accent,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { picker.selectedDateMillis?.let { onPick(dateOf(it)) } ?: onDismiss() }) {
                Text(stringResource(R.string.ok), color = c.accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = Color.White) } },
        colors = colors,
    ) {
        DatePicker(picker, colors = colors, showModeToggle = false)
    }
}
