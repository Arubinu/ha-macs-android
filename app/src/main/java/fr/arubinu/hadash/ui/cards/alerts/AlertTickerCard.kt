package fr.arubinu.hadash.ui.cards.alerts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import fr.arubinu.hadash.App
import fr.arubinu.hadash.config.Card
import fr.arubinu.hadash.config.b
import fr.arubinu.hadash.config.d
import fr.arubinu.hadash.config.i
import fr.arubinu.hadash.config.o
import fr.arubinu.hadash.config.s
import fr.arubinu.hadash.ha.EntityState
import fr.arubinu.hadash.ui.EditTarget
import fr.arubinu.hadash.ui.LocalDashState
import fr.arubinu.hadash.ui.components.IconRegistry
import fr.arubinu.hadash.ui.components.editOutline
import fr.arubinu.hadash.ui.theme.MdiFont
import fr.arubinu.hadash.ui.theme.parseCssColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.max

/** AlertTicker Card : carte du tableau de bord (configuration modifiable en mode édition). */
@Composable
fun AlertTickerCard(card: Card, modifier: Modifier) {
    val state = LocalDashState.current
    val hub = App.instance.alerts
    val blocks by App.instance.config.blocks.collectAsState()
    val key = AlertHub.cardKey(card)
    val cfg = blocks[key]?.o("config") ?: card
    val result by hub.result(key).collectAsState()
    AtcView(
        cfg = cfg,
        result = result,
        engine = hub.engine(key),
        cardKey = key,
        modifier = modifier,
        onEdit = if (state.editMode) ({ state.editTarget = EditTarget.Card(key) }) else null,
    )
}

/** Horloge d'animation partagée (ms), lue uniquement pendant le dessin : pas de recomposition par image. */
@Composable
fun rememberAnimClock(enabled: Boolean): State<Long> {
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (true) withFrameMillis { clock.longValue = it - start }
    }
    return clock
}

private val easeOut = CubicBezierEasing(0.4f, 0f, 1f, 1f)
private val easeIn = CubicBezierEasing(0f, 0f, 0.6f, 1f)
private val bounceIn = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

/**
 * Vue complète de la carte : diapositive courante, cycle animé, boutons (snooze, historique, ✕),
 * compteur, historique, état « tout va bien ». Utilisée par la carte et par l'aperçu de l'éditeur.
 */
@Composable
fun AtcView(
    cfg: JsonObject,
    result: EngineResult,
    engine: AlertEngine,
    cardKey: String,
    modifier: Modifier = Modifier,
    previewIndex: Int? = null,
    onEdit: (() -> Unit)? = null,
) {
    val state = LocalDashState.current
    val hub = App.instance.alerts
    val i18n = hub.i18n
    val states by state.ha.states.collectAsState()
    hub.templates.version.collectAsState().value
    hub.store.version.collectAsState().value
    val list = result.active
    val disableAnim = cfg.b("disable_animation")
    val clock = rememberAnimClock(!disableAnim)
    val animName = cfg.s("cycle_animation") ?: "fold"
    val testMode = cfg.b("test_mode")
    var index by remember { mutableIntStateOf(0) }
    var outgoing by remember { mutableStateOf(false) }
    val anim = remember { Animatable(1f) }
    var historyOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val radius = (cfg.d("radius") ?: 12.0).dp
    val shape = RoundedCornerShape(radius)

    // Aperçu de l'éditeur : affiche l'alerte en cours de modification.
    LaunchedEffect(previewIndex, list) {
        if (previewIndex != null) list.indexOfFirst { it.idx == previewIndex }.takeIf { it >= 0 }?.let { index = it }
    }

    suspend fun step(dir: Int) {
        if (list.size <= 1) return
        if (animName == "none" || disableAnim) { index = Math.floorMod(index + dir, list.size); return }
        outgoing = true
        anim.snapTo(0f)
        anim.animateTo(1f, tween(340, easing = if (animName == "fade" || animName == "blur") LinearEasing else easeOut))
        index = Math.floorMod(index + dir, list.size)
        outgoing = false
        anim.snapTo(0f)
        anim.animateTo(1f, tween(if (animName == "bounce") 500 else 340, easing = when (animName) {
            "bounce" -> bounceIn; "fade", "blur" -> LinearEasing; else -> easeIn
        }))
    }

    val interval = ((cfg.d("cycle_interval") ?: 5.0) * 1000).toLong().coerceAtLeast(1000)
    LaunchedEffect(list.size, interval, testMode, historyOpen, animName) {
        while (true) {
            delay(interval)
            if (list.size > 1 && !historyOpen && !testMode) step(1)
        }
    }

    val clickable = onEdit?.let { Modifier.clickable(onClick = it) } ?: Modifier
    val root = modifier.editOutline(onEdit != null, shape).then(clickable)

    if (list.isEmpty()) {
        Box(root) {
            when {
                result.snoozedCount > 0 && cfg.b("show_snooze_bar", true) && !cfg.b("show_when_clear") ->
                    StatusBar("💤", "${result.snoozedCount} ${i18n.t("alerts_snoozed")}", "↩ ${i18n.t("snooze_reset")}", shape) { hub.store.resetSnooze() }
                result.dismissedCount > 0 && cfg.b("show_dismiss_bar", true) && !cfg.b("show_when_clear") ->
                    StatusBar("🔕", "${result.dismissedCount} ${i18n.t("alerts_dismissed")}", "↩ ${i18n.t("dismiss_reset")}", shape) { hub.store.resetDismissed() }
                cfg.b("show_when_clear") -> ClearView(cfg, engine, states, clock, shape, disableAnim || cfg.b("clear_disable_animation"))
                cfg.b("card_border") || onEdit != null -> Row(
                    Modifier.fillMaxWidth().clip(shape).background(Color(0x22FFFFFF)).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🔔", fontSize = 20.sp)
                    Text("AlertTicker Card", Modifier.padding(start = 10.dp), color = Color(0x99FFFFFF), fontSize = 14.sp)
                }
            }
        }
        return
    }

    val cur = list[Math.floorMod(index, list.size)]
    val large = cfg.b("large_buttons")
    val fs = (cfg.d("font_scale") ?: 1.0).toFloat()
    val counter = if (list.size > 1 && cfg.b("show_counter", true)) (Math.floorMod(index, list.size) + 1) to list.size else null

    Column(root) {
    Box(if (onEdit == null) swipe(cfg, cur, engine, states) { dir -> scope.launch { step(dir) } } else Modifier) {
        if (historyOpen) {
            HistoryView(cardKey, shape) { historyOpen = false }
        } else {
            val tap = cur.cfg.o("tap_action") ?: if (cur.group != null) null else cur.cfg.o("action")?.let { JsonObject(it + ("action" to kotlinx.serialization.json.JsonPrimitive("call-service"))) }
            val hold = cur.cfg.o("hold_action")
            val dbl = cur.cfg.o("double_tap_action")
            val hasAction = cur.group != null || listOf(tap, hold, dbl).any { it != null && it.s("action") != "none" }
            @OptIn(ExperimentalFoundationApi::class)
            val actionMod = if (onEdit == null && hasAction) Modifier.combinedClickable(
                onClick = {
                    if (cur.group != null && tap == null) engine.expandedGroups += cur.group.key
                    else runAction(state, engine, tap, cur)
                },
                onLongClick = hold?.let { { runAction(state, engine, it, cur) } },
                onDoubleClick = dbl?.let { { runAction(state, engine, it, cur) } },
            ) else Modifier
            Box(
                Modifier.fillMaxWidth()
                    .cycle(animName, { outgoing }, { anim.value })
                    .then(actionMod)
            ) {
                AtcSlide(
                    al = cur,
                    cfg = cfg,
                    engine = engine,
                    states = states,
                    clock = clock,
                    shape = shape,
                    counter = if (large) null else counter,
                    padEnd = if (large) (cfg.d("content_padding_right") ?: 88.0).dp else 0.dp,
                    fs = fs,
                    animate = !disableAnim && !cur.cfg.b("disable_animation"),
                    tickerList = list,
                )
            }
        }
        // Boutons
        if (!historyOpen) {
            val size = if (large) (cfg.d("large_button_size") ?: 30.0).dp else 26.dp
            val right = if (large) (cfg.d("large_button_right") ?: 8.0).dp else 7.dp
            val btnAlign = if (large) Alignment.CenterEnd else Alignment.TopEnd
            val btnTop = if (large) 0.dp else 7.dp
            Row(
                Modifier.align(btnAlign).padding(end = right, top = btnTop),
                horizontalArrangement = Arrangement.spacedBy(if (large) (46.0 - 30.0 - 8.0).dp + (size - 30.dp).coerceAtLeast(0.dp) / 2 else 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (cfg.b("show_history_button", true) && large) RoundButton("📋", size, Color(0x59000000)) { historyOpen = true }
                cur.expandedGroupKey?.let { g -> RoundButton("◀", size, Color(0x80000000)) { engine.expandedGroups -= g } }
                SnoozeButton(cur, cfg, engine, states, size, large)
            }
            if (!large && cfg.b("show_history_button", true)) {
                Box(Modifier.align(Alignment.BottomEnd).padding(end = 7.dp, bottom = 6.dp)) {
                    RoundButton("📋", 22.dp, Color(0x40000000)) { historyOpen = true }
                }
            }
            if (large && counter != null) {
                Text(
                    "${counter.first}/${counter.second}",
                    Modifier.align(Alignment.TopEnd).padding(top = (cfg.d("counter_top") ?: 5.0).dp, end = (cfg.d("counter_right") ?: 7.0).dp),
                    style = TextStyle(fontSize = (0.68f * 16 * fs).sp, fontWeight = FontWeight.Bold, color = Color(0x8CFFFFFF), letterSpacing = 0.5.sp),
                )
            }
            if (result.snoozedCount > 0 && cfg.b("show_snooze_bar", true)) {
                Text(
                    "💤 ${result.snoozedCount}",
                    Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 6.dp)
                        .clip(RoundedCornerShape(20.dp)).background(Color(0x73000000))
                        .border(1.dp, Color(0x59FFC850), RoundedCornerShape(20.dp))
                        .clickable { hub.store.resetSnooze() }.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xCCFFC850)),
                )
            }
        }
        // Mode édition : un calque capte tous les touchers (boutons 💤, 📋… compris) pour ouvrir l'éditeur.
        if (onEdit != null) Box(Modifier.matchParentSize().clickable(onClick = onEdit))
    }
    if (testMode) {
        Text(
            "🧪 ${i18n.t("test_mode_active")}",
            Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xCCFF9800)).padding(4.dp),
            style = TextStyle(fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
        )
    }
    }
}

private fun runAction(state: fr.arubinu.hadash.ui.DashState, engine: AlertEngine, action: JsonObject?, al: Active) {
    if (action == null || action.s("action") == "none") return
    when (action.s("action")) {
        "dismiss" -> engine.dismiss(al, state.ha.states.value)
        "undismiss" -> App.instance.alerts.store.resetDismissed()
        "assist" -> state.voice.toggleListening()
        else -> state.perform(action, action.s("entity") ?: al.entity, "none")
    }
}

/** Glisser : alerte précédente / suivante, ou snooze (swipe_to_snooze) vers la gauche. */
private fun swipe(cfg: JsonObject, cur: Active, engine: AlertEngine, states: Map<String, EntityState>, onStep: (Int) -> Unit): Modifier =
    Modifier.pointerInput(cur, cfg) {
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                if (kotlin.math.abs(total) > 60.dp.toPx()) {
                    if (total < 0 && cfg.b("swipe_to_snooze") && cur.entity != null) {
                        engine.snooze(cur, cur.cfg.d("snooze_duration") ?: cfg.d("snooze_default_duration") ?: 1.0)
                    } else onStep(if (total < 0) 1 else -1)
                }
            },
        ) { _, dx -> total += dx }
    }

// ------------------------------------------------------------------------------------------------
// Transitions de cycle (fold, slide, fade, flip, zoom, glitch, bounce, swing, blur, split, roll, curtain)
// ------------------------------------------------------------------------------------------------

private fun Modifier.cycle(name: String, out: () -> Boolean, progress: () -> Float): Modifier = this
    .graphicsLayer {
        val p = progress()
        val o = out()
        cameraDistance = 12f * density
        fun lerp(a: Float, b: Float) = a + (b - a) * p
        alpha = 1f; rotationX = 0f; rotationY = 0f; rotationZ = 0f; translationX = 0f; translationY = 0f; scaleX = 1f; scaleY = 1f
        renderEffect = null
        transformOrigin = TransformOrigin.Center
        when (name) {
            "none" -> Unit
            "slide" -> { translationX = size.width * if (o) lerp(0f, -1.1f) else lerp(1.1f, 0f); alpha = if (o) 1 - p else p }
            "fade" -> alpha = if (o) 1 - p else p
            "flip" -> { cameraDistance = 6f * density; rotationY = if (o) lerp(0f, 90f) else lerp(-90f, 0f); alpha = if (o) 1 - p else p }
            "zoom" -> { val s = if (o) lerp(1f, 0.6f) else lerp(1.2f, 1f); scaleX = s; scaleY = s; alpha = if (o) 1 - p else p }
            "bounce" -> {
                translationY = size.height * if (o) lerp(0f, -0.4f) else lerp(0.6f, 0f)
                scaleY = if (o) lerp(1f, 0.7f) else lerp(0.6f, 1f)
                alpha = if (o) 1 - p else p.coerceAtMost(1f)
            }
            "swing" -> { transformOrigin = TransformOrigin(0f, 0f); rotationZ = if (o) lerp(0f, 15f) else lerp(-15f, 0f); alpha = if (o) 1 - p else p }
            "blur" -> {
                val r = (if (o) p else 1 - p) * 12f * density
                if (r > 0.5f) renderEffect = BlurEffect(r, r)
                alpha = if (o) 1 - p else p
            }
            "roll" -> {
                cameraDistance = 6f * density
                rotationY = if (o) lerp(0f, -60f) else lerp(60f, 0f)
                translationX = size.width * if (o) lerp(0f, -0.6f) else lerp(0.6f, 0f)
                alpha = if (o) 1 - p else p
            }
            "split", "curtain" -> alpha = if (o) 1 - p else p
            "glitch" -> {
                val k = (p * 5).toInt().coerceAtMost(4)
                val outX = floatArrayOf(0f, 5f, -4f, 3f, -2f)
                val inX = floatArrayOf(0f, -5f, 4f, -3f, 2f)
                translationX = (if (o) outX[k] else inX[k]) * density
                alpha = if (o) floatArrayOf(1f, 1f, 1f, 1f, 0.3f)[k] else floatArrayOf(0f, 0.4f, 0.6f, 0.8f, 0.9f)[k]
                if (p >= 1f) alpha = if (o) 0f else 1f
            }
            else -> { // fold
                rotationX = if (o) lerp(0f, -88f) else lerp(88f, 0f)
                scaleY = if (o) lerp(1f, 0.05f) else lerp(0.05f, 1f)
                alpha = if (o) 1 - p else p
            }
        }
    }
    .drawWithContent {
        val p = progress()
        val o = out()
        val h = size.height
        val w = size.width
        when (name) {
            "split" -> { val k = if (o) p / 2 else (1 - p) / 2; clipRect(0f, h * k, w, h * (1 - k)) { this@drawWithContent.drawContent() } }
            "curtain" -> { val k = if (o) p / 2 else (1 - p) / 2; clipRect(w * k, 0f, w * (1 - k), h) { this@drawWithContent.drawContent() } }
            "glitch" -> {
                val k = (p * 5).toInt().coerceAtMost(4)
                val outIns = arrayOf(0f to 0f, 0.15f to 0.35f, 0.55f to 0.08f, 0.28f to 0.22f, 0f to 0f)
                val inIns = arrayOf(0.5f to 0.5f, 0.08f to 0.55f, 0.35f to 0.15f, 0.22f to 0.28f, 0f to 0f)
                val (top, bottom) = if (p >= 1f) (if (o) 0.5f to 0.5f else 0f to 0f) else if (o) outIns[k] else inIns[k]
                clipRect(0f, h * top, w, h * (1 - bottom)) { this@drawWithContent.drawContent() }
            }
            else -> drawContent()
        }
    }

// ------------------------------------------------------------------------------------------------
// Diapositive d'une alerte
// ------------------------------------------------------------------------------------------------

@Composable
fun AtcSlide(
    al: Active,
    cfg: JsonObject,
    engine: AlertEngine,
    states: Map<String, EntityState>,
    clock: State<Long>,
    shape: androidx.compose.ui.graphics.Shape,
    counter: Pair<Int, Int>?,
    padEnd: Dp,
    fs: Float,
    animate: Boolean,
    tickerList: List<Active>? = null,
) {
    val i18n = App.instance.alerts.i18n
    val theme = AtcThemes[al.theme]
    val d = LocalDensity.current.density
    val haTheme = cfg.b("ha_theme")
    val accent = al.cfg.s("color")?.let { parseCssColor(it) }
    val vertical = cfg.b("vertical")
    val center = cfg.s("text_align") == "center" || vertical
    val svRight = (al.cfg.s("secondary_value_align") ?: cfg.s("secondary_value_align")) == "right"
    val severityBorder = cfg["severity_border"]?.let { cfg.b("severity_border", true) } ?: true
    val bgOverride = cfg.s("card_background")?.takeIf { it != "true" && it != "false" }?.let { parseCssColor(it) }
    val fixedHeight = cfg.d("card_height")?.dp
    val minimalAccent = AtcThemes.accentFor(al.theme)
    val es = al.entity?.let { states[it] }
    val isTimer = theme.category == Category.Timer
    val timer = if (isTimer) engine.timerData(al, states) else null
    val timerColor = timer?.let { timerColor(it.progress) }

    // Arrondi réel de la carte (le même que `shape`) : bordure et halo suivent la découpe.
    val cornerDp = (cfg.d("radius") ?: 12.0).toFloat()
    val border = theme.border
    val borderColor = accent ?: if (theme.id == "minimal") minimalAccent else border?.sideColor ?: border?.color
    val badgeColor = accent ?: if (theme.id == "minimal") minimalAccent else if (haTheme) fr.arubinu.hadash.ui.theme.Dash.colors.accent else theme.badge
    val titleColor = if (haTheme) Color.White else theme.title

    // Halo (box-shadow) dessiné hors du cadre, avant le découpage.
    val glowMod = theme.glow?.takeIf { !haTheme }?.let { g ->
        Modifier.drawBehind {
            val p = if (animate && g.period > 0) phase(clock.value, g.period) else 0f
            val k = when {
                g.blink -> if (p < 0.5f) 0f else 1f
                g.flashAt != null -> if (kotlin.math.abs(p - g.flashAt) < 0.012f || kotlin.math.abs(p - g.flashAt - 0.02f) < 0.008f) 1f else 0f
                g.period > 0 -> breathe(p)
                else -> 0f
            }
            val col = lerpColor(g.color, g.color2, k)
            val blur = (g.blur + (g.blur2 - g.blur) * k)
            if (blur > 0.5f && col.alpha > 0.01f) drawIntoCanvas { cv ->
                val paint = android.graphics.Paint().apply {
                    color = col.toArgb()
                    maskFilter = android.graphics.BlurMaskFilter(blur * d * 0.5f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                }
                val r = cornerDp * d
                cv.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint)
            }
        }
    } ?: Modifier

    val bodyBrush: Brush? = when {
        bgOverride != null -> Brush.linearGradient(listOf(bgOverride, bgOverride))
        haTheme -> Brush.linearGradient(listOf(Color(0xFF1C1C1C), Color(0xFF1C1C1C)))
        else -> null
    }

    Box(
        Modifier.fillMaxWidth()
            .then(if (fixedHeight != null) Modifier.height(fixedHeight) else Modifier)
            .then(glowMod)
            .clip(shape)
            .drawBehind {
                drawRect(bodyBrush ?: theme.bg.brush(size.width, size.height))
                if (!haTheme && animate) theme.decor.forEach { drawDecor(it, clock.value, d, accent ?: theme.color) }
                if (theme.id == "hourglass" && timer != null && timerColor != null) {
                    val fh = size.height * timer.progress.coerceAtLeast(0f)
                    drawRect(timerColor.copy(alpha = 0.125f), Offset(0f, size.height - fh), Size(size.width, fh))
                }
                if (theme.id == "timer_pulse" && timerColor != null && animate) {
                    val speed = max(0.4f, (if (timer.progress >= 0) timer.progress else 0.8f) * 2.5f)
                    val k = breathe(phase(clock.value, (speed * 1000).toInt()))
                    drawRect(timerColor.copy(alpha = 0.25f * k), style = Stroke(4 * d * k))
                }
            }
            .drawWithContent {
                drawContent()
                // Bordure (et barre de gravité à gauche pour warning / info / success / minimal).
                if (border != null && severityBorder) {
                    val glowK = theme.glow?.let { g -> if (animate && g.period > 0 && g.border2 != null) (if (g.blink) (if (phase(clock.value, g.period) < 0.5f) 0f else 1f) else breathe(phase(clock.value, g.period))) else 0f } ?: 0f
                    val bc = if (theme.glow?.border2 != null && accent == null) lerpColor(border.color, theme.glow.border2, glowK) else if (border.left != null) border.color else borderColor ?: border.color
                    val bw = border.width * d
                    // Trait centré à bw/2 du bord : rayon concentrique à la découpe pour qu'il suive tout l'arrondi.
                    val r = (cornerDp * d - bw / 2).coerceAtLeast(0f)
                    drawRoundRect(bc, Offset(bw / 2, bw / 2), Size(size.width - bw, size.height - bw), CornerRadius(r), style = Stroke(bw))
                    border.left?.let { lw -> drawRect(borderColor ?: border.color, Offset.Zero, Size(lw * d, size.height)) }
                } else if (theme.id == "timer_pulse" && timerColor != null) {
                    drawRect(timerColor, Offset.Zero, Size(4 * d, size.height))
                }
                if (theme.id == "countdown" && timer != null && timerColor != null) {
                    drawRect(Color(0x26808080), Offset(0f, size.height - 4 * d), Size(size.width, 4 * d))
                    drawRect(timerColor, Offset(0f, size.height - 4 * d), Size(size.width * timer.progress.coerceAtLeast(0f), 4 * d))
                }
            }
    ) {
        when (theme.id) {
            "ticker" -> TickerSlide(tickerList ?: listOf(al), engine, states, fs)
            else -> {
                val pad = if (isTimer) PaddingSpec(10f, 14f, 12f) else PaddingSpec(16f, 18f, 14f)
                val iconSlot: @Composable () -> Unit = {
                    if (theme.id == "battery3d") Battery3dIcon(es) else AtcIcon(al, theme, es, clock, fs, animate)
                }
                val contentSlot: @Composable (Modifier) -> Unit = { m ->
                    Column(m, horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start) {
                        if (theme.id == "matrix") MatrixContent(al, engine, states, fs, clock, animate)
                        else {
                            val label = badgeLabel(al, theme, timer, i18n)
                            val title = engine.resolve(al.cfg.s("message"), al, states)
                            if (svRight) {
                                if (label != null) Badge(label, theme, badgeColor, fs)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TitleText(title, theme, titleColor, fs, center, Modifier.weight(1f, fill = false))
                                    Spacer(Modifier.width(8.dp))
                                    SecondaryLines(al, cfg, engine, states, fs, theme, compactRight = true)
                                }
                            } else {
                                if (label != null) Badge(label, theme, badgeColor, fs)
                                TitleText(title, theme, titleColor, fs, center)
                                if (al.group != null) {
                                    SecondaryText(al.cfg.s("secondary_text") ?: al.group.names.joinToString(" · "), fs)
                                } else SecondaryLines(al, cfg, engine, states, fs, theme, compactRight = false)
                            }
                            if (theme.id == "battery3d" && es?.state?.toDoubleOrNull() != null) Battery3dBar(es)
                        }
                    }
                }
                val rightSlot: @Composable () -> Unit = {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        when {
                            al.group != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${al.group.members.size}", style = TextStyle(fontSize = (1.4f * 16 * fs).sp, fontWeight = FontWeight.Bold, color = titleColor))
                                Text(" ▼", style = TextStyle(fontSize = 10.sp, color = Color(0x99FFFFFF)))
                            }
                            theme.id == "battery" -> es?.state?.toDoubleOrNull()?.takeIf { it in 0.0..100.0 }?.let { v ->
                                val lvl = Math.round(v).toInt()
                                val col = when { lvl < 20 -> Color(0xFFF44336); lvl < 40 -> Color(0xFFFFA726); else -> Color(0xFF66BB6A) }
                                Text("$lvl%", style = TextStyle(fontSize = (1.5f * 16 * fs).sp, fontWeight = FontWeight.Bold, color = col))
                            }
                            theme.id == "battery3d" -> es?.state?.toDoubleOrNull()?.takeIf { it in 0.0..100.0 }?.let { v ->
                                val col = b3dColor(Math.round(v).toInt()).first
                                Text("${Math.round(v)}%", style = TextStyle(fontSize = (1.7f * 16 * fs).sp, fontWeight = FontWeight.Bold, color = col))
                            }
                            theme.id == "timer_ring" && timer != null && timerColor != null -> TimerRing(timer, timerColor)
                            isTimer && timer != null && timerColor != null ->
                                Text(timer.remaining, style = TextStyle(fontSize = (1.3f * 16 * fs).sp, fontWeight = FontWeight.ExtraBold, color = timerColor))
                        }
                        if (theme.decor.contains(Decor.WarnDot)) WarnDot(theme.badge, clock, if (theme.id == "caution") 1500 else 2000, animate)
                        counter?.let { (a, n) ->
                            Text("$a/$n", style = TextStyle(fontSize = (0.85f * 16 * fs).sp, fontWeight = FontWeight.Bold, color = Color(0xA6FFFFFF), letterSpacing = 0.5.sp))
                        }
                    }
                }
                val extraEnd = when (theme.id) { "radar" -> 86.dp; else -> 0.dp }
                if (vertical) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = (pad.h * fs).dp, vertical = (pad.v * fs).dp).padding(end = padEnd.coerceAtMost(18.dp)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        iconSlot()
                        contentSlot(Modifier.fillMaxWidth())
                        if (padEnd == 0.dp) rightSlot()
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().then(if (fixedHeight != null) Modifier.fillMaxHeight() else Modifier)
                            .padding(start = (pad.h * fs).dp, top = (pad.v * fs).dp, bottom = (pad.v * fs).dp, end = (pad.h * fs).dp + padEnd + extraEnd),
                        verticalAlignment = if (theme.id == "matrix") Alignment.Top else Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy((pad.gap * fs).dp),
                    ) {
                        iconSlot()
                        contentSlot(Modifier.weight(1f))
                        if (padEnd == 0.dp && !svRight) rightSlot()
                    }
                }
            }
        }
    }
}

private data class PaddingSpec(val v: Float, val h: Float, val gap: Float)

private fun lerpColor(a: Color, b: Color, k: Float) = Color(
    a.red + (b.red - a.red) * k, a.green + (b.green - a.green) * k, a.blue + (b.blue - a.blue) * k, a.alpha + (b.alpha - a.alpha) * k,
)

fun timerColor(progress: Float): Color = when {
    progress < 0 -> Color(0xFF039BE5)
    progress > 0.5f -> Color(0xFF43A047)
    progress > 0.2f -> Color(0xFFF57C00)
    else -> Color(0xFFE53935)
}

private fun badgeLabel(al: Active, theme: AtcTheme, timer: AlertEngine.Timer?, i18n: AtcI18n): String? {
    if (al.cfg["show_badge"]?.let { al.cfg.b("show_badge", true) } == false) return null
    al.cfg.s("badge_label")?.takeIf { it.isNotEmpty() }?.let { return it }
    if (timer != null) return if (timer.active) i18n.t("timer_active") else i18n.t("timer_done")
    return when (theme.category) {
        Category.Critical -> i18n.t("critical")
        Category.Warning -> i18n.t("warning_label")
        Category.Ok -> i18n.t("success_label")
        else -> i18n.t("info_label")
    }
}

@Composable
private fun Badge(text: String, theme: AtcTheme, color: Color, fs: Float) {
    val grad = theme.badgeGradient
    val base = TextStyle(
        fontSize = (theme.badgeRem * 16 * fs).sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (if (theme.id == "retro" || theme.id == "neon") 3 else if (theme.category == Category.Timer) 1 else 2).sp,
        color = if (theme.category == Category.Timer) Color(0x8CFFFFFF) else color,
        shadow = if (theme.badgeGlow) Shadow(color, blurRadius = 16f) else null,
        fontFamily = if (theme.mono) FontFamily.Monospace else null,
    )
    val style = grad?.let { base.merge(TextStyle(brush = Brush.horizontalGradient(it))) } ?: base
    Text(text.uppercase(), Modifier.padding(bottom = 3.dp), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun TitleText(text: String, theme: AtcTheme, color: Color, fs: Float, center: Boolean, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier,
        style = TextStyle(
            fontSize = (theme.titleRem * 16 * fs).sp,
            lineHeight = (theme.titleRem * 16 * fs * 1.3f).sp,
            fontWeight = FontWeight(theme.titleWeight),
            color = color,
            textAlign = if (center) TextAlign.Center else TextAlign.Start,
            fontFamily = if (theme.mono) FontFamily.Monospace else null,
            letterSpacing = if (theme.id == "arcade") 1.sp else 0.sp,
            shadow = when (theme.id) {
                "neon" -> Shadow(Color(0x66C8C8FF), blurRadius = 12f)
                "retro" -> Shadow(Color(0x73FF8F00), blurRadius = 8f)
                "motion" -> Shadow(Color(0x6600FF41), blurRadius = 8f)
                else -> null
            },
        ),
        maxLines = if (theme.id == "battery" || theme.id == "battery3d") 1 else 3,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun SecondaryText(text: String, fs: Float, weight: FontWeight = FontWeight.Normal, sizeRem: Float = 0.78f, alpha: Float = 0.72f) {
    Text(
        text,
        Modifier.padding(top = 3.dp),
        style = TextStyle(fontSize = (sizeRem * 16 * fs).sp, fontWeight = weight, color = Color.White.copy(alpha = 0.85f * alpha)),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** `_renderSecondaryValue` : nom de l'entité du filtre, texte secondaire, valeur d'une autre entité. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SecondaryLines(al: Active, cfg: JsonObject, engine: AlertEngine, states: Map<String, EntityState>, fs: Float, theme: AtcTheme, compactRight: Boolean) {
    val es = al.entity?.let { states[it] }
    val src = al.source
    val fromFilter = src != null && (src.s("entity_filter") != null || src.s("device_class") != null)
    if (fromFilter && al.cfg.b("show_filter_name", true)) {
        var name = es?.attrString("friendly_name") ?: al.entity ?: ""
        if (theme.id == "battery" && al.cfg.b("battery_trim_name")) name = name.replace(Regex("\\s*battery\\s*$", RegexOption.IGNORE_CASE), "").trim()
        val showLevel = theme.id == "battery" && es?.state?.toDoubleOrNull()?.let { it in 0.0..100.0 } == true
        val st = if (al.cfg.b("show_filter_state") && es != null && !showLevel) engine.formatState(es, al.cfg.s("attribute")) else null
        SecondaryText(name + (st?.let { "  $it" } ?: ""), fs, FontWeight.SemiBold, if (compactRight) 0.9f else 0.92f, 0.9f)
        if (theme.id == "battery") return
    }
    al.cfg.s("secondary_text")?.let { t ->
        val r = engine.resolve(t, al, states)
        if (r.isNotEmpty()) SecondaryText(r, fs, sizeRem = if (compactRight) 0.9f else 0.78f, alpha = if (compactRight) 0.85f else 0.72f)
    }
    al.cfg.s("secondary_entity")?.let { sid ->
        val ses = states[sid]
        if (ses != null) {
            val v = engine.formatState(ses, al.cfg.s("secondary_attribute"))
            if (v.isNotEmpty()) {
                val name = if (al.cfg.b("show_secondary_name")) ses.attrString("friendly_name") ?: sid else null
                val text = name?.let { "$it $v" } ?: v
                Text(
                    text,
                    Modifier.padding(top = 3.dp).then(if (text.length > 28) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier),
                    style = TextStyle(fontSize = (0.92f * 16 * fs).sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.77f)),
                    maxLines = 1,
                )
            }
        } else SecondaryText("⚠ $sid", fs, sizeRem = 0.72f, alpha = 0.5f)
    }
}

// --- Icône ----------------------------------------------------------------------------------------

@Composable
private fun AtcIcon(al: Active, theme: AtcTheme, es: EntityState?, clock: State<Long>, fs: Float, animate: Boolean) {
    val ha = LocalDashState.current.ha
    val a = al.cfg
    val sizeDp = parseCssSize(a.s("icon_size")) ?: (theme.iconRem * 16 * fs)
    val tint = a.s("icon_color")?.let { parseCssColor(it) }
    val box = theme.iconBox
    val imageUrl = a.s("icon_image") ?: if (a.b("use_entity_picture")) es?.attrString("entity_picture") else null
    val glow = theme.iconGlow
    val anim = if (animate) theme.iconAnim else IconAnim.None
    val swingIcon = when (theme.id) { "door" -> "mdi:door-open"; "window" -> "mdi:window-open-variant"; else -> null }
    val icon: String = when {
        imageUrl != null -> ""
        a.s("icon") == null && !a.b("use_ha_icon") && swingIcon != null -> swingIcon
        a.s("icon") != null -> a.s("icon")!!
        a.b("use_ha_icon") && es != null -> entityIcon(es)
        else -> theme.emoji
    }
    val isGlyph = Regex("^[\\w-]+:").containsMatchIn(icon)
    val glyphSize = if (box != null) sizeDp.coerceAtMost(box.size * 0.6f).let { if (a.s("icon_size") == null) box.size * 0.48f else it } else sizeDp
    val content: @Composable () -> Unit = {
        Box(
            Modifier.graphicsLayer {
                if (anim == IconAnim.None) return@graphicsLayer
                val t = clock.value
                iconTransform(anim, theme.id, t, density)
            },
            contentAlignment = Alignment.Center,
        ) {
            when {
                imageUrl != null -> AsyncImage(ha.resolveUrl(imageUrl), null, Modifier.size(parseCssSize(a.s("icon_size"))?.dp ?: 32.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Fit)
                isGlyph -> {
                    val name = icon.substringAfter(':')
                    if (icon.startsWith("hue:")) fr.arubinu.hadash.ui.components.HaIcon(icon, size = glyphSize.dp, tint = tint ?: Color(0xE6FFFFFF))
                    else Text(
                        IconRegistry.mdiChar(name) ?: IconRegistry.mdiChar("bell") ?: "?",
                        style = TextStyle(fontFamily = MdiFont, fontSize = glyphSize.sp, color = tint ?: Color(0xE6FFFFFF),
                            shadow = glow?.let { Shadow(it, blurRadius = 18f) }),
                    )
                }
                else -> Text(icon, style = TextStyle(fontSize = (glyphSize).sp, color = tint ?: Color.White, shadow = glow?.let { Shadow(it, blurRadius = 20f) }))
            }
        }
    }
    if (box != null) {
        val bshape = if (box.shape == IconBoxShape.Circle) CircleShape else RoundedCornerShape(box.radius.dp)
        Box(
            Modifier.size((box.size * fs).dp)
                .then(if (theme.id == "notification") Modifier.drawBehind {
                    drawIntoCanvas { cv ->
                        val p = android.graphics.Paint().apply { color = Color(0x801565C0).toArgb(); maskFilter = android.graphics.BlurMaskFilter(7 * density, android.graphics.BlurMaskFilter.Blur.NORMAL) }
                        cv.nativeCanvas.drawRoundRect(0f, 4 * density, size.width, size.height + 4 * density, 14 * density, 14 * density, p)
                    }
                } else Modifier)
                .clip(bshape)
                .background(if (box.bg.size > 1) Brush.linearGradient(box.bg) else Brush.linearGradient(listOf(box.bg[0], box.bg[0])))
                .then(box.border?.let { Modifier.border(box.borderWidth.dp, it, bshape) } ?: Modifier),
            contentAlignment = Alignment.Center,
        ) { content() }
        if (theme.id == "notification") NotificationDot(clock, animate)
    } else content()
}

@Composable
private fun NotificationDot(clock: State<Long>, animate: Boolean) {
    Box(
        Modifier.offset(x = (-14).dp, y = (-20).dp).size(11.dp)
            .graphicsLayer { val s = if (animate) 1f + 0.4f * breathe(phase(clock.value, 2000)) else 1f; scaleX = s; scaleY = s }
            .clip(CircleShape).background(Color(0xFFFF5252)).border(2.dp, Color(0xFF001428), CircleShape)
    )
}

/** Animations des icônes, transcrites des keyframes CSS de chaque thème. */
private fun androidx.compose.ui.graphics.GraphicsLayerScope.iconTransform(anim: IconAnim, themeId: String, t: Long, density: Float) {
    when (anim) {
        IconAnim.None -> Unit
        IconAnim.Flash -> alpha = if (phase(t, 800) < 0.5f) 1f else 0.3f
        IconAnim.FlashFast -> alpha = if (phase(t, 500) < 0.5f) 1f else 0.25f
        IconAnim.Flicker -> { val p = phase(t, 800).let { if (it < 0.5f) it * 2 else 2 - it * 2 }; val e = p * p * (3 - 2 * p); scaleX = 1f + 0.08f * e; scaleY = scaleX; rotationZ = -3f + 6f * e }
        IconAnim.Spin -> rotationZ = 360f * phase(t, if (themeId == "update") 3000 else 7000)
        IconAnim.SpinFast -> rotationZ = 360f * phase(t, 1800)
        IconAnim.Pulse -> { val k = breathe(phase(t, if (themeId == "arcade") 2000 else 2800)); val s = 1f + (if (themeId == "arcade") 0.06f else 0.09f) * k; scaleX = s; scaleY = s }
        IconAnim.PulseSoft -> { val (per, amp) = when (themeId) { "lock" -> 3000 to 0.05f; "plant" -> 3000 to 0.1f; else -> 2500 to 0.1f }; val s = 1f + amp * breathe(phase(t, per)); scaleX = s; scaleY = s }
        IconAnim.Bob -> {
            val (per, amp) = when (themeId) { "leak" -> 2000 to 4f; "cloud" -> 5000 to 5f; "sunrise" -> 4000 to 4f; "nebula" -> 5000 to 4f; "vapor" -> 3000 to 5f; "quantum" -> 3000 to 3f; else -> 2000 to 5f }
            translationY = -amp * density * breathe(phase(t, per))
        }
        IconAnim.Shake -> rotationZ = keyframes(phase(t, 450), 0f to 0f, 0.25f to -6f, 0.75f to 6f, 1f to 0f)
        IconAnim.Swing -> { cameraDistance = 4f * density; transformOrigin = TransformOrigin(0f, 0.5f); rotationY = keyframes(phase(t, 3500), 0f to 0f, 0.15f to -75f, 0.52f to -75f, 0.75f to 0f, 1f to 0f) }
        IconAnim.SwingV -> { cameraDistance = 4f * density; transformOrigin = TransformOrigin(0.5f, 0f); rotationX = keyframes(phase(t, 3500), 0f to 0f, 0.15f to 60f, 0.52f to 60f, 0.75f to 0f, 1f to 0f) }
        IconAnim.Sway -> translationX = 5f * density * breathe(phase(t, 1200))
        IconAnim.Scan -> translationX = density * keyframes(phase(t, 2500), 0f to 0f, 0.3f to 6f, 0.7f to -6f, 1f to 0f)
        IconAnim.Beat -> {
            val s = if (themeId == "music") 1f + 0.12f * breathe(phase(t, 1200)) else keyframes(phase(t, 1100), 0f to 1f, 0.15f to 1.14f, 0.3f to 1f, 1f to 1f)
            scaleX = s; scaleY = s
        }
        IconAnim.Bounce -> { val s = 1f + 0.12f * breathe(phase(t, 1200)); scaleX = s; scaleY = s }
        IconAnim.Glow -> Unit
        IconAnim.Ping -> {
            val p = phase(t, if (themeId == "tips") 4000 else if (themeId == "check") 2000 else 3000)
            alpha = if (themeId == "tips") (if (p in 0.88f..0.96f) 0.6f else 1f) else 1f
            if (themeId == "radar") { val k = keyframes(p, 0f to 1f, 0.85f to 1f, 0.9f to 1.15f, 1f to 1f); scaleX = k; scaleY = k }
        }
        IconAnim.Blink -> {
            val p = phase(t, if (themeId == "satellite") 2000 else 2500)
            alpha = when (themeId) {
                "satellite" -> if (p in 0.85f..0.95f) 0.3f else 1f
                "presence" -> if (p in 0.82f..0.94f) 0.35f else 1f
                else -> 1f
            }
        }
        IconAnim.Wobble -> { val k = breathe(phase(t, 4000)); scaleX = 1f + 0.08f * k; scaleY = scaleX; translationY = -3f * density * k }
        IconAnim.Zap -> { val k = breathe(phase(t, 900)); scaleX = 1f + 0.18f * k; scaleY = scaleX; rotationZ = 6f * k }
        IconAnim.Thunder -> {
            if (themeId == "volt") { val p = phase(t, 1200); val s = keyframes(p, 0f to 1f, 0.78f to 1f, 0.82f to 1.18f, 1f to 1f); scaleX = s; scaleY = s }
            else {
                val p = phase(t, 3500)
                when {
                    p >= 0.80f && p < 0.81f -> { translationX = -3f * density; translationY = 2f * density; rotationZ = -3f }
                    p >= 0.81f && p < 0.82f -> { translationX = 3f * density; translationY = -2f * density; rotationZ = 3f }
                    p >= 0.82f && p < 0.83f -> { translationX = -2f * density; translationY = density; rotationZ = -1f }
                }
            }
        }
        IconAnim.Collapse -> { val s = 1f - 0.09f * breathe(phase(t, 4000)); scaleX = s; scaleY = s }
        IconAnim.Sparkle -> Unit
        IconAnim.Facet -> {
            val p = phase(t, 3500)
            val s = keyframes(p, 0f to 1f, 0.25f to 1.1f, 0.75f to 1.07f, 1f to 1f); scaleX = s; scaleY = s
            rotationZ = keyframes(p, 0f to 0f, 0.25f to -4f, 0.75f to 4f, 1f to 0f)
        }
        IconAnim.FlareSpin -> { val k = breathe(phase(t, 2500)); scaleX = 1f + 0.12f * k; scaleY = scaleX; rotationZ = 20f * k }
        IconAnim.Tilt -> Unit
        IconAnim.FlickerHolo -> { val p = phase(t, 5000); alpha = when { p in 0.92f..0.93f -> 0.3f; p in 0.95f..0.96f -> 0.5f; else -> 1f } }
        IconAnim.FloatSpin -> { val k = breathe(phase(t, 6000)); translationY = -4f * density * k; rotationZ = 20f * k }
    }
}

/** Icône par défaut d'une entité (use_ha_icon) : attribut `icon`, sinon selon le domaine / la classe. */
fun entityIcon(e: EntityState): String {
    e.attrString("icon")?.let { return it }
    App.instance.alerts.registry.entities[e.entityId]?.icon?.let { return it }
    val dc = e.attrString("device_class")
    val on = e.state == "on"
    return when (e.domain) {
        "binary_sensor" -> when (dc) {
            "door" -> if (on) "mdi:door-open" else "mdi:door-closed"
            "window" -> if (on) "mdi:window-open" else "mdi:window-closed"
            "motion" -> if (on) "mdi:motion-sensor" else "mdi:motion-sensor-off"
            "smoke" -> if (on) "mdi:smoke-detector-alert" else "mdi:smoke-detector"
            "moisture" -> if (on) "mdi:water-alert" else "mdi:water-off"
            "battery" -> if (on) "mdi:battery-alert" else "mdi:battery"
            "connectivity" -> if (on) "mdi:check-network-outline" else "mdi:close-network-outline"
            "occupancy", "presence" -> if (on) "mdi:home" else "mdi:home-outline"
            else -> if (on) "mdi:checkbox-marked-circle" else "mdi:radiobox-blank"
        }
        "sensor" -> when (dc) {
            "battery" -> e.state.toDoubleOrNull()?.let { v -> if (v >= 95) "mdi:battery" else if (v < 10) "mdi:battery-outline" else "mdi:battery-${(v / 10).toInt() * 10}" } ?: "mdi:battery-unknown"
            "temperature" -> "mdi:thermometer"
            "humidity" -> "mdi:water-percent"
            "power" -> "mdi:flash"
            "energy" -> "mdi:lightning-bolt"
            "timestamp" -> "mdi:clock"
            else -> "mdi:eye"
        }
        "light" -> "mdi:lightbulb"
        "switch" -> if (on) "mdi:toggle-switch-variant" else "mdi:toggle-switch-variant-off"
        "lock" -> if (e.state == "unlocked") "mdi:lock-open" else "mdi:lock"
        "cover" -> if (e.state == "open") "mdi:window-shutter-open" else "mdi:window-shutter"
        "camera" -> "mdi:cctv"
        "media_player" -> "mdi:cast"
        "person" -> "mdi:account"
        "update" -> "mdi:package-up"
        "timer" -> "mdi:timer-outline"
        "climate" -> "mdi:thermostat"
        "alarm_control_panel" -> "mdi:shield"
        "input_text" -> "mdi:form-textbox"
        "weather" -> "mdi:weather-partly-cloudy"
        else -> "mdi:bell"
    }
}

/** Taille CSS (`1.2em`, `24px`, `2rem`) convertie en dp. */
private fun parseCssSize(s: String?): Float? {
    if (s.isNullOrBlank()) return null
    val v = Regex("^\\s*([0-9.]+)\\s*(px|em|rem)?").find(s) ?: return null
    val n = v.groupValues[1].toFloatOrNull() ?: return null
    return when (v.groupValues[2]) { "em", "rem" -> n * 16; else -> n }
}

// --- Éléments propres à certains thèmes -----------------------------------------------------------

@Composable
private fun WarnDot(color: Color, clock: State<Long>, period: Int, animate: Boolean) {
    Box(
        Modifier.size(8.dp).graphicsLayer {
            if (animate) { val k = breathe(phase(clock.value, period)); alpha = 1f - 0.7f * k; scaleX = 1f - 0.4f * k; scaleY = scaleX }
        }.clip(CircleShape).background(color)
    )
}

@Composable
private fun MatrixContent(al: Active, engine: AlertEngine, states: Map<String, EntityState>, fs: Float, clock: State<Long>, animate: Boolean) {
    val i18n = App.instance.alerts.i18n
    val green = Color(0xFF00FF41)
    val mono = FontFamily.Monospace
    Text("${i18n.t("alert_system")}  |  ${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))}", Modifier.padding(bottom = 6.dp),
        style = TextStyle(fontFamily = mono, fontSize = (0.72f * 16 * fs).sp, color = green.copy(alpha = 0.65f), letterSpacing = 2.sp))
    Row(Modifier.padding(bottom = 3.dp)) {
        Text(i18n.t("cmd_prefix"), style = TextStyle(fontFamily = mono, fontSize = (0.88f * 16 * fs).sp, color = green))
        Text(" ${i18n.t("cmd_read")}", style = TextStyle(fontFamily = mono, fontSize = (0.88f * 16 * fs).sp, color = green.copy(alpha = 0.45f)))
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(engine.resolve(al.cfg.s("message"), al, states), Modifier.weight(1f, fill = false), style = TextStyle(fontFamily = mono, fontSize = (0.96f * 16 * fs).sp, fontWeight = FontWeight.Bold, color = green))
        Box(Modifier.padding(start = 2.dp, bottom = 3.dp).size(8.dp, 14.dp).graphicsLayer { alpha = if (!animate || phase(clock.value, 1000) < 0.5f) 1f else 0f }.background(green))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TickerSlide(list: List<Active>, engine: AlertEngine, states: Map<String, EntityState>, fs: Float) {
    val i18n = App.instance.alerts.i18n
    Row(Modifier.fillMaxWidth().height(52.dp).background(Color(0xFF111111)).border(1.dp, Color(0xFF333333)), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.fillMaxHeight().background(Color(0xFFE53935)).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(i18n.t("alerts").uppercase(), style = TextStyle(fontSize = (0.68f * 16 * fs).sp, fontWeight = FontWeight.ExtraBold, color = Color.White, letterSpacing = 1.5.sp))
        }
        Text(
            list.joinToString("     ●     ") { a -> "${AtcThemes[a.theme].emoji} ${engine.resolve(a.cfg.s("message"), a, states)}" },
            Modifier.weight(1f).padding(start = 40.dp).basicMarquee(iterations = Int.MAX_VALUE, velocity = 60.dp),
            style = TextStyle(fontSize = (1.05f * 16 * fs).sp, fontWeight = FontWeight.Medium, color = Color(0xFFF0F0F0)),
            maxLines = 1,
        )
    }
}

private fun b3dColor(level: Int): Pair<Color, Color> = when {
    level <= 20 -> Color(0xFFF44336) to Color(0xFFFF7961)
    level <= 60 -> Color(0xFFFF9800) to Color(0xFFFFC947)
    else -> Color(0xFF4CAF50) to Color(0xFF80E27E)
}

@Composable
private fun Battery3dIcon(es: EntityState?) {
    val lvl = es?.state?.toDoubleOrNull()?.takeIf { it in 0.0..100.0 }?.let { Math.round(it).toInt() } ?: 0
    val (c1, c2) = b3dColor(lvl)
    Box(Modifier.size(56.dp, 28.dp).border(2.dp, c1, RoundedCornerShape(5.dp)).padding(4.dp)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(lvl / 100f).clip(RoundedCornerShape(2.dp)).background(Brush.horizontalGradient(listOf(c1, c2))))
    }
}

@Composable
private fun Battery3dBar(es: EntityState) {
    val lvl = es.state.toDoubleOrNull()?.coerceIn(0.0, 100.0)?.let { Math.round(it).toInt() } ?: return
    val (c1, c2) = b3dColor(lvl)
    Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x14FFFFFF))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(lvl / 100f).clip(RoundedCornerShape(3.dp)).background(Brush.horizontalGradient(listOf(c1, c2))))
    }
}

@Composable
private fun TimerRing(timer: AlertEngine.Timer, color: Color) {
    Box(Modifier.size(56.dp).drawBehind {
        val sw = 4.dp.toPx()
        val r = size.minDimension * 22f / 52f
        val tl = Offset(center.x - r, center.y - r)
        drawCircle(Color(0x26808080), r, style = Stroke(sw))
        drawArc(color, -90f, 360f * timer.progress.coerceAtLeast(0f), false, tl, Size(2 * r, 2 * r), style = Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }, contentAlignment = Alignment.Center) {
        Text(timer.remaining, style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = color))
    }
}

// --- Boutons --------------------------------------------------------------------------------------

@Composable
private fun RoundButton(text: String, size: Dp, bg: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(bg).border(1.dp, Color(0x33FFFFFF), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = TextStyle(fontSize = (size.value * 0.5f).sp, color = Color.White)) }
}

/** 💤 : menu de durée, snooze immédiat (durée fixe) ou ✕ pour une alarme persistante. */
@Composable
private fun SnoozeButton(al: Active, cfg: JsonObject, engine: AlertEngine, states: Map<String, EntityState>, size: Dp, large: Boolean) {
    if (al.entity == null && al.group == null) return
    if (!cfg.b("show_snooze_button", true)) return
    val i18n = App.instance.alerts.i18n
    val state = LocalDashState.current
    if (al.cfg.b("persistent")) {
        Box(
            Modifier.size(size).clip(CircleShape).background(Color(0x6B000000)).border(1.dp, Color(0x61FF5050), CircleShape)
                .clickable { engine.dismissPersistent(al, states) },
            contentAlignment = Alignment.Center,
        ) { Text("✕", style = TextStyle(fontSize = (size.value * 0.45f).sp, color = Color(0xE0FF9696), fontWeight = FontWeight.Bold)) }
        return
    }
    val fixed = if (al.cfg.containsKey("snooze_duration")) al.cfg.d("snooze_duration") else cfg.d("snooze_default_duration")
    val action = al.cfg.o("snooze_action")?.takeIf { it.s("action") != null && it.s("action") != "none" }
    var menu by remember { mutableStateOf(false) }
    Box {
        RoundButton("💤", size, if (large) Color(0x80000000) else Color(0x73000000)) {
            action?.let { runAction(state, engine, it, al) }
            if (fixed != null && al.group == null) engine.snooze(al, fixed)
            else if (fixed != null) engine.snooze(al, fixed)
            else menu = true
        }
        DropdownMenu(menu, { menu = false }, containerColor = Color(0xFF1A1A2E), shape = RoundedCornerShape(10.dp)) {
            Text(i18n.t("snooze").uppercase(), Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Color(0x59FFFFFF)))
            listOf(1.0 to "snooze_1h", 4.0 to "snooze_4h", 8.0 to "snooze_8h", 24.0 to "snooze_24h", 168.0 to "snooze_1w", 720.0 to "snooze_1m").forEach { (h, k) ->
                Text(
                    i18n.t(k),
                    Modifier.padding(horizontal = 6.dp, vertical = 2.dp).widthIn(min = 110.dp).clip(RoundedCornerShape(6.dp))
                        .background(Color(0x0FFFFFFF)).clickable { menu = false; engine.snooze(al, h) }.padding(horizontal = 10.dp, vertical = 8.dp),
                    style = TextStyle(fontSize = 14.sp, color = Color(0xD9FFFFFF)),
                )
            }
        }
    }
}

@Composable
private fun StatusBar(icon: String, text: String, reset: String, shape: androidx.compose.ui.graphics.Shape, onReset: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Color(0xFF1A1A2E)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, fontSize = 18.sp)
        Text(text, Modifier.weight(1f).padding(start = 10.dp), color = Color(0xB3FFFFFF), fontSize = 13.sp)
        Text(reset, Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onReset).padding(horizontal = 10.dp, vertical = 4.dp), color = Color(0xFFFFC850), fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

// --- Historique -----------------------------------------------------------------------------------

@Composable
private fun HistoryView(cardKey: String, shape: androidx.compose.ui.graphics.Shape, onClose: () -> Unit) {
    val hub = App.instance.alerts
    val i18n = hub.i18n
    hub.store.version.collectAsState().value
    val entries = hub.store.history(cardKey)
    val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    Column(Modifier.fillMaxWidth().clip(shape).background(Color(0xFF12121E)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(i18n.t("history"), Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(i18n.t("history_clear"), Modifier.clip(RoundedCornerShape(8.dp)).clickable { hub.store.clearHistory(cardKey) }.padding(horizontal = 8.dp, vertical = 4.dp), color = Color(0xFFFF8A80), fontSize = 12.sp)
            Text("✕", Modifier.clip(CircleShape).clickable(onClick = onClose).padding(horizontal = 10.dp, vertical = 4.dp), color = Color.White, fontSize = 14.sp)
        }
        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(top = 6.dp)) {
            if (entries.isEmpty()) Text(i18n.t("history_empty"), Modifier.padding(8.dp), color = Color(0x80FFFFFF), fontSize = 13.sp)
            entries.forEach { h ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Text(h.icon, fontSize = 16.sp)
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(h.message, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        if (h.entityName != null) Text(h.entityName + (h.entityState?.let { "  $it" } ?: ""), color = Color(0xA6FFFFFF), fontSize = 11.sp)
                        if (h.secondaryName != null) Text(h.secondaryName + (h.secondaryState?.let { "  $it" } ?: ""), color = Color(0x80FFFFFF), fontSize = 11.sp)
                        Text(fmt.format(java.time.Instant.ofEpochMilli(h.ts).atZone(java.time.ZoneId.systemDefault())), color = Color(0x66FFFFFF), fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

// --- « Tout va bien » -----------------------------------------------------------------------------

@Composable
private fun ClearView(cfg: JsonObject, engine: AlertEngine, states: Map<String, EntityState>, clock: State<Long>, shape: androidx.compose.ui.graphics.Shape, noAnim: Boolean) {
    val i18n = App.instance.alerts.i18n
    val mode = cfg.s("clear_display_mode") ?: "message"
    if (mode != "message") {
        AtcClearWidget(cfg, mode, states, clock, shape, noAnim)
        return
    }
    val alert = Active(
        kotlinx.serialization.json.buildJsonObject {
            put("message", kotlinx.serialization.json.JsonPrimitive(cfg.s("clear_message")?.takeIf { it.isNotEmpty() } ?: i18n.t("all_clear")))
            put("icon", kotlinx.serialization.json.JsonPrimitive(cfg.s("clear_icon") ?: "✅"))
            put("theme", kotlinx.serialization.json.JsonPrimitive(cfg.s("clear_theme") ?: "success"))
            put("priority", kotlinx.serialization.json.JsonPrimitive(0))
            cfg.s("clear_badge_label")?.let { put("badge_label", kotlinx.serialization.json.JsonPrimitive(it)) }
            cfg.s("clear_icon_size")?.let { put("icon_size", kotlinx.serialization.json.JsonPrimitive(it)) }
            cfg.s("clear_icon_color")?.let { put("icon_color", kotlinx.serialization.json.JsonPrimitive(it)) }
        }, -1, null,
    )
    val state = LocalDashState.current
    val tap = cfg.o("clear_tap_action")
    val hold = cfg.o("clear_hold_action")
    val dbl = cfg.o("clear_double_tap_action")
    @OptIn(ExperimentalFoundationApi::class)
    Box(if (listOf(tap, hold, dbl).any { it != null }) Modifier.combinedClickable(
        onClick = { runAction(state, engine, tap, alert) }, onLongClick = hold?.let { { runAction(state, engine, it, alert) } }, onDoubleClick = dbl?.let { { runAction(state, engine, it, alert) } },
    ) else Modifier) {
        AtcSlide(alert, cfg, engine, states, clock, shape, null, 0.dp, (cfg.d("font_scale") ?: 1.0).toFloat(), !noAnim)
    }
}

/** Bannière overlay affichée sur les autres écrans quand une alerte se déclenche. */
@Composable
fun BoxScope.AlertOverlayHost() {
    val state = LocalDashState.current
    val hub = App.instance.alerts
    var current by remember { mutableStateOf<OverlayAlert?>(null) }
    var idx by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        hub.overlay.collect { ev ->
            val screen = hub.cards.value[ev.cardKey]?.first
            if (screen != state.screen) { current = ev; idx = 0 }
        }
    }
    val ev = current ?: return
    val cfg = ev.config
    val duration = cfg.d("overlay_duration") ?: 10.0
    val dismissible = cfg.b("overlay_dismissible", true)
    LaunchedEffect(ev) { if (duration > 0 && dismissible) { delay((duration * 1000).toLong()); if (current == ev) current = null } }
    LaunchedEffect(ev, cfg.b("overlay_rotation")) {
        if (cfg.b("overlay_rotation") && ev.alerts.size > 1) while (true) { delay(((cfg.d("overlay_rotation_interval") ?: 5.0) * 1000).toLong()); idx++ }
    }
    val al = ev.alerts[Math.floorMod(idx, ev.alerts.size)]
    val states by state.ha.states.collectAsState()
    val clock = rememberAnimClock(!cfg.b("disable_animation"))
    val scale = (cfg.d("overlay_scale") ?: 1.0).toFloat()
    val align = when (cfg.s("overlay_position")) { "bottom" -> Alignment.BottomCenter; "center" -> Alignment.Center; else -> Alignment.TopCenter }
    Box(Modifier.align(align).padding(24.dp).widthIn(max = (560 * scale).dp)) {
        Column {
            AtcSlide(al, cfg, hub.engine(ev.cardKey), states, clock, RoundedCornerShape(14.dp),
                if (ev.alerts.size > 1) (Math.floorMod(idx, ev.alerts.size) + 1) to ev.alerts.size else null, if (dismissible) 44.dp else 0.dp, scale, true)
            al.cfg.s("camera_entity")?.let { cam ->
                val pic = states[cam]?.attrString("entity_picture")
                if (pic != null) AsyncImage(state.ha.resolveUrl(pic), null, Modifier.padding(top = 6.dp).fillMaxWidth().height(200.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
            }
        }
        if (dismissible) Box(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp)) { RoundButton("✕", 30.dp, Color(0x80000000)) { current = null } }
    }
}
