package fr.pergens.hamacs.ui.cards.alerts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import fr.pergens.hamacs.App
import fr.pergens.hamacs.config.b
import fr.pergens.hamacs.config.d
import fr.pergens.hamacs.config.s
import fr.pergens.hamacs.ha.EntityState
import fr.pergens.hamacs.ha.Forecast
import fr.pergens.hamacs.ha.subscribeForecast
import fr.pergens.hamacs.ui.components.HaIcon
import fr.pergens.hamacs.ui.theme.parseCssColor
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Widgets « Tout est OK » de l'AlertTicker Card : horloge (4 styles), météo (8 styles, fond animé selon
 * la condition : soleil, étoiles et lune, aurore, nuages, brouillard, vent, pluie, neige, grêle, orage…),
 * prévisions sur 7 jours, et alternance météo / prévisions. Transcription de _renderWeatherBg(), _w*(),
 * _renderForecastWidget(), _renderClearWidget() et des feuilles de style associées.
 */

/** `filter: blur(Npx)` (écart type σ) → rayon de BlurMaskFilter (rayon ≈ σ × √3). */
private const val CSS_BLUR = 1.732f
private fun remOf(k: Float): TextUnit = (16f * k).sp
private fun rgba(r: Int, g: Int, b: Int, a: Float) = Color(r, g, b, (a.coerceIn(0f, 1f) * 255).roundToInt())
private fun hex(v: Long) = Color(v or 0xFF000000)

// --- Générateur pseudo-aléatoire identique à _rng() (mêmes positions que la carte d'origine) -------------

private class JsRng(seed: String) {
    private var s: Int

    init {
        var x = 0
        for (c in seed) x = x * 31 + c.code
        s = if (x == 0) 12345 else x
    }

    fun next(): Float {
        s = s * 1664525 + 1013904223
        return ((s.toLong() and 0xffffffffL).toDouble() / 0xffffffffL.toDouble()).toFloat()
    }
}

// --- Description de la scène (calculée une fois par condition) -------------------------------------------

private class Ray(val angle: Float, val len: Float, val dist: Float, val w: Float, val op: Float)
private class Star(val size: Float, val top: Float, val left: Float, val delay: Float, val tc: Int, val glow: Float)
private class Ribbon(val top: Float, val h: Float, val color: Color, val alt: Boolean, val dur: Float, val delay: Float)
private class Cloud(val top: Float, val left: Float, val w: Float, val h: Float, val delay: Float, val anim: Int, val op: Float, val dur: Float)
private class FogBand(val top: Float, val h: Float, val op: Float, val dur: Float, val delay: Float, val alt: Boolean, val blur: Float)
private class WindLine(val w: Float, val top: Float, val dur: Float, val delay: Float, val color: Color, val h: Float, val blur: Float)
private class Drop(val w: Float, val h: Float, val left: Float, val dur: Float, val delay: Float, val op: Float)
private class Splash(val left: Float, val size: Float, val delay: Float, val dur: Float)
private class Flake(val size: Float, val left: Float, val dur: Float, val delay: Float, val sx: Float, val sr: Float, val ch: String)
private class HailStone(val size: Float, val left: Float, val dur: Float, val delay: Float, val hx: Float)
private class Bolt(val left: Float, val dur: Float, val delay: Float, val main: List<Offset>, val branches: List<Pair<Offset, Offset>>)
private class Dust(val size: Float, val dr: Float, val dur: Float, val delay: Float, val color: Color)

private sealed interface Layer {
    class Sun(val rays: List<Ray>) : Layer
    class Stars(val stars: List<Star>, val moon: Boolean, val ribbons: List<Ribbon>, val shootDelay: Float) : Layer
    class Clouds(val clouds: List<Cloud>, val variant: String) : Layer
    class Fog(val bands: List<FogBand>) : Layer
    class Wind(val lines: List<WindLine>) : Layer
    class Rain(val drops: List<Drop>, val splashes: List<Splash>, val heavy: Boolean) : Layer
    class Snow(val flakes: List<Flake>) : Layer
    class Hail(val stones: List<HailStone>) : Layer
    class Lightning(val bolts: List<Bolt>, val flashDur: Float) : Layer
    class Exceptional(val dust: List<Dust>, val lines: List<WindLine>) : Layer
}

private class Scene(val bg: List<Pair<Float, Color>>?, val angle: Float, val layers: List<Layer>)

private fun sun(r: JsRng) = Layer.Sun(List(18) { i ->
    val angle = (360f / 18) * i
    val len = r.next() * 55 + 55
    val dist = r.next() * 10 + 52
    val w = r.next() * 1.5f + 1.5f
    val op = r.next() * 0.4f + 0.2f
    Ray(angle, len, dist, w, op)
})

private fun stars(count: Int, r: JsRng, withMoon: Boolean): Layer.Stars {
    val list = List(count) {
        val size = r.next() * 1.7f + 0.8f
        val top = r.next() * (if (withMoon) 70 else 78) + 2
        val left = r.next() * 94 + 3
        val delay = r.next() * 5
        val tc = floor(r.next() * 5).toInt()
        val glow = r.next() * 0.5f + 0.3f
        Star(size, top, left, delay, tc, glow)
    }
    var ribbons = emptyList<Ribbon>()
    var shoot = 0f
    if (withMoon) {
        val colors = listOf(rgba(0, 255, 130, .18f), rgba(0, 160, 255, .14f), rgba(140, 0, 255, .12f), rgba(0, 255, 200, .10f))
        ribbons = colors.mapIndexed { i, c ->
            Ribbon(8f + i * 9, 120f + floor(r.next() * 60), c, i % 2 != 0, 7f + i * 2.3f, i * 1.5f)
        }
        shoot = r.next() * 3
    }
    return Layer.Stars(list, withMoon, ribbons, shoot)
}

private val cloudCfgs = listOf(
    floatArrayOf(6f, 8f, 200f, 70f, 0f, 0f, .88f), floatArrayOf(2f, 38f, 260f, 85f, 1.5f, 1f, .78f),
    floatArrayOf(10f, 60f, 180f, 65f, .8f, 2f, .82f), floatArrayOf(18f, 20f, 140f, 50f, 2f, 0f, .65f),
    floatArrayOf(4f, 74f, 150f, 55f, 3f, 1f, .7f),
)

private fun clouds(count: Int, r: JsRng, variant: String) = Layer.Clouds(List(minOf(count, cloudCfgs.size)) { i ->
    val c = cloudCfgs[i]
    val dur = 12f + i * 3 + floor(r.next() * 5)
    Cloud(c[0], c[1], c[2], c[3], c[4], c[5].toInt(), c[6], dur)
}, variant)

private fun fog(r: JsRng): Layer.Fog {
    val bands = listOf(
        floatArrayOf(15f, 55f, .22f, 18f, 0f, 0f), floatArrayOf(30f, 45f, .28f, 22f, -5f, 1f), floatArrayOf(42f, 60f, .32f, 15f, -3f, 0f),
        floatArrayOf(55f, 50f, .35f, 25f, -8f, 1f), floatArrayOf(65f, 65f, .38f, 20f, -2f, 0f), floatArrayOf(75f, 70f, .42f, 17f, -10f, 1f),
        floatArrayOf(82f, 80f, .5f, 14f, -4f, 0f), floatArrayOf(88f, 90f, .55f, 28f, -6f, 1f),
    )
    return Layer.Fog(bands.map { b -> FogBand(b[0], b[1], b[2], b[3], b[4], b[5] == 1f, floor(r.next() * 17) + 18) })
}

private fun windLines(count: Int, r: JsRng): List<WindLine> {
    val pal = listOf(Triple(255, 255, 255), Triple(200, 240, 235), Triple(180, 230, 225))
    return List(count) {
        val w = r.next() * 160 + 60
        val top = r.next() * 87 + 5
        val dur = r.next() * 1.8f + 1.4f
        val delay = r.next() * 4
        val op = r.next() * 0.37f + 0.18f
        val col = pal[floor(r.next() * pal.size).toInt().coerceAtMost(pal.size - 1)]
        val h = r.next() * 1.5f + 1
        val blur = r.next() + 0.5f
        WindLine(w, top, dur, delay, rgba(col.first, col.second, col.third, op), h, blur)
    }
}

private fun rain(count: Int, r: JsRng, heavy: Boolean): Layer.Rain {
    val drops = List(count) {
        val w = r.next() * (if (heavy) 1f else .5f) + 1
        val h = r.next() * (if (heavy) 20 else 14) + (if (heavy) 25 else 14)
        val left = r.next() * 100
        val dur = r.next() * (if (heavy) .35f else .6f) + (if (heavy) .55f else .8f)
        val delay = r.next() * 2
        val op = r.next() * 0.4f + 0.35f
        Drop(w, h, left, dur, delay, op)
    }
    val splashes = List(6) {
        val left = r.next() * 85 + 5
        val size = r.next() * 12 + 8
        val delay = r.next()
        val dur = r.next() * .4f + .4f
        Splash(left, size, delay, dur)
    }
    return Layer.Rain(drops, splashes, heavy)
}

private fun snow(count: Int, r: JsRng): Layer.Snow {
    val chars = listOf("❄", "❅", "❆", "✻", "✼")
    return Layer.Snow(List(count) {
        val size = r.next() * 16 + 10
        val left = r.next() * 100
        val dur = r.next() * 6 + 4
        val delay = r.next() * 6
        val sx = r.next() * 120 - 60
        val mag = r.next() * 280 + 120
        val sr = mag * (if (r.next() > 0.5f) 1 else -1)
        val ch = chars[floor(r.next() * chars.size).toInt().coerceAtMost(chars.size - 1)]
        Flake(size, left, dur, delay, sx, sr, ch)
    })
}

private fun hail(count: Int, r: JsRng) = Layer.Hail(List(count) {
    val size = r.next() * 7 + 5
    val left = r.next() * 100
    val dur = r.next() * .3f + .45f
    val delay = r.next() * 2.5f
    val hx = r.next() * 60 - 30
    HailStone(size, left, dur, delay, hx)
})

private fun lightning(r: JsRng): Layer.Lightning {
    val bolts = List(2) { b ->
        val left = 20f + b * 35
        val dur = r.next() * 2.5f + 2.5f
        val delay = b * 1.8f
        val steps = floor(r.next() * 5).toInt() + 7
        var x = 35f
        var y = 0f
        val main = mutableListOf(Offset(x, y))
        val branches = mutableListOf<Pair<Offset, Offset>>()
        for (i in 0 until steps) {
            x += r.next() * 36 - 18
            y += 200f / steps
            main += Offset(x, y)
            if (r.next() < 0.4f && i < steps - 2) {
                val bx = x + r.next() * 50 - 25
                val by = y + r.next() * 30 + 20
                branches += Offset(x, y) to Offset(bx, by)
            }
        }
        Bolt(left, dur, delay, main, branches)
    }
    return Layer.Lightning(bolts, r.next() * 2.5f + 2.5f)
}

private fun exceptional(r: JsRng): Layer.Exceptional {
    val dust = List(30) {
        val size = r.next() * 6 + 2
        val dr = r.next() * 70 + 20
        val dur = r.next() * 5 + 3
        val delay = r.next() * 5
        r.next() // angle initial (remplacé par l'animation)
        val rc = floor(r.next() * 55).toInt() + 200
        val gc = floor(r.next() * 80).toInt() + 80
        val bc = floor(r.next() * 50).toInt() + 10
        val op = r.next() * 0.4f + 0.3f
        r.next() // flou
        Dust(size, dr, dur, delay, rgba(rc, gc, bc, op))
    }
    val lines = List(12) {
        val dur = r.next() * 1.5f + 1
        val top = r.next() * 80 + 10
        val op = r.next() * 0.23f + 0.12f
        val w = r.next() * 140 + 40
        val h = r.next() + 1
        val delay = r.next() * 3
        WindLine(w, top, dur, delay, rgba(255, 160, 80, op), h, 1f)
    }
    return Layer.Exceptional(dust, lines)
}

private fun scene(state: String, night: Boolean): Scene {
    val r = JsRng(state + if (night) "_n" else "_d")
    val layers: List<Layer> = when {
        state == "sunny" && !night -> listOf(sun(r))
        state == "clear-night" || (night && state == "sunny") -> listOf(stars(18, r, true))
        state == "partlycloudy" -> if (night) listOf(stars(8, r, true), clouds(2, r, "night")) else listOf(sun(r), clouds(2, r, "day"))
        state == "cloudy" -> listOf(clouds(5, r, "grey"))
        state == "fog" -> listOf(fog(r))
        state == "windy" || state == "windy-variant" -> listOf(Layer.Wind(windLines(10, r)))
        state == "rainy" -> listOf(clouds(4, r, "dark"), rain(26, r, false))
        state == "snowy-rainy" -> listOf(clouds(4, r, "dark"), rain(18, r, false), snow(8, r))
        state == "pouring" -> listOf(clouds(5, r, "dark"), rain(40, r, true))
        state == "snowy" -> listOf(clouds(3, r, "grey"), snow(18, r))
        state == "hail" -> listOf(clouds(4, r, "dark"), hail(22, r))
        state == "lightning" -> listOf(clouds(5, r, "storm"), rain(22, r, false), lightning(r))
        state == "lightning-rainy" -> listOf(clouds(5, r, "storm"), rain(36, r, true), lightning(r))
        state == "exceptional" -> listOf(exceptional(r))
        else -> emptyList()
    }
    val (angle, stops) = when {
        state == "partlycloudy" && night -> 175f to listOf(0f to hex(0x020510), .5f to hex(0x050d22), 1f to hex(0x0d1a3a))
        else -> when (state) {
            "sunny" -> 155f to listOf(0f to hex(0x0a3a7a), .3f to hex(0x1260a8), .75f to hex(0xc27a10), 1f to hex(0xc8a020))
            "clear-night" -> 175f to listOf(0f to hex(0x000208), .4f to hex(0x030b1e), .7f to hex(0x060d22), 1f to hex(0x0b1535))
            "partlycloudy" -> 155f to listOf(0f to hex(0x0d3d80), .45f to hex(0x1e6aaa), 1f to hex(0x3a7fc0))
            "cloudy" -> 175f to listOf(0f to hex(0x2a3440), .4f to hex(0x3a4a58), 1f to hex(0x506070))
            "fog" -> 180f to listOf(0f to hex(0x3a4450), .5f to hex(0x566070), 1f to hex(0x728090))
            "windy", "windy-variant" -> 145f to listOf(0f to hex(0x003028), .4f to hex(0x004840), .8f to hex(0x0d6a60), 1f to hex(0x207870))
            "rainy" -> 175f to listOf(0f to hex(0x1a237e), .4f to hex(0x283593), 1f to hex(0x37474f))
            "snowy-rainy" -> 175f to listOf(0f to hex(0x1b2a4a), .5f to hex(0x2e3f6b), 1f to hex(0x546380))
            "pouring" -> 175f to listOf(0f to hex(0x090c20), .4f to hex(0x10152e), 1f to hex(0x1a2035))
            "snowy" -> 175f to listOf(0f to hex(0x2a3840), .5f to hex(0x3e5460), 1f to hex(0x587080))
            "hail" -> 165f to listOf(0f to hex(0x263238), .4f to hex(0x37474f), 1f to hex(0x546e7a))
            "lightning" -> 175f to listOf(0f to hex(0x0a0a1f), .4f to hex(0x12103a), 1f to hex(0x1e1040))
            "lightning-rainy" -> 175f to listOf(0f to hex(0x050510), .4f to hex(0x0a0820), 1f to hex(0x110c28))
            "exceptional" -> 145f to listOf(0f to hex(0xbf360c), .4f to hex(0xe64a19), 1f to hex(0xff7043))
            else -> 0f to null
        }
    }
    return Scene(stops, angle, layers)
}

// --- Outils de dessin --------------------------------------------------------------------------------------

/** Dégradé linéaire CSS (angle en degrés, arrêts explicites). */
private fun cssLinear(angle: Float, w: Float, h: Float, stops: List<Pair<Float, Color>>): Brush {
    val rad = (angle - 90f) * PI.toFloat() / 180f
    val dx = cos(rad)
    val dy = sin(rad)
    val half = (kotlin.math.abs(w * dx) + kotlin.math.abs(h * dy)) / 2f
    return Brush.linearGradient(
        *stops.toTypedArray(),
        start = Offset(w / 2 - dx * half, h / 2 - dy * half),
        end = Offset(w / 2 + dx * half, h / 2 + dy * half),
    )
}

/** Dégradé radial elliptique (« radial-gradient(ellipse rx ry at cx cy, …) »). */
private fun DrawScope.ellipseGlow(cx: Float, cy: Float, rx: Float, ry: Float, stops: List<Pair<Float, Color>>, alpha: Float = 1f, k: Float = 1f) {
    if (rx <= 0f || ry <= 0f) return
    scale(k, k * ry / rx, Offset(cx, cy)) {
        drawCircle(Brush.radialGradient(*stops.toTypedArray(), center = Offset(cx, cy), radius = rx), rx, Offset(cx, cy), alpha = alpha.coerceIn(0f, 1f))
    }
}

/** Interpolation linéaire entre des paires (instant, valeur). */
private fun lin(p: Float, vararg stops: Pair<Float, Float>): Float {
    if (p <= stops.first().first) return stops.first().second
    for (i in 1 until stops.size) {
        val (t1, v1) = stops[i]
        val (t0, v0) = stops[i - 1]
        if (p <= t1) return if (t1 == t0) v1 else v0 + (v1 - v0) * (p - t0) / (t1 - t0)
    }
    return stops.last().second
}

/** Phase d'une animation CSS : 0 avant le début (délai positif), cycle ensuite. */
private fun cssPhase(t: Long, durS: Float, delayS: Float): Float {
    val ms = t - (delayS * 1000).toLong()
    if (ms < 0) return 0f
    return phase(ms, (durS * 1000).toInt().coerceAtLeast(1))
}

/** Ombre portée floue (box-shadow) d'un disque : couleur pleine jusqu'au bord, fondu sur [blur]. */
private fun DrawScope.discShadow(c: Offset, radius: Float, spread: Float, blur: Float, color: Color) {
    val outer = radius + spread + blur
    val inner = ((radius + spread - blur) / outer).coerceIn(0f, 0.999f)
    drawCircle(Brush.radialGradient(0f to color, inner to color, 1f to color.copy(alpha = 0f), center = c, radius = outer), outer, c)
}

private fun blurPaint(color: Color, blur: Float) = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color.toArgb()
    if (blur > 0.5f) maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
}

/** Ligne horizontale en fondu (vent, brouillard) : transparent → couleur → transparent, floutée. */
private fun DrawScope.fadedBand(x: Float, y: Float, w: Float, h: Float, color: Color, from: Float, to: Float, blur: Float, oval: Boolean) {
    val paint = blurPaint(Color.White, blur)
    paint.shader = android.graphics.LinearGradient(
        x, 0f, x + w, 0f,
        intArrayOf(Color.Transparent.toArgb(), color.toArgb(), color.toArgb(), Color.Transparent.toArgb()),
        floatArrayOf(0f, from, to, 1f), android.graphics.Shader.TileMode.CLAMP,
    )
    drawIntoCanvas {
        if (oval) it.nativeCanvas.drawOval(x, y, x + w, y + h, paint)
        else it.nativeCanvas.drawRoundRect(x, y, x + w, y + h, 2 * density, 2 * density, paint)
    }
}

// --- Rendu du fond animé -------------------------------------------------------------------------------------

private fun DrawScope.drawWeatherScene(sc: Scene, t: Long, vh: Float) {
    val w = size.width
    val h = size.height
    val d = density
    sc.bg?.let { drawRect(cssLinear(sc.angle, w, h, it)) }
    var lightning: Layer.Lightning? = null
    sc.layers.forEach { layer ->
        when (layer) {
            is Layer.Sun -> drawSun(layer, t, d)
            is Layer.Stars -> drawStars(layer, t, d)
            is Layer.Clouds -> drawClouds(layer, t, d)
            is Layer.Fog -> layer.bands.forEach { b ->
                val p = cssPhase(t, b.dur, b.delay)
                val k = keyframes(p, 0f to 0f, .5f to 1f, 1f to 0f)
                val bw = w * 1.2f
                val tx = bw * (if (b.alt) -0.05f else 0.04f) * k
                val sy = 1f + (if (b.alt) -0.1f else 0.1f) * k
                val top = h * b.top / 100f
                val bh = b.h * d
                scale(1f, sy, Offset(0f, top + bh / 2)) {
                    fadedBand(-0.1f * w + tx, top, bw, bh, rgba(195, 208, 220, b.op), .3f, .7f, b.blur * d * CSS_BLUR, true)
                }
            }
            is Layer.Wind -> clipRect { layer.lines.forEach { drawWindLine(it, t, d) } }
            is Layer.Rain -> drawRain(layer, t, d, vh)
            is Layer.Snow -> drawSnow(layer, t, d, vh)
            is Layer.Hail -> layer.stones.forEach { s ->
                val p = cssPhase(t, s.dur, s.delay)
                val op = lin(p, 0f to 0f, .06f to 1f, .92f to 1f, 1f to 0f)
                if (op <= 0.01f) return@forEach
                val sz = s.size * d
                val c = Offset(w * s.left / 100f + s.hx * d * p + sz / 2, -0.05f * h + 1.1f * vh * p + sz / 2)
                drawCircle(
                    Brush.radialGradient(
                        0f to rgba(255, 255, 255, .9f), .6f to rgba(200, 230, 255, .6f), 1f to rgba(150, 200, 240, .3f),
                        center = Offset(c.x - sz * .15f, c.y - sz * .2f), radius = sz * .8f,
                    ), sz / 2, c, alpha = op,
                )
            }
            is Layer.Lightning -> lightning = layer
            is Layer.Exceptional -> {
                drawRect(Brush.verticalGradient(0f to rgba(255, 100, 20, .08f), .4f to Color.Transparent, startY = h, endY = 0f))
                layer.dust.forEach { p0 ->
                    val p = cssPhase(t, p0.dur, p0.delay)
                    val op = lin(p, 0f to 0f, .1f to 1f, .9f to .6f, 1f to 0f)
                    val a = 2 * PI.toFloat() * p
                    val sz = p0.size * d
                    val c = Offset(w * .5f + sz / 2 + cos(a) * p0.dr * d, h * .6f + sz / 2 + sin(a) * p0.dr * d)
                    drawCircle(p0.color, sz / 2, c, alpha = op)
                }
                clipRect { layer.lines.forEach { drawWindLine(it, t, d) } }
            }
        }
    }
    // Vignette (::after) puis éclairs, au premier plan.
    val rx = w / 2 * sqrt(2f)
    ellipseGlow(w / 2, h / 2, rx, h / 2 * sqrt(2f), listOf(0f to Color.Transparent, .4f to Color.Transparent, 1f to rgba(0, 0, 0, .18f)))
    lightning?.let { drawLightning(it, t, d) }
}

private fun DrawScope.drawSun(l: Layer.Sun, t: Long, d: Float) {
    val c = Offset(size.width / 2, size.height * .1f + 45 * d)
    val core = 45 * d
    // Halo flottant (6 s) et rayons en rotation (22 s).
    val hk = keyframes(phase(t, 6000), 0f to 0f, .5f to 1f, 1f to 0f)
    val hc = Offset(c.x, c.y - 4 * d * hk)
    val hr = 100 * d * (1f + .08f * hk)
    drawCircle(Brush.radialGradient(0f to rgba(255, 220, 50, .12f), .7f to Color.Transparent, center = hc, radius = hr), hr, hc)
    val rot = 360f * phase(t, 22000)
    l.rays.forEach { ray ->
        rotate(ray.angle + rot, c) {
            val rw = ray.w * d
            drawRect(
                Brush.horizontalGradient(listOf(rgba(255, 236, 100, .55f), Color.Transparent), c.x - rw / 2, c.x + rw / 2),
                Offset(c.x - rw / 2, c.y + ray.dist * d), Size(rw, ray.len * d), alpha = ray.op,
            )
        }
    }
    // Respiration du disque (4 s) : quatre ombres portées.
    val k = breathe(phase(t, 4000))
    fun lerp(a: Float, b: Float) = a + (b - a) * k
    discShadow(c, core, lerp(60f, 80f) * d, lerp(140f, 180f) * d * .5f, rgba(230, 81, 0, lerp(.15f, .2f)))
    discShadow(c, core, lerp(30f, 50f) * d, lerp(80f, 110f) * d * .5f, rgba(255, 152, 0, lerp(.3f, .35f)))
    discShadow(c, core, lerp(12f, 20f) * d, lerp(30f, 50f) * d * .5f, rgba(255, 193, 7, lerp(.4f, .55f)))
    drawCircle(rgba(255, 224, 82, lerp(.12f, .18f)), core + lerp(10f, 18f) * d, c)
    drawCircle(
        Brush.radialGradient(
            0f to hex(0xfff8e1), .3f to hex(0xffe082), .65f to hex(0xffb300), 1f to hex(0xe65100),
            center = Offset(c.x - .12f * 90 * d, c.y - .14f * 90 * d), radius = 80 * d,
        ), core, c,
    )
}

private val twinkles = listOf(
    floatArrayOf(3100f, .9f, .2f, .6f), floatArrayOf(4200f, .7f, .15f, .5f), floatArrayOf(2800f, 1f, .3f, .7f),
    floatArrayOf(5100f, .6f, .1f, .4f), floatArrayOf(3700f, .85f, .25f, .65f),
)

private fun DrawScope.drawStars(l: Layer.Stars, t: Long, d: Float) {
    val w = size.width
    val h = size.height
    if (l.moon) {
        // Aurore (rubans flous) dans la moitié haute.
        clipRect(0f, 0f, w, h * .55f) {
            l.ribbons.forEach { rb ->
                val p = cssPhase(t, rb.dur, rb.delay)
                val k = keyframes(p, 0f to 0f, .5f to 1f, 1f to 0f)
                val ty = (if (rb.alt) -18f else 20f) * d * k
                val sy = 1f + (if (rb.alt) .2f else .4f) * k
                val sx = 1f + (if (rb.alt) -.03f else .05f) * k
                val top = h * .55f * rb.top / 100f + ty
                val rh = rb.h * d
                val left = -0.2f * w
                val rw = 1.4f * w
                scale(sx, sy, Offset(left + rw / 2, top + rh / 2)) {
                    drawIntoCanvas { it.nativeCanvas.drawOval(left, top, left + rw, top + rh, blurPaint(rb.color, 28 * d * CSS_BLUR)) }
                }
            }
        }
        // Lune : lueur respirante, dégradé, ombre intérieure et cratères.
        val r = 35 * d
        val c = Offset(w / 2, h * .12f + r)
        val k = breathe(phase(t, 6000))
        discShadow(c, r, (30 + 20 * k) * d, (80 + 30 * k) * d * .5f, rgba(200, 190, 120, .08f + .06f * k))
        discShadow(c, r, (10 + 8 * k) * d, (30 + 15 * k) * d * .5f, rgba(220, 210, 150, .18f + .1f * k))
        drawCircle(
            Brush.radialGradient(0f to hex(0xf0f0e0), .5f to hex(0xd8d8b0), 1f to hex(0xb0a870), center = Offset(c.x - .12f * 2 * r, c.y - .15f * 2 * r), radius = 2 * r * .8f),
            r, c,
        )
        val moon = Path().apply { addOval(Rect(c, r)) }
        val lit = Path().apply { addOval(Rect(Offset(c.x - 12 * d, c.y - 8 * d), r)) }
        drawPath(Path.combine(PathOperation.Difference, moon, lit), rgba(0, 0, 0, .18f))
        clipPath(moon) {
            listOf(floatArrayOf(22f, 25f, 14f, 12f), floatArrayOf(40f, 55f, 8f, 7f), floatArrayOf(55f, 30f, 6f, 5f)).forEach { cr ->
                val x = c.x - r + 2 * r * cr[0] / 100f
                val y = c.y - r + 2 * r * cr[1] / 100f
                drawOval(rgba(0, 0, 0, .08f), Offset(x, y), Size(cr[2] * d, cr[3] * d))
            }
        }
        // Étoile filante (6 s, ease-in).
        val p = cssPhase(t, 6f, l.shootDelay)
        val op = lin(p, 0f to 0f, .06f to 1f, .35f to 0f, 1f to 0f)
        if (op > 0.01f) {
            val e = (p / .35f).coerceAtMost(1f).let { it * it }
            val x0 = w * .65f
            val y0 = h * .05f
            val sh = 85 * d
            rotate(-30f, Offset(x0 + d, y0 + sh / 2)) {
                val tx = -55 * d * e
                val ty = 210 * d * e
                drawRect(
                    Brush.verticalGradient(listOf(Color.Transparent, rgba(255, 255, 255, .95f)), y0 + ty, y0 + ty + sh),
                    Offset(x0 + tx, y0 + ty), Size(2 * d, sh), alpha = op,
                )
            }
        }
    }
    l.stars.forEach { s ->
        val tw = twinkles[s.tc.coerceIn(0, 4)]
        val k = keyframes(cssPhase(t, tw[0] / 1000f, s.delay), 0f to 0f, .5f to 1f, 1f to 0f)
        val op = tw[1] + (tw[2] - tw[1]) * k
        val sc = 1f + (tw[3] - 1f) * k
        val sz = s.size * d
        val c = Offset(w * s.left / 100f + sz / 2, h * s.top / 100f + sz / 2)
        val gr = sz / 2 + sz * 2
        drawCircle(Brush.radialGradient(0f to rgba(220, 220, 255, s.glow), 1f to Color.Transparent, center = c, radius = gr * sc), gr * sc, c, alpha = op)
        drawCircle(Color.White, sz / 2 * sc, c, alpha = op)
    }
}

private val cloudBody = mapOf(
    "day" to rgba(255, 255, 255, .82f), "night" to rgba(40, 55, 90, .8f), "grey" to rgba(120, 130, 145, .75f),
    "dark" to rgba(30, 35, 55, .85f), "storm" to rgba(20, 15, 40, .9f),
)
private val cloudPuff = mapOf(
    "day" to rgba(255, 255, 255, .9f), "night" to rgba(40, 55, 90, .8f), "grey" to rgba(120, 130, 145, .75f),
    "dark" to rgba(30, 35, 55, .85f), "storm" to rgba(20, 15, 40, .9f),
)

private fun DrawScope.drawClouds(l: Layer.Clouds, t: Long, d: Float) {
    val body = cloudBody[l.variant] ?: cloudBody.getValue("day")
    val puff = cloudPuff[l.variant] ?: cloudPuff.getValue("day")
    l.clouds.forEach { c ->
        val p = cssPhase(t, c.dur, c.delay)
        val (tx, ty) = when (c.anim) {
            1 -> keyframes(p, 0f to 0f, .4f to -8f, .75f to 5f, 1f to 0f) to keyframes(p, 0f to 0f, .4f to 2f, .75f to -2f, 1f to 0f)
            2 -> keyframes(p, 0f to 0f, .3f to 10f, .65f to -6f, 1f to 0f) to keyframes(p, 0f to 0f, .3f to 3f, .65f to -3f, 1f to 0f)
            else -> keyframes(p, 0f to 0f, .33f to 6f, .66f to -4f, 1f to 0f) to keyframes(p, 0f to 0f, .33f to -3f, .66f to 2f, 1f to 0f)
        }
        val x = size.width * c.left / 100f + tx * d
        val y = size.height * c.top / 100f + ty * d
        val cw = c.w * d
        val ch = c.h * d
        // Opacité appliquée au nuage entier (calque), comme `opacity` en CSS.
        drawIntoCanvas { cv ->
            val paint = androidx.compose.ui.graphics.Paint().apply { alpha = c.op }
            cv.saveLayer(Rect(x - cw * .2f, y - ch * .8f, x + cw * 1.2f, y + ch * 1.2f), paint)
            drawRoundRect(body, Offset(x, y), Size(cw, ch), androidx.compose.ui.geometry.CornerRadius(minOf(50 * d, ch / 2)))
            drawOval(puff, Offset(x + cw * .18f, y - ch * .4f), Size(cw * .45f, ch * 1.1f))
            drawOval(puff, Offset(x + cw * .48f, y - ch * .55f), Size(cw * .38f, ch * 1.3f))
            cv.restore()
        }
    }
}

private fun DrawScope.drawWindLine(l: WindLine, t: Long, d: Float) {
    val p = cssPhase(t, l.dur, l.delay)
    val op = lin(p, 0f to 0f, .08f to 1f, .85f to .6f, 1f to 0f)
    if (op <= 0.01f) return
    val lw = l.w * d
    val x = -0.3f * size.width + 1.6f * lw * p
    fadedBand(x, size.height * l.top / 100f, lw, l.h * d, l.color.copy(alpha = l.color.alpha * op), .4f, .8f, l.blur * d * CSS_BLUR, false)
}

private fun DrawScope.drawRain(l: Layer.Rain, t: Long, d: Float, vh: Float) {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(0f to Color.Transparent, .6f to Color.Transparent, 1f to rgba(10, 20, 60, .3f)))
    val drift = if (l.heavy) -90f else -60f
    l.drops.forEach { dr ->
        val p = cssPhase(t, dr.dur, dr.delay)
        val op = if (l.heavy) lin(p, 0f to 0f, .04f to 1f, .94f to .8f, 1f to 0f) else lin(p, 0f to 0f, .05f to 1f, .95f to .7f, 1f to 0f)
        if (op <= 0.01f) return@forEach
        val y = -0.08f * h + 1.2f * vh * p
        if (y > h) return@forEach
        val x = w * dr.left / 100f + drift * d * p
        val dh = dr.h * d
        drawRect(
            Brush.verticalGradient(listOf(rgba(180, 210, 255, .7f), rgba(140, 180, 240, .3f)), y, y + dh),
            Offset(x, y), Size(dr.w * d, dh), alpha = op * dr.op,
        )
    }
    l.splashes.forEach { s ->
        val p = cssPhase(t, s.dur, s.delay)
        val e = 1f - (1f - p) * (1f - p)
        val sz = s.size * d
        val c = Offset(w * s.left / 100f + sz / 2, h * .98f - sz / 2)
        if (e > 0.02f) drawCircle(rgba(180, 210, 255, .4f), sz / 2 * e, c, alpha = .8f * (1f - e), style = Stroke(d))
    }
}

private fun DrawScope.drawSnow(l: Layer.Snow, t: Long, d: Float, vh: Float) {
    val w = size.width
    val h = size.height
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = android.graphics.Paint.Align.CENTER
        setShadowLayer(3 * d, 0f, 0f, rgba(200, 230, 255, .6f).toArgb())
    }
    l.flakes.forEach { f ->
        val p = phase(t - (-f.delay * 1000).toLong(), (f.dur * 1000).toInt())
        val op = lin(p, 0f to 0f, .08f to 1f, .9f to .8f, 1f to 0f)
        if (op <= 0.01f) return@forEach
        val e = p * p
        val y = -0.05f * h + 1.1f * vh * e
        if (y > h + 40 * d) return@forEach
        val fs = f.size * d
        val x = w * f.left / 100f + f.sx * d * e
        paint.textSize = fs
        paint.color = rgba(255, 255, 255, .85f * op).toArgb()
        drawIntoCanvas { cv ->
            cv.nativeCanvas.save()
            cv.nativeCanvas.rotate(f.sr * e, x + fs / 2, y + fs / 2)
            cv.nativeCanvas.drawText(f.ch, x + fs / 2, y + fs * .85f, paint)
            cv.nativeCanvas.restore()
        }
    }
    // Neige au sol.
    clipRect(0f, h * .72f, w, h) {
        drawOval(
            Brush.verticalGradient(listOf(Color.Transparent, rgba(255, 255, 255, .55f)), h * .72f, h),
            Offset(-0.05f * w, h * .72f), Size(1.1f * w, h * .56f),
        )
    }
}

private fun DrawScope.drawLightning(l: Layer.Lightning, t: Long, d: Float) {
    val sk = lin(phase(t, (l.flashDur * 1000).toInt()), 0f to 0f, .03f to 1f, .06f to .2f, .09f to .8f, .15f to 0f, 1f to 0f)
    if (sk > 0.01f) drawRect(rgba(200, 180, 255, .12f), alpha = sk)
    l.bolts.forEach { b ->
        val op = keyframes(cssPhase(t, b.dur, b.delay), 0f to 0f, .02f to 1f, .05f to .3f, .07f to 1f, .12f to 0f, 1f to 0f)
        if (op <= 0.01f) return@forEach
        translate(size.width * b.left / 100f, 0f) {
            val path = Path().apply {
                moveTo(b.main.first().x * d, b.main.first().y * d)
                b.main.drop(1).forEach { lineTo(it.x * d, it.y * d) }
                b.branches.forEach { (a, z) -> moveTo(a.x * d, a.y * d); lineTo(z.x * d, z.y * d) }
            }
            drawPath(path, rgba(144, 96, 255, .25f), alpha = op, style = Stroke(14 * d, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, rgba(200, 160, 255, .3f), alpha = op, style = Stroke(8 * d, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, hex(0xd0b0ff), alpha = op, style = Stroke(2.5f * d, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

// --- Données météo ------------------------------------------------------------------------------------------

private class WeatherData(
    val state: String,
    val temp: String?,
    val wind: String?,
    val humidity: String?,
    val high: String?,
    val low: String?,
    val aqi: String?,
)

private val ICONS = mapOf(
    "sunny" to "mdi:weather-sunny", "clear-night" to "mdi:weather-night", "partlycloudy" to "mdi:weather-partly-cloudy",
    "cloudy" to "mdi:weather-cloudy", "fog" to "mdi:weather-fog", "windy" to "mdi:weather-windy", "windy-variant" to "mdi:weather-windy-variant",
    "rainy" to "mdi:weather-rainy", "snowy-rainy" to "mdi:weather-snowy-rainy", "pouring" to "mdi:weather-pouring",
    "snowy" to "mdi:weather-snowy", "hail" to "mdi:weather-hail", "lightning" to "mdi:weather-lightning",
    "lightning-rainy" to "mdi:weather-lightning-rainy", "exceptional" to "mdi:alert-circle-outline",
)

private val EMOJI = mapOf(
    "sunny" to "☀️", "clear-night" to "🌙", "partlycloudy" to "⛅", "cloudy" to "☁️", "fog" to "🌫️", "windy" to "💨",
    "windy-variant" to "🌬️", "rainy" to "🌧️", "snowy-rainy" to "🌨️", "pouring" to "⛈️", "snowy" to "❄️", "hail" to "🌩️",
    "lightning" to "⚡", "lightning-rainy" to "🌩️", "exceptional" to "🌪️",
)

private val COND_COLOR = mapOf(
    "sunny" to hex(0xffb300), "clear-night" to hex(0x7c83c8), "partlycloudy" to hex(0x78a0c8), "cloudy" to hex(0x8096a8),
    "fog" to hex(0xa0aeb6), "windy" to hex(0x38b8a0), "windy-variant" to hex(0x38b8a0), "rainy" to hex(0x5080d8),
    "snowy-rainy" to hex(0x78a0d8), "pouring" to hex(0x3060c8), "snowy" to hex(0x90b8e0), "hail" to hex(0x607080),
    "lightning" to hex(0x9060e8), "lightning-rainy" to hex(0x7050d0), "exceptional" to hex(0xe05028),
)

private fun tempColor(t: Int?): Color = when {
    t == null -> rgba(255, 255, 255, .75f)
    t >= 35 -> hex(0xff6d00)
    t >= 28 -> hex(0xffab40)
    t >= 20 -> hex(0xffd54f)
    t >= 10 -> Color.White
    t >= 0 -> hex(0x80d8ff)
    else -> hex(0xea80fc)
}

private fun sensorValue(states: Map<String, EntityState>, id: String?, unit: String?, sep: String = ""): String? {
    val s = id?.let { states[it] } ?: return null
    val v = s.state.toDoubleOrNull() ?: return null
    return "${v.roundToInt()}$sep${s.unit ?: unit ?: ""}"
}

private fun weatherData(cfg: JsonObject, states: Map<String, EntityState>, forecast: List<Forecast>): WeatherData? {
    val ws = cfg.s("clear_weather_entity")?.let { states[it] } ?: return null
    val unit = ws.attrString("temperature_unit") ?: "°"
    val temp = if (cfg.s("clear_weather_temperature_entity") != null) sensorValue(states, cfg.s("clear_weather_temperature_entity"), unit)
    else ws.attrDouble("temperature")?.let { "${it.roundToInt()}$unit" }
    val wind = ws.attrDouble("wind_speed")?.let { "${it.roundToInt()} ${ws.attrString("wind_speed_unit") ?: "km/h"}" }
    val hum = if (cfg.s("clear_weather_humidity_entity") != null) cfg.s("clear_weather_humidity_entity")?.let { states[it] }?.state?.toDoubleOrNull()?.let { "${it.roundToInt()}%" }
    else ws.attrDouble("humidity")?.let { "${it.roundToInt()}%" }
    val today = forecast.firstOrNull()
    val high = if (cfg.s("clear_weather_temp_high_entity") != null) sensorValue(states, cfg.s("clear_weather_temp_high_entity"), unit)
    else today?.temperature?.let { "${it.roundToInt()}$unit" }
    val low = if (cfg.s("clear_weather_temp_low_entity") != null) sensorValue(states, cfg.s("clear_weather_temp_low_entity"), unit)
    else today?.templow?.let { "${it.roundToInt()}$unit" }
    val aqi = sensorValue(states, cfg.s("clear_weather_aqi_entity"), "µg/m³", " ")
    return WeatherData(ws.state, temp, wind, hum, high, low, aqi)
}

// --- Widget principal ---------------------------------------------------------------------------------------

/** Horloge / météo / prévisions affichées quand aucune alerte n'est active. */
@Composable
internal fun AtcClearWidget(cfg: JsonObject, mode: String, states: Map<String, EntityState>, clock: State<Long>, shape: Shape, noAnim: Boolean) {
    val i18n = App.instance.alerts.i18n
    // Heure et date (rafraîchies chaque seconde), au format de la carte d'origine.
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = LocalDateTime.now(); delay(1000) } }
    val seconds = cfg.b("clear_clock_show_seconds", true)
    val time = if (cfg.b("clear_clock_12h")) {
        val h12 = (now.hour % 12).let { if (it == 0) 12 else it }
        "$h12:${now.format(DateTimeFormatter.ofPattern(if (seconds) "mm:ss" else "mm"))} ${if (now.hour < 12) "AM" else "PM"}"
    } else now.format(DateTimeFormatter.ofPattern(if (seconds) "HH:mm:ss" else "HH:mm"))
    val locale = Locale.forLanguageTag(i18n.lang)
    val date = now.format(DateTimeFormatter.ofPattern("EEEE d MMMM", locale))
    val showDate = cfg.b("clear_clock_show_date", true)
    val dateAbove = cfg.s("clear_clock_date_position") == "above"

    // Prévisions quotidiennes (aussi pour les max / min du jour en mode météo).
    val entity = cfg.s("clear_weather_entity")
    var forecast by remember(entity) { mutableStateOf<List<Forecast>>(emptyList()) }
    if (entity != null && mode != "clock") {
        DisposableEffect(entity) {
            val unsub = App.instance.ha.subscribeForecast(entity, "daily") { forecast = it }
            onDispose { unsub() }
        }
    }
    val t = if (noAnim) 2500L else clock.value

    when (mode) {
        "clock" -> ClockWidget(cfg, time, if (showDate) date else null, dateAbove, t, shape)
        "forecast" -> ForecastWidget(forecast, t, shape, i18n, locale)
        "weather", "weather_clock", "weather_forecast" -> {
            val data = weatherData(cfg, states, forecast)
            val withClock = mode != "weather"
            val panel: @Composable (Modifier) -> Unit = { m ->
                if (data == null) EmptyWidget("mdi:weather-cloudy", i18n.t("clear_weather_entity_label"), shape, m)
                else WeatherPanel(cfg, data, if (withClock) time else null, if (showDate) date else null, dateAbove, clock, noAnim, shape, m)
            }
            if (mode != "weather_forecast") panel(Modifier)
            else {
                var showForecast by remember { mutableStateOf(false) }
                val interval = ((cfg.d("weather_forecast_interval") ?: 5.0).takeIf { it > 0 } ?: 5.0) * 1000
                LaunchedEffect(interval) { while (true) { delay(interval.toLong()); showForecast = !showForecast } }
                val k by animateFloatAsState(if (showForecast) 1f else 0f, tween(550), label = "wf")
                Box(Modifier.fillMaxWidth()) {
                    ForecastWidget(forecast, t, shape, i18n, locale, Modifier.graphicsLayer { alpha = k; translationY = (1 - k) * 10 * density })
                    panel(Modifier.matchParentSize().graphicsLayer { alpha = 1 - k; translationY = k * 10 * density })
                }
            }
        }
    }
}

@Composable
private fun EmptyWidget(icon: String, label: String, shape: Shape, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 68.dp).clip(shape)
            .background(Brush.linearGradient(listOf(hex(0x060c1c), hex(0x0c1a3a), hex(0x0a1428)))),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        HaIcon(icon, Modifier.padding(end = 8.dp), 26.dp, Color.White.copy(alpha = .35f))
        Text(label, color = Color.White.copy(alpha = .45f), fontSize = remOf(.85f))
    }
}

// --- Horloge ----------------------------------------------------------------------------------------------

@Composable
private fun ClockWidget(cfg: JsonObject, time: String, date: String?, dateAbove: Boolean, t: Long, shape: Shape) {
    val style = cfg.s("clear_clock_style") ?: "default"
    val customBg = cfg.s("clear_clock_background")?.let { parseCssColor(it) }
    val timeColor = cfg.s("clear_clock_color")?.let { parseCssColor(it) } ?: when (style) {
        "aurora" -> hex(0xa0ffd6); "gold" -> hex(0xf5d060); "matrix" -> hex(0x00e840); else -> hex(0xdce8ff)
    }
    val dateColor = cfg.s("clear_clock_date_color")?.let { parseCssColor(it) } ?: when (style) {
        "aurora" -> rgba(0, 220, 140, .55f); "gold" -> rgba(200, 155, 30, .55f); "matrix" -> rgba(0, 200, 50, .5f); else -> rgba(148, 175, 255, .52f)
    }
    val bgStops = when (style) {
        "aurora" -> listOf(0f to hex(0x020d0a), .4f to hex(0x041a10), .75f to hex(0x061220), 1f to hex(0x020d0a))
        "gold" -> listOf(0f to hex(0x080600), .4f to hex(0x120e00), .7f to hex(0x0e0a00), 1f to hex(0x080600))
        "matrix" -> listOf(0f to Color.Black, 1f to Color.Black)
        else -> listOf(0f to hex(0x060c1c), .45f to hex(0x0c1a3a), .75f to hex(0x0a1428), 1f to hex(0x060c1c))
    }
    val pulsePeriod = when (style) { "aurora" -> 6000; "gold" -> 7000; "matrix" -> 4000; else -> 5000 }
    val glowK = .6f + .4f * breathe(phase(t, pulsePeriod))
    val glow = when (style) {
        "aurora" -> rgba(0, 220, 120, .22f) to floatArrayOf(.55f, .40f, .5f, .5f, .7f)
        "gold" -> rgba(200, 140, 0, .20f) to floatArrayOf(.50f, .35f, .5f, .5f, .65f)
        "matrix" -> rgba(0, 220, 60, .15f) to floatArrayOf(.45f, .35f, .5f, .55f, .65f)
        else -> rgba(100, 160, 255, .07f) to floatArrayOf(.40f, .80f, .5f, .45f, .7f)
    }
    // Aurore : les nappes de couleur respirent (8 s, alternate).
    val auroraP = phase(t, 16000).let { if (it < .5f) it * 2 else 2 - it * 2 }
    val auroraA = keyframes(auroraP, 0f to .7f, .5f to 1f, 1f to .8f)
    val auroraSx = keyframes(auroraP, 0f to 1f, .5f to 1.08f, 1f to .95f)
    val (shadowColor, shadowBlur) = when (style) {
        "aurora" -> rgba(0, 220, 120, .6f) to 18f; "gold" -> rgba(220, 160, 0, .55f) to 16f
        "matrix" -> rgba(0, 220, 50, .8f) to 10f; else -> rgba(90, 150, 255, .55f) to 18f
    }
    val mono = style == "matrix"
    Box(
        Modifier.fillMaxWidth().heightIn(min = 68.dp).clip(shape).drawBehind {
            val w = size.width
            val h = size.height
            if (customBg != null) drawRect(customBg) else drawRect(cssLinear(135f, w, h, bgStops))
            when (style) {
                "aurora" -> scale(auroraSx, 1f) {
                    ellipseGlow(w * .3f, h * .6f, w * .8f, h * .5f, listOf(0f to rgba(0, 220, 120, .18f), .7f to Color.Transparent), auroraA)
                    ellipseGlow(w * .7f, h * .4f, w * .6f, h * .4f, listOf(0f to rgba(80, 40, 200, .20f), .65f to Color.Transparent), auroraA)
                    ellipseGlow(w * .5f, h * .8f, w * .5f, h * .35f, listOf(0f to rgba(0, 180, 160, .12f), .6f to Color.Transparent), auroraA)
                }
                "gold" -> {
                    ellipseGlow(w * .5f, h * .55f, w * .7f, h * .5f, listOf(0f to rgba(200, 150, 0, .14f), .7f to Color.Transparent))
                    ellipseGlow(w * .25f, h * .35f, w * .4f, h * .3f, listOf(0f to rgba(255, 200, 50, .08f), .6f to Color.Transparent))
                }
                "matrix" -> ellipseGlow(w * .5f, h * .5f, w * .6f, h * .5f, listOf(0f to rgba(0, 180, 40, .10f), .7f to Color.Transparent))
                else -> ellipseGlow(w * .5f, h * .5f, w * .65f, h * 1.5f, listOf(0f to rgba(50, 110, 255, .11f), .45f to rgba(80, 60, 200, .06f), .75f to Color.Transparent))
            }
            val g = glow.second
            ellipseGlow(w * g[2], h * g[3], w * g[0], h * g[1], listOf(0f to glow.first, g[4] to Color.Transparent), glowK)
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(vertical = 10.dp)) {
            val dateText: @Composable () -> Unit = {
                if (date != null) Text(
                    date.uppercase(), style = TextStyle(
                        fontSize = remOf(.6f), fontWeight = FontWeight.Medium, color = dateColor,
                        letterSpacing = if (style == "gold" || style == "matrix") .18.em else .12.em,
                        fontFamily = if (mono) FontFamily.Monospace else null,
                    ),
                )
            }
            if (dateAbove) dateText()
            Text(
                time, style = TextStyle(
                    fontSize = remOf(2.05f),
                    fontWeight = when (style) { "gold" -> FontWeight.Light; "matrix" -> FontWeight.Normal; else -> FontWeight.Bold },
                    letterSpacing = when (style) { "gold" -> .18.em; "matrix" -> .22.em; else -> .12.em },
                    color = timeColor, fontFamily = if (mono) FontFamily.Monospace else null,
                    fontFeatureSettings = "tnum",
                    shadow = Shadow(shadowColor, Offset.Zero, shadowBlur * 2),
                ),
            )
            if (!dateAbove) dateText()
        }
    }
}

// --- Météo ------------------------------------------------------------------------------------------------

/** Paramètres visuels d'un style de panneau météo (`clear_weather_style`). */
private class WxStyle(
    val badgeBg: Color?, val badgeBorder: Color?, val badgeRadius: Dp, val padH: Dp, val padV: Dp,
    val temp: TextUnit, val tempWeight: FontWeight, val tempColor: Color,
    val clock: TextUnit, val clockWeight: FontWeight, val clockColor: Color, val clockSpacing: Float,
    val meta: TextUnit, val metaColor: Color, val cond: TextUnit, val condColor: Color,
    val dateSize: TextUnit, val dateColor: Color, val icon: Dp, val heavyShadow: Boolean,
)

private fun wxStyle(name: String): WxStyle {
    val base = WxStyle(
        rgba(8, 18, 32, .65f), Color.White.copy(alpha = .10f), 9.dp, 8.dp, 5.dp,
        remOf(1.2f), FontWeight.Bold, Color.White, remOf(1.3f), FontWeight.Bold, Color.White, .06f,
        remOf(.65f), Color.White.copy(alpha = .8f), remOf(.65f), Color.White.copy(alpha = .7f),
        remOf(.52f), Color.White.copy(alpha = .65f), 20.dp, false,
    )
    return when (name) {
        "frosted" -> WxStyle(
            Color.White.copy(alpha = .08f), Color.White.copy(alpha = .22f), 14.dp, 8.dp, 5.dp,
            base.temp, base.tempWeight, Color.White.copy(alpha = .95f), base.clock, base.clockWeight, Color.White.copy(alpha = .95f), .06f,
            base.meta, Color.White.copy(alpha = .75f), base.cond, Color.White.copy(alpha = .75f), base.dateSize, Color.White.copy(alpha = .55f), 20.dp, false,
        )
        "solid" -> WxStyle(
            rgba(10, 12, 18, .9f), null, 8.dp, 8.dp, 5.dp,
            base.temp, base.tempWeight, Color.White, base.clock, base.clockWeight, hex(0xe8f0ff), .04f,
            base.meta, rgba(180, 195, 230, .75f), base.cond, rgba(200, 210, 255, .8f), base.dateSize, rgba(180, 200, 255, .6f), 20.dp, false,
        )
        "minimal" -> WxStyle(
            null, null, 9.dp, 6.dp, 4.dp,
            remOf(1.3f), base.tempWeight, Color.White, remOf(1.4f), base.clockWeight, Color.White, .06f,
            base.meta, Color.White.copy(alpha = .85f), base.cond, Color.White.copy(alpha = .85f), base.dateSize, Color.White.copy(alpha = .65f), 20.dp, true,
        )
        "wallpanel" -> WxStyle(
            base.badgeBg, base.badgeBorder, 11.dp, 10.dp, 7.dp,
            remOf(3f), base.tempWeight, Color.White, remOf(1.55f), base.clockWeight, Color.White, .06f,
            remOf(1f), base.metaColor, remOf(1f), base.condColor, remOf(.62f), base.dateColor, 26.dp, false,
        )
        else -> base
    }
}

private fun shadowFor(heavy: Boolean, blur: Float = 6f) = Shadow(Color.Black.copy(alpha = if (heavy) .9f else .7f), Offset(0f, 2f), blur * 2)

@Composable
private fun WeatherPanel(
    cfg: JsonObject, data: WeatherData, time: String?, date: String?, dateAbove: Boolean,
    clock: State<Long>, noAnim: Boolean, shape: Shape, modifier: Modifier,
) {
    val i18n = App.instance.alerts.i18n
    val styleName = cfg.s("clear_weather_style") ?: "default"
    val st = wxStyle(styleName)
    val night = App.instance.ha.states.value["sun.sun"]?.state == "below_horizon"
    val sc = remember(data.state, night) { scene(data.state, night) }
    val vh = LocalConfiguration.current.screenHeightDp.toFloat()
    val icon = ICONS[data.state] ?: "mdi:weather-cloudy"
    val condition = i18n.t("weather.${data.state}").takeIf { it != "weather.${data.state}" } ?: data.state
    val cinematic = styleName == "cinematic"
    Box(
        modifier.fillMaxWidth().heightIn(min = if (cinematic) 90.dp else 68.dp).clip(shape).drawBehind {
            if (sc.bg == null) drawRect(hex(0x0d1b2a))
            drawWeatherScene(sc, if (noAnim) 2500L else clock.value, vh * density)
        },
    ) {
        val shadow = shadowFor(st.heavyShadow)
        @Composable
        fun Badge(m: Modifier = Modifier, content: @Composable () -> Unit) {
            val sh = RoundedCornerShape(st.badgeRadius)
            Box(
                m.then(if (st.badgeBg != null) Modifier.clip(sh).background(st.badgeBg, sh) else Modifier)
                    .then(if (st.badgeBorder != null) Modifier.border(1.dp, st.badgeBorder, sh) else Modifier)
                    .padding(horizontal = st.padH, vertical = st.padV),
            ) { content() }
        }
        @Composable
        fun Meta(text: String, color: Color = st.metaColor, size: TextUnit = st.meta) =
            Text(text, style = TextStyle(fontSize = size, fontWeight = FontWeight.Medium, color = color, shadow = shadow), maxLines = 1)
        @Composable
        fun Temp(size: TextUnit = st.temp, weight: FontWeight = st.tempWeight) =
            Text(data.temp ?: "", style = TextStyle(fontSize = size, fontWeight = weight, color = st.tempColor, letterSpacing = (-.02).em, shadow = shadow))
        @Composable
        fun Cond(size: TextUnit = st.cond, upper: Boolean = false) =
            Text(if (upper) condition.uppercase() else condition, style = TextStyle(fontSize = size, fontWeight = FontWeight.SemiBold, color = st.condColor, shadow = shadow), maxLines = 1, overflow = TextOverflow.Ellipsis)
        @Composable
        fun MinMax(showHiLo: Boolean = true) {
            val items = buildList {
                if (showHiLo) { data.high?.let { add("↑$it") }; data.low?.let { add("↓$it") } }
                data.aqi?.let { add("🌿 $it") }
                data.humidity?.let { add("💧 $it") }
            }
            if (items.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(if (styleName == "wallpanel") 7.dp else 4.dp)) { items.forEach { Meta(it) } }
        }
        @Composable
        fun ClockText(size: TextUnit = st.clock, weight: FontWeight = st.clockWeight, spacing: Float = st.clockSpacing, align: Alignment.Horizontal = Alignment.CenterHorizontally, dateSize: TextUnit = st.dateSize) {
            Column(horizontalAlignment = align) {
                val dt: @Composable () -> Unit = {
                    if (date != null) Text(date.uppercase(), style = TextStyle(fontSize = dateSize, fontWeight = FontWeight.Medium, letterSpacing = .10.em, color = st.dateColor, shadow = shadow))
                }
                if (dateAbove) dt()
                Text(time ?: "", style = TextStyle(fontSize = size, fontWeight = weight, letterSpacing = spacing.em, color = st.clockColor, fontFeatureSettings = "tnum", shadow = shadow))
                if (!dateAbove) dt()
            }
        }

        when (styleName) {
            "stage" -> Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (time != null) ClockText(remOf(2.8f), FontWeight.ExtraLight, .20f, dateSize = remOf(.58f))
                val pill = RoundedCornerShape(16.dp)
                Column(
                    Modifier.clip(pill).background(rgba(0, 0, 0, .28f), pill).border(1.dp, Color.White.copy(alpha = .1f), pill)
                        .padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            HaIcon(icon, size = 20.dp, tint = Color.White.copy(alpha = .95f))
                            Temp(remOf(1.2f), FontWeight.Normal)
                        }
                        Box(Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = .18f)))
                        Box(Modifier.padding(start = 8.dp)) { Cond(remOf(.65f)) }
                    }
                    val items = listOfNotNull(data.high?.let { "↑$it" }, data.low?.let { "↓$it" }, data.aqi?.let { "🌿 $it" }, data.humidity?.let { "💧 $it" })
                    if (items.isNotEmpty()) {
                        Box(Modifier.padding(top = 3.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = .12f)))
                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { items.forEach { Meta(it, Color.White.copy(alpha = .8f * .85f), remOf(.6f)) } }
                    }
                }
            }
            "split" -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    HaIcon(icon, size = 30.dp, tint = Color.White.copy(alpha = .95f))
                    Temp(remOf(1.8f), FontWeight.Light)
                    MinMax()
                    data.wind?.let { Meta("💨 $it") }
                    Cond()
                }
                if (time != null) Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 8.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                    ClockText(remOf(1.55f), FontWeight.Light, .10f)
                }
            }
            "cinematic" -> Column(
                Modifier.heightIn(min = 90.dp).background(Brush.horizontalGradient(listOf(rgba(0, 0, 0, .72f), Color.Transparent)))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                if (time != null) ClockText(remOf(1.15f), FontWeight.Normal, .08f, Alignment.Start, remOf(.52f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        HaIcon(icon, size = 15.dp, tint = Color.White.copy(alpha = .95f))
                        Temp(remOf(.95f), FontWeight.Medium)
                    }
                    MinMax()
                    data.wind?.let { Box(Modifier.graphicsLayer { alpha = .8f }) { Meta("💨 $it") } }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = .25f)))
                    Cond(remOf(.58f), upper = true)
                }
            }
            else -> Row(
                Modifier.fillMaxWidth().padding(horizontal = if (styleName == "wallpanel") 12.dp else 10.dp, vertical = if (styleName == "wallpanel") 10.dp else 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top,
            ) {
                Badge(Modifier.widthIn(min = if (styleName == "wallpanel") 88.dp else 70.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(if (styleName == "wallpanel") 3.dp else 2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (styleName == "wallpanel") 5.dp else 4.dp)) {
                            HaIcon(icon, size = st.icon, tint = Color.White.copy(alpha = .95f))
                            Temp()
                        }
                        MinMax(showHiLo = styleName != "wallpanel")
                        if (styleName != "wallpanel") data.wind?.let { Meta("💨 $it") }
                        Cond()
                    }
                }
                if (time != null) Badge { ClockText() }
            }
        }
    }
}

// --- Prévisions --------------------------------------------------------------------------------------------

@Composable
private fun ForecastWidget(days0: List<Forecast>, t: Long, shape: Shape, i18n: AtcI18n, locale: Locale, modifier: Modifier = Modifier) {
    val days = days0.take(7)
    if (days.isEmpty()) {
        EmptyWidget("mdi:calendar-week", i18n.t("clear_weather_entity_label"), shape, modifier)
        return
    }
    val accent = COND_COLOR[days.first().condition] ?: hex(0x5090d8)
    val pulse = breathe(phase(t, 5000))
    val line = .7f + .3f * breathe(phase(t, 3000))
    val float = breathe(phase(t, 4000))
    val zone = ZoneId.systemDefault()
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(shape).drawBehind {
            drawRect(cssLinear(145f, size.width, size.height, listOf(0f to hex(0x060b18), .5f to hex(0x0c1830), 1f to hex(0x081022))))
            val r = maxOf(size.width, size.height) * .65f * (1f + .08f * pulse)
            ellipseGlow(size.width / 2, size.height * 1.2f, r, r * size.height / size.width * 1.6f, listOf(0f to accent, .65f to Color.Transparent), .12f + .13f * pulse)
        },
    ) {
        days.forEachIndexed { idx, day ->
            val today = idx == 0
            val dt = Instant.ofEpochMilli(day.time).atZone(zone)
            val name = if (today) i18n.t("today").uppercase() else dt.format(DateTimeFormatter.ofPattern("EEE", locale)).uppercase()
            val dateLabel = dt.format(DateTimeFormatter.ofPattern("d MMM", locale))
            val hi = day.temperature?.roundToInt()
            val lo = day.templow?.roundToInt()
            val precip = day.precipitationProbability?.roundToInt()
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .then(if (today) Modifier.background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .08f), Color.White.copy(alpha = .03f)))) else Modifier)
                    .drawBehind {
                        if (idx < days.lastIndex) drawRect(Color.White.copy(alpha = if (today) .10f else .05f), Offset(size.width - density, 0f), Size(density, size.height))
                        if (today) drawRect(
                            Brush.horizontalGradient(listOf(Color.Transparent, accent, Color.Transparent)),
                            size = Size(size.width, 2 * density), alpha = line,
                        )
                    }
                    .padding(horizontal = 2.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = remOf(.58f), fontWeight = FontWeight.Bold, letterSpacing = .08.em, color = if (today) accent.copy(alpha = .95f) else Color.White.copy(alpha = .5f)),
                )
                Text(
                    EMOJI[day.condition] ?: "🌡️", fontSize = if (today) remOf(2.4f) else remOf(2f),
                    modifier = Modifier.padding(bottom = 4.dp).graphicsLayer { if (today) translationY = -3 * density * float },
                )
                Column(Modifier.padding(top = 7.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val sh = Shadow(Color.Black.copy(alpha = .7f), Offset(0f, 2f), 8f)
                    if (hi != null) Text("$hi°", style = TextStyle(fontSize = if (today) remOf(1.4f) else remOf(1.2f), fontWeight = FontWeight.Bold, color = tempColor(hi), shadow = sh), maxLines = 1)
                    if (lo != null) Text("$lo°", style = TextStyle(fontSize = if (today) remOf(1.05f) else remOf(.95f), fontWeight = FontWeight.Medium, color = tempColor(lo).copy(alpha = .7f), shadow = sh), maxLines = 1)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    dateLabel, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = if (today) remOf(.72f) else remOf(.65f), fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = if (today) .75f else .5f)),
                )
                if (precip != null && precip >= 20) Row(Modifier.height(22.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(Modifier.size(3.dp, 18.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .1f)), contentAlignment = Alignment.BottomCenter) {
                        Box(Modifier.fillMaxWidth().height((18f * precip / 100f).dp).background(Brush.verticalGradient(listOf(hex(0x82b1ff), hex(0x40c4ff)))))
                    }
                    Text("$precip%", style = TextStyle(fontSize = remOf(.42f), fontWeight = FontWeight.SemiBold, color = hex(0x82b1ff)))
                }
            }
        }
    }
}
