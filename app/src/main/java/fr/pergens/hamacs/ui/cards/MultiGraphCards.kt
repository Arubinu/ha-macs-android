package fr.pergens.hamacs.ui.cards

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.pergens.hamacs.config.Card
import fr.pergens.hamacs.config.b
import fr.pergens.hamacs.config.i
import fr.pergens.hamacs.config.objs
import fr.pergens.hamacs.config.s
import fr.pergens.hamacs.ha.Fmt
import fr.pergens.hamacs.ha.history
import fr.pergens.hamacs.ui.LocalDashState
import fr.pergens.hamacs.ui.components.DashCard
import fr.pergens.hamacs.ui.components.editOutline
import fr.pergens.hamacs.ui.theme.Dash
import fr.pergens.hamacs.ui.theme.parseCssColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.max

/** Couleurs par défaut de mini-graph-card (`line_color`), par position de l'entité ; la 1re est l'accent. */
private val MGC_COLORS = listOf(
    null, "#3498db", "#e74c3c", "#9b59b6", "#f1c40f", "#2ecc71", "#1abc9c", "#34495e", "#e67e22", "#7f8c8d",
    "#27ae60", "#2980b9", "#8e44ad",
)

/** Couleur d'une courbe : `color` de l'entité, sinon `line_color` de la carte, sinon palette par position. */
internal fun mgcColor(card: JsonObject, entity: JsonObject, index: Int, accent: Color): Color =
    parseCssColor(entity.s("color")) ?: (card["line_color"] as? kotlinx.serialization.json.JsonArray)?.getOrNull(index)
        ?.let { parseCssColor((it as? JsonPrimitive)?.content) }
    ?: MGC_COLORS.getOrNull(index % MGC_COLORS.size)?.let { parseCssColor(it) } ?: accent

/** Entité d'une mini-graph-card multi-entités (`entities`), avec ses options. */
private class MgEntity(val o: JsonObject, val index: Int) {
    val id: String = o.s("entity") ?: ""
    val showGraph get() = o["show_graph"]?.let { (it as? JsonPrimitive)?.content != "false" } ?: true
    val showLine get() = o["show_line"]?.let { (it as? JsonPrimitive)?.content != "false" } ?: true
    val showLegend get() = o["show_legend"]?.let { (it as? JsonPrimitive)?.content != "false" } ?: true
    val secondary get() = o.s("y_axis") == "secondary"
    val smoothing get() = o["smoothing"]?.let { (it as? JsonPrimitive)?.content != "false" }
}

/** Série échantillonnée d'une entité : une valeur (ou null) par tranche. */
private class MgSeries(val values: List<Double?>)

/**
 * mini-graph-card à plusieurs entités (`entities`) : nom de la carte, valeur de la 1re entité en grand,
 * légende, une courbe par entité (couleurs de la carte d'origine), remplissage, axe secondaire (ex. capteur
 * jour/nuit converti par `state_map`), agrégation par tranche (`aggregate_func`, `points_per_hour`).
 */
@Composable
internal fun MiniGraphMulti(card: Card, modifier: Modifier) {
    val state = LocalDashState.current
    val entities = (card["entities"] as? kotlinx.serialization.json.JsonArray)?.mapIndexedNotNull { i, e ->
        when (e) {
            is JsonObject -> MgEntity(e, i)
            is JsonPrimitive -> MgEntity(JsonObject(mapOf("entity" to e)), i)
            else -> null
        }
    }?.filter { it.id.isNotEmpty() } ?: return
    if (entities.isEmpty()) return
    val first = entities.first()
    val hours = card.i("hours_to_show", card.i("hours", 24))
    val pph = (card["points_per_hour"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.5
    val buckets = (hours * pph).toInt().coerceIn(2, 500)
    val agg = card.s("aggregate_func") ?: card.s("aggregate", "avg")
    val decimals = card.i("decimals", 0)
    val stateMap = card.objs("state_map").associate { (it.s("value") ?: "") to ((it["y_value"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0) }
    val show = card["show"] as? JsonObject
    fun showFlag(k: String, def: Boolean) = (show?.get(k) as? JsonPrimitive)?.content?.let { it != "false" } ?: def
    val fill = showFlag("fill", true)
    val graphed = entities.filter { it.showGraph }
    val ids = graphed.map { it.id }.distinct()

    var series by remember(card) { mutableStateOf<Map<String, MgSeries>>(emptyMap()) }
    LaunchedEffect(ids, hours, buckets, agg) {
        cachedLoop("mgm:${ids.joinToString(",")}:$hours:$buckets:$agg", 5 * 60_000L, { series = it }) {
            val hist = state.ha.history(ids, hours)
            withContext(Dispatchers.Default) {
                val end = System.currentTimeMillis()
                val t0 = end - hours * 3_600_000L
                val span = (end - t0) / buckets
                ids.associateWith { id ->
                    val spans = hist[id].orEmpty().sortedBy { it.start }
                    fun num(s: String) = s.toDoubleOrNull() ?: stateMap[s] ?: when (s) {
                        // Bande jour / nuit sans state_map : soleil (sun.sun) ou capteur binaire.
                        "above_horizon", "on" -> 1.0
                        "below_horizon", "off" -> 0.0
                        else -> null
                    }
                    // Chaque tranche : valeur en vigueur à son début, plus les valeurs prises pendant la tranche.
                    var carry: Double? = null
                    var k = 0
                    MgSeries(List(buckets) { b ->
                        val from = t0 + b * span
                        val to = from + span
                        while (k < spans.size && spans[k].start <= from) { carry = num(spans[k].state); k++ }
                        val all = mutableListOf<Double>()
                        carry?.let { all += it }
                        while (k < spans.size && spans[k].start < to) {
                            carry = num(spans[k].state)
                            carry?.let { all += it }
                            k++
                        }
                        when {
                            all.isEmpty() -> null
                            agg == "min" -> all.min()
                            agg == "max" -> all.max()
                            agg == "last" -> all.last()
                            agg == "first" -> all.first()
                            else -> all.average()
                        }
                    })
                }
            }
        }
    }

    fun colorOf(e: MgEntity, accent: Color): Color = mgcColor(card, e.o, e.index, accent)

    val accent = Dash.colors.accent
    // `state_from: min | max` : la valeur affichée est la plus basse (ou haute) des courbes de l'axe principal,
    // avec son nom (ex. meilleur prix parmi plusieurs stations) — sans capteur calculé dans Home Assistant.
    val stateFrom = card.s("state_from")?.takeIf { it == "min" || it == "max" }
    val allStates = state.ha.states.collectAsState()
    val best by remember(card, stateFrom) {
        androidx.compose.runtime.derivedStateOf {
            if (stateFrom == null) null else {
                val st = allStates.value
                val candidates = graphed.filter { !it.secondary }.mapNotNull { e ->
                    st[e.id]?.takeIf { !it.isUnavailable }?.numeric?.let { v -> e to v }
                }
                if (stateFrom == "min") candidates.minByOrNull { it.second } else candidates.maxByOrNull { it.second }
            }
        }
    }
    val firstState = fr.pergens.hamacs.ui.entity(first.id)
    val current = best?.let { allStates.value[it.first.id] } ?: firstState
    val bestName = best?.first?.let { it.o.s("name") ?: allStates.value[it.id]?.name }
    val lineWidth = card.i("line_width", 5)
    val fadeEdges = card.b("fade_edges")
    // Clé propre à cette carte (hauteur, titre, courbes), indépendante des entités partagées avec d'autres cartes.
    val key = card.s("_key") ?: fr.pergens.hamacs.config.BlockOverrides.multiGraphKey(card) ?: first.id
    // Plusieurs courbes sur l'axe principal : un toucher ouvre leur détail (valeurs, écarts, derniers changements).
    val primary = graphed.filter { !it.secondary }
    var details by remember { mutableStateOf(false) }
    if (details) GraphDetailsSheet(
        card.s("name") ?: current?.name ?: first.id,
        primary.map { e -> DetailLine(e.id, e.o.s("name") ?: allStates.value[e.id]?.name ?: e.id, colorOf(e, accent)) },
        stateFrom, decimals,
    ) { details = false }

    DashCard(
        modifier.fillMaxWidth().height(graphHeight(card, key, 220).dp).graphEdit(card, key, 220)
            .then(if (!state.editMode && primary.size > 1) Modifier.clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null,
            ) { state.lastInteraction = System.currentTimeMillis(); details = true } else Modifier)
            .then(if (fadeEdges) Modifier.fadeEdges() else Modifier),
        background = card.s("background")?.let { parseCssColor(it) } ?: Dash.colors.cardBackground,
    ) {
        Column(Modifier.fillMaxSize().padding(top = 16.dp)) {
            // En-tête : nom de la carte, puis valeur de la 1re entité.
            if (showFlag("name", true)) Text(
                card.s("name") ?: current?.name ?: first.id, Modifier.padding(horizontal = 16.dp),
                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.65f)),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (showFlag("state", true)) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
                Text(
                    current?.numeric?.let { Fmt.number(it, decimals) } ?: current?.state ?: "—",
                    style = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Light, color = Color.White),
                )
                Text(
                    current?.unit ?: "", Modifier.padding(start = 4.dp, bottom = 5.dp),
                    style = TextStyle(fontSize = 20.sp, color = Color.White.copy(alpha = 0.6f)),
                )
                if (bestName != null) Text(
                    "· $bestName", Modifier.padding(start = 8.dp, bottom = 6.dp),
                    style = TextStyle(fontSize = 16.sp, color = Color.White.copy(alpha = 0.6f)),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            // Légende (au-dessus de la courbe, comme mini-graph-card), seulement s'il y a plusieurs courbes.
            val legend = graphed.filter { it.showLegend }
            if (showFlag("legend", true) && legend.size > 1) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.Center) {
                    legend.forEach { e ->
                        Row(Modifier.padding(horizontal = 5.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(colorOf(e, accent)))
                            Text(
                                e.o.s("name") ?: fr.pergens.hamacs.ui.entity(e.id)?.name ?: e.id, Modifier.padding(start = 5.dp),
                                style = TextStyle(fontSize = 13.sp, color = Color.White.copy(alpha = 0.75f)), maxLines = 1,
                            )
                        }
                    }
                }
            }
            // Courbes
            Spacer(Modifier.fillMaxWidth().weight(1f).drawWithCache {
                val w = size.width
                val h = size.height
                val stroke = lineWidth * (w / 500f)
                val pad = stroke
                fun bounds(list: List<MgEntity>): Pair<Double, Double>? {
                    val vs = list.flatMap { series[it.id]?.values.orEmpty().filterNotNull() }
                    if (vs.isEmpty()) return null
                    val lo = vs.min()
                    val hi = vs.max()
                    return if (abs(hi - lo) < 1e-9) (lo - 1) to (hi + 1) else lo to hi
                }
                val prim = bounds(graphed.filter { !it.secondary })
                val sec = bounds(graphed.filter { it.secondary })
                class Drawn(val color: Color, val line: Path?, val area: Path?)
                val drawn = graphed.mapNotNull { e ->
                    val vals = series[e.id]?.values ?: return@mapNotNull null
                    val (lo, hi) = (if (e.secondary) sec else prim) ?: return@mapNotNull null
                    val n = vals.size
                    val pts = vals.mapIndexedNotNull { i, v ->
                        v?.let { Offset(if (n <= 1) 0f else i * w / (n - 1), (h - pad - ((it - lo) / (hi - lo)).toFloat() * (h - 2 * pad))) }
                    }
                    if (pts.size < 2) return@mapNotNull null
                    val smooth = e.smoothing ?: (card["smoothing"] as? JsonPrimitive)?.content?.let { it != "false" } ?: true
                    val line = if (smooth) smoothPathPublic(pts) else Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
                    val area = if (fill) Path().apply { addPath(line); lineTo(pts.last().x, h); lineTo(pts.first().x, h); close() } else null
                    Drawn(colorOf(e, accent), if (e.showLine) line else null, area)
                }
                onDrawBehind {
                    // Remplissages d'abord (opacité 0,15 comme mini-graph-card), puis les courbes par-dessus.
                    drawn.forEach { d -> d.area?.let { drawPath(it, d.color.copy(alpha = 0.15f)) } }
                    drawn.forEach { d -> d.line?.let { drawPath(it, d.color, style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)) } }
                }
            })
        }
    }
}

/** Fondu des bords gauche/droit (3 %) et bas (3 %), équivalent du `mask-image` du card_mod d'origine. */
internal fun Modifier.fadeEdges(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            Brush.horizontalGradient(0f to Color.Transparent, 0.03f to Color.Black, 0.97f to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
        drawRect(Brush.verticalGradient(0.97f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
    }

/**
 * simple-swipe-card : défilement automatique (`enable_auto_swipe`, `auto_swipe_interval`), glissement au doigt,
 * effet de transition (`swipe_effect` : slide, fade, instant) et indicateur à points (pagination).
 */
@Composable
fun SwipeCard(card: Card, modifier: Modifier) {
    val cards = card.objs("cards")
    if (cards.isEmpty()) return
    var index by remember { mutableIntStateOf(0) }
    var dir by remember { mutableIntStateOf(1) }
    var lastUser by remember { mutableLongStateOf(0L) }
    val auto = card["enable_auto_swipe"]?.let { (it as? JsonPrimitive)?.content != "false" } ?: (card["interval"] != null)
    val interval = card.i("auto_swipe_interval", card.i("interval", 5000)).toLong().coerceAtLeast(1000)
    fun go(to: Int, d: Int) { dir = d; index = Math.floorMod(to, cards.size) }
    // En mode édition, pas de défilement automatique : on choisit la carte à régler avec les points ou au doigt.
    val state = LocalDashState.current
    val editing = state.editMode
    // Carte affichée (bornée : la liste peut raccourcir quand on retire une carte).
    val shown = Math.floorMod(index, cards.size)
    LaunchedEffect(cards.size, auto, interval, lastUser, editing) {
        if (!auto || cards.size < 2 || editing) return@LaunchedEffect
        while (true) { delay(interval); go(index + 1, 1) }
    }
    val effect = card.s("swipe_effect", "slide")
    Box(
        modifier.fillMaxWidth().pointerInput(cards.size) {
            var total = 0f
            detectHorizontalDragGestures(
                onDragStart = { total = 0f },
                onDragEnd = {
                    if (abs(total) > 60.dp.toPx()) { lastUser = System.currentTimeMillis(); go(index + if (total < 0) 1 else -1, if (total < 0) 1 else -1) }
                },
            ) { _, dx -> total += dx }
        },
    ) {
        AnimatedContent(
            targetState = shown,
            transitionSpec = {
                when (effect) {
                    "instant" -> fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    "fade" -> fadeIn(tween(400)) togetherWith fadeOut(tween(400))
                    else -> slideInHorizontally(tween(350)) { w -> w * dir } togetherWith slideOutHorizontally(tween(350)) { w -> -w * dir }
                }
            },
            label = "swipe",
        ) { RenderCard(cards.getOrNull(it) ?: cards[0]) }
        // Indicateur à points (simple-swipe-card) : point actif de la couleur principale.
        if (cards.size > 1 && card["show_pagination"]?.let { (it as? JsonPrimitive)?.content != "false" } != false) Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            cards.indices.forEach { i ->
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(if (i == shown) Dash.colors.accent else Color(127, 127, 127, 153))
                        .clickable { lastUser = System.currentTimeMillis(); go(i, if (i > index) 1 else -1) },
                )
            }
        }
        // Mode édition : un toucher ouvre d'abord le carrousel (liste de ses cartes), puis la carte choisie.
        if (editing) {
            val key = card.s("_key") ?: fr.pergens.hamacs.config.BlockOverrides.carouselKey(card)
            if (key != null) Box(
                Modifier.matchParentSize()
                    .then(Modifier.editOutline(true, androidx.compose.foundation.shape.RoundedCornerShape(Dash.shapes.cardRadius)))
                    .clickable { state.editTarget = fr.pergens.hamacs.ui.EditTarget.Swipe(key) },
            )
        }
    }
}
