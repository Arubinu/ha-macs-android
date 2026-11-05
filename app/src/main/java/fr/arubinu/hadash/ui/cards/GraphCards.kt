package fr.arubinu.hadash.ui.cards

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.drawWithCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.res.stringResource
import fr.arubinu.hadash.App
import fr.arubinu.hadash.R
import fr.arubinu.hadash.config.Card
import fr.arubinu.hadash.config.b
import fr.arubinu.hadash.config.i
import fr.arubinu.hadash.config.objs
import fr.arubinu.hadash.config.s
import fr.arubinu.hadash.ha.Fmt
import fr.arubinu.hadash.ha.StatPoint
import fr.arubinu.hadash.ha.history
import fr.arubinu.hadash.ha.statistics
import fr.arubinu.hadash.ui.LocalDashState
import fr.arubinu.hadash.ui.components.editOutline
import androidx.compose.foundation.clickable
import fr.arubinu.hadash.ui.components.DashCard
import fr.arubinu.hadash.ui.entity
import fr.arubinu.hadash.ui.theme.Dash
import fr.arubinu.hadash.ui.theme.parseCssColor
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** Hauteur d'un graphique : réglage du mode édition, sinon `height` de la config, sinon [default]. */
@Composable
internal fun graphHeight(card: Card, entity: String, default: Int): Int {
    val blocks by App.instance.config.blocks.collectAsState()
    return blocks["graph:${card.s("type")}:$entity"]?.get("height")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() }
        ?: card.i("height", default)
}

/** Mode édition : contour pointillé, et un toucher ouvre le réglage de hauteur du graphique. */
@Composable
internal fun Modifier.graphEdit(card: Card, entity: String, default: Int): Modifier {
    val state = LocalDashState.current
    if (!state.editMode) return this
    return this.editOutline(true, androidx.compose.foundation.shape.RoundedCornerShape(Dash.shapes.cardRadius))
        .clickable { state.editTarget = fr.arubinu.hadash.ui.EditTarget.Graph("graph:${card.s("type")}:$entity", entity, card.i("height", default)) }
}

/** Courbe lissée (spline monotone simplifiée) passant par les points. */
private fun smoothPath(pts: List<Offset>, close: Float? = null): Path {
    val p = Path()
    if (pts.isEmpty()) return p
    p.moveTo(pts[0].x, pts[0].y)
    for (i in 1 until pts.size) {
        val a = pts[i - 1]
        val b = pts[i]
        val mx = (a.x + b.x) / 2
        p.cubicTo(mx, a.y, mx, b.y, b.x, b.y)
    }
    if (close != null) {
        p.lineTo(pts.last().x, close)
        p.lineTo(pts.first().x, close)
        p.close()
    }
    return p
}

private fun niceStep(range: Double, ticks: Int): Double {
    val raw = range / ticks
    val mag = 10.0.pow(kotlin.math.floor(log10(raw)))
    val n = raw / mag
    return mag * when {
        n <= 1 -> 1.0
        n <= 2 -> 2.0
        n <= 5 -> 5.0
        else -> 10.0
    }
}

private val textPaint = android.graphics.Paint().apply { isAntiAlias = true }

private fun DrawScope.text(s: String, x: Float, y: Float, color: Color, sizeSp: Float, align: android.graphics.Paint.Align) {
    textPaint.color = android.graphics.Color.argb((color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
    textPaint.textSize = sizeSp * density * fontScale
    textPaint.textAlign = align
    drawContext.canvas.nativeCanvas.drawText(s, x, y, textPaint)
}

/**
 * Dernières données des graphiques, conservées entre deux affichages de l'onglet : le retour sur
 * l'onglet est instantané et la requête n'est relancée que si les données ont expiré.
 */
private object GraphCache {
    private val entries = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Any>>()

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): Pair<Long, T>? = entries[key] as Pair<Long, T>?

    fun put(key: String, value: Any) {
        entries[key] = System.currentTimeMillis() to value
    }
}

/** Boucle de rafraîchissement qui reprend le cache et n'interroge HA qu'une fois les données expirées. */
internal suspend fun <T : Any> cachedLoop(key: String, periodMs: Long, onValue: (T) -> Unit, load: suspend () -> T) {
    GraphCache.get<T>(key)?.let { (at, v) ->
        onValue(v)
        delay((at + periodMs - System.currentTimeMillis()).coerceAtLeast(0))
    }
    while (true) {
        runCatching { load() }.onSuccess { GraphCache.put(key, it); onValue(it) }
        delay(periodMs)
    }
}

/**
 * Consommation instantanée (équivalent du plotly-graph) : enveloppe min/max sur 5 min,
 * moyenne lissée, et bande jour/nuit dérivée de sun.sun sur l'axe secondaire.
 */
@Composable
fun PowerGraphCard(card: Card, modifier: Modifier) {
    val state = LocalDashState.current
    val id = card.s("entity") ?: return
    val hours = card.i("hours", 48)
    val color = fr.arubinu.hadash.ui.theme.configColor(card.s("color"), Dash.colors.accent.copy(alpha = 0.8f))
    var data by remember(id) { mutableStateOf<PowerData?>(null) }
    val current = entity(id)
    LaunchedEffect(id, hours) {
        cachedLoop("power:$id:$hours", card.i("refresh", 60) * 1000L, { data = it }) {
            val stats = state.ha.statistics(id, hours, "5minute")
            val sun = runCatching {
                state.ha.history(listOf("sun.sun"), hours)["sun.sun"].orEmpty().map { it.start to (it.state == "above_horizon") }
            }.getOrDefault(emptyList())
            PowerData(System.currentTimeMillis(), stats, sun)
        }
    }
    val zone = ZoneId.systemDefault()
    val dayLabel = stringResource(R.string.graph_day)
    val nightLabel = stringResource(R.string.graph_night)
    val currentLabel = current?.numeric?.let { "${it.toInt()} W" }

    DashCard(modifier.fillMaxWidth().height(graphHeight(card, id, 300).dp).graphEdit(card, id, 300).then(if (card.b("fade_edges")) Modifier.fadeEdges() else Modifier), padding = 12.dp) {
        // Les tracés ne sont recalculés que si les données ou la taille changent, pas à chaque image
        // (animation de changement d'onglet notamment).
        Spacer(Modifier.fillMaxSize().drawWithCache {
            val d = data
            if (d == null || d.stats.isEmpty()) return@drawWithCache onDrawBehind {}
            val stats = d.stats
            val sun = d.sun
            val left = 46.dp.toPx()
            val right = 62.dp.toPx()
            val bottom = 28.dp.toPx()
            val w = size.width - left - right
            val h = size.height - bottom - 8.dp.toPx()
            val now = d.at
            val t0 = now - hours * 3_600_000L
            val maxV = stats.maxOf { it.max ?: it.mean ?: 0.0 }.coerceAtLeast(1.0)
            val step = niceStep(maxV, 4)
            val top = ceil(maxV / step) * step
            fun x(t: Long) = left + ((t - t0).toFloat() / (now - t0)) * w
            fun y(v: Double) = 8.dp.toPx() + h - (v / top).toFloat() * h

            // Bande jour/nuit (sun.sun) sur l'axe secondaire : une « cloche » par journée, du lever au coucher.
            val sunPaths = if (sun.isEmpty()) null else {
                val days = sun.mapIndexedNotNull { i, (t, up) ->
                    if (!up) null else t to (sun.getOrNull(i + 1)?.first ?: (t + 10 * 3_600_000L))
                }.toMutableList()
                // Journée en cours ou suivante : décalage de 24 h de la précédente (comme le filtre plotly d'origine).
                days.lastOrNull()?.let { (s, e) -> if (e < now) days += (s + 86_400_000L) to (e + 86_400_000L) }
                val samples = (0..240).map { i ->
                    val t = t0 + i * (now - t0) / 240
                    val day = days.firstOrNull { t in it.first..it.second }
                    // sqrt(sin) : plateau en journée et transitions douces, proche du rendu plotly d'origine.
                    val v = day?.let { (s, e) -> kotlin.math.sqrt(kotlin.math.sin(Math.PI * (t - s) / (e - s).coerceAtLeast(1))).toFloat() } ?: 0f
                    Offset(x(t), 8.dp.toPx() + h - v * h * 0.95f)
                }
                smoothPath(samples, 8.dp.toPx() + h) to smoothPath(samples)
            }

            // Enveloppe min/max
            val env = Path()
            stats.forEachIndexed { i, p ->
                val px = x(p.t)
                val py = y(p.max ?: p.mean ?: 0.0)
                if (i == 0) env.moveTo(px, py) else env.lineTo(px, py)
            }
            stats.asReversed().forEach { p -> env.lineTo(x(p.t), y(p.min ?: p.mean ?: 0.0)) }
            env.close()

            // Moyenne lissée
            val mean = stats.mapNotNull { p -> p.mean?.let { Offset(x(p.t), y(it)) } }
            val meanPath = smoothPath(mean)
            val stroke = Stroke(2.dp.toPx())

            // Axe X : graduations toutes les 3/6/12 h selon la place disponible (≥ 56 dp entre libellés).
            val fmt = DateTimeFormatter.ofPattern("HH:mm")
            val stepH = listOf(3, 6, 12, 24).first { s -> w / (hours / s.toFloat()) >= 56.dp.toPx() }
            val xTicks = mutableListOf<Pair<Float, String>>()
            var local = Instant.ofEpochMilli(t0).atZone(zone).withMinute(0).withSecond(0).withNano(0)
            local = local.withHour(local.hour / stepH * stepH).plusHours(stepH.toLong())
            while (local.toInstant().toEpochMilli() < now) {
                val tx = x(local.toInstant().toEpochMilli())
                if (tx > left) xTicks += tx to fmt.format(local)
                local = local.plusHours(stepH.toLong())
            }

            onDrawBehind {
                // Grille + axe Y
                var v = 0.0
                while (v <= top + 1e-6) {
                    drawLine(Color(255, 255, 255, 25), Offset(left, y(v)), Offset(left + w, y(v)), 1f)
                    text(v.toInt().toString(), left - 8.dp.toPx(), y(v) + 4.dp.toPx(), Color.White, 12f, android.graphics.Paint.Align.RIGHT)
                    v += step
                }
                sunPaths?.let { (fill, line) ->
                    drawPath(fill, Color(255, 255, 255, 25))
                    drawPath(line, Color(255, 255, 255, 64), style = stroke)
                    text(dayLabel, size.width, 8.dp.toPx() + h * 0.05f + 4.dp.toPx(), Color.White, 12f, android.graphics.Paint.Align.RIGHT)
                    text(nightLabel, size.width, 8.dp.toPx() + h + 4.dp.toPx(), Color.White, 12f, android.graphics.Paint.Align.RIGHT)
                }
                drawPath(env, color.copy(alpha = 0.25f))
                drawPath(meanPath, color, style = stroke)

                // Valeur actuelle
                mean.lastOrNull()?.let { last ->
                    drawCircle(color, 3.dp.toPx(), last)
                    val label = currentLabel ?: "${stats.last().mean?.toInt()} W"
                    text(label, last.x + 6.dp.toPx(), last.y + 4.dp.toPx(), color, 13f, android.graphics.Paint.Align.LEFT)
                }
                xTicks.forEach { (tx, s) -> text(s, tx, size.height - 6.dp.toPx(), Color.White, 12f, android.graphics.Paint.Align.CENTER) }
            }
        })
    }
}

private class PowerData(val at: Long, val stats: List<StatPoint>, val sun: List<Pair<Long, Boolean>>)

private class MiniData(val points: List<Pair<Long, Double>>, val extrema: Pair<Pair<Long, Double>, Pair<Long, Double>>?)

/**
 * mini-graph-card : valeur actuelle en grand, extrema avec heure, courbe lissée avec remplissage
 * en fondu. Utilise l'historique (ou les statistiques horaires pour les longues périodes).
 */
@Composable
fun MiniGraphCard(card: Card, modifier: Modifier) {
    // Plusieurs entités (`entities`) : rendu fidèle de mini-graph-card multi-courbes.
    if (card["entities"] != null) { MiniGraphMulti(card, modifier); return }
    val state = LocalDashState.current
    val id = card.s("entity") ?: return
    val hours = card.i("hours", 24)
    val decimals = card.i("decimals", 0)
    val color = fr.arubinu.hadash.ui.theme.configColor(card.s("color"), Dash.colors.accent)
    val current = entity(id)
    var data by remember(id) { mutableStateOf(MiniData(emptyList(), null)) }
    val buckets = card.i("points", 24).coerceAtLeast(2)
    val agg = card.s("aggregate", "avg")
    LaunchedEffect(id, hours, buckets, agg) {
        cachedLoop("mini:$id:$hours:$buckets:$agg", 5 * 60_000L, { data = it }) {
            val spans = state.ha.history(listOf(id), hours)[id].orEmpty()
            withContext(Dispatchers.Default) {
                val raw = spans.mapNotNull { s -> s.state.toDoubleOrNull()?.let { s.start to it } }
                // Extrema calculés sur les valeurs brutes (comme mini-graph-card), pas sur les moyennes par tranche.
                val extrema = if (raw.isEmpty()) null else raw.minBy { it.second } to raw.maxBy { it.second }
                // Regroupe par tranches comme mini-graph-card (points_per_hour = 0.5 par défaut sur 24 h).
                val t0 = System.currentTimeMillis() - hours * 3_600_000L
                val span = hours * 3_600_000L / buckets
                val points = raw.groupBy { ((it.first - t0).coerceAtLeast(0) / span) }.toSortedMap().map { (b, vs) ->
                    val values = vs.map { it.second }
                    (t0 + b * span + span / 2) to when (agg) {
                        "min" -> values.min()
                        "max" -> values.max()
                        else -> values.average()
                    }
                }
                MiniData(points, extrema)
            }
        }
    }
    val points = data.points
    val extrema = data.extrema
    val unit = current?.unit ?: ""
    val fmtTime = DateTimeFormatter.ofPattern("HH:mm")
    val zone = ZoneId.systemDefault()

    DashCard(modifier.fillMaxWidth().height(graphHeight(card, id, 220).dp).graphEdit(card, id, 220).then(if (card.b("fade_edges")) Modifier.fadeEdges() else Modifier), background = card.s("background")?.let { parseCssColor(it) } ?: Dash.colors.cardBackground) {
        Spacer(Modifier.fillMaxSize().drawWithCache {
            if (points.size < 2) return@drawWithCache onDrawBehind {}
            val minV = points.minOf { it.second }
            val maxV = points.maxOf { it.second }
            val range = max(maxV - minV, 1e-6)
            val t0 = points.first().first
            val t1 = points.last().first
            val top = size.height * 0.45f
            val bottom = size.height
            val pts = points.map { (t, v) ->
                Offset(((t - t0).toFloat() / (t1 - t0).coerceAtLeast(1)) * size.width, bottom - 8.dp.toPx() - ((v - minV) / range).toFloat() * (bottom - top - 16.dp.toPx()))
            }
            val fill = smoothPath(pts, bottom)
            val line = smoothPath(pts)
            val brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0.05f)), startY = top, endY = bottom)
            val stroke = Stroke(card.i("line_width", 2).dp.toPx() * 2)
            onDrawBehind {
                drawPath(fill, brush)
                drawPath(line, color, style = stroke)
            }
        })
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            card.s("name")?.takeIf { card.s("show_name") != "false" }?.let {
                Text(it, style = TextStyle(fontSize = 14.sp, color = Color(255, 255, 255, 180), fontWeight = FontWeight.Medium, letterSpacing = 1.5.sp))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Fmt.number(current?.numeric, decimals), style = TextStyle(fontSize = 34.sp, color = Color(255, 255, 255, 220)))
                Text(unit, Modifier.padding(start = 4.dp, bottom = 4.dp), style = TextStyle(fontSize = 18.sp, color = Color(255, 255, 255, 180)))
            }
            val ex = extrema
            if (card.s("extrema") != "false" && ex != null) {
                val (lo, hi) = ex
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Extremum(stringResource(R.string.graph_min), Fmt.number(lo.second, decimals) + " $unit", fmtTime.format(Instant.ofEpochMilli(lo.first).atZone(zone)), Alignment.Start, Modifier.weight(1f))
                    Extremum(stringResource(R.string.graph_max), Fmt.number(hi.second, decimals) + " $unit", fmtTime.format(Instant.ofEpochMilli(hi.first).atZone(zone)), Alignment.End, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Extremum(label: String, value: String, time: String, align: Alignment.Horizontal, modifier: Modifier) {
    Column(modifier, horizontalAlignment = align) {
        Text(label, style = TextStyle(fontSize = 15.sp, color = Color(255, 255, 255, 180), fontWeight = FontWeight.Bold))
        Text(value, style = TextStyle(fontSize = 15.sp, color = Color(255, 255, 255, 180)))
        Text(time, style = TextStyle(fontSize = 15.sp, color = Color(255, 255, 255, 180)))
    }
}


/** Courbe lissée, pour les autres fichiers de cartes. */
internal fun smoothPathPublic(pts: List<Offset>): Path = smoothPath(pts)
