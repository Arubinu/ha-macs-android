package fr.arubinu.hadash.ui.cards.alerts

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Position [0, 1) dans un cycle de [period] ms, décalé de [delay] ms. */
fun phase(t: Long, period: Int, delay: Int = 0): Float {
    if (period <= 0) return 0f
    val x = ((t - delay) % period + period) % period
    return x / period.toFloat()
}

/** Respiration 0 → 1 → 0 (keyframes 0 %, 50 %, 100 %, ease-in-out). */
fun breathe(p: Float): Float = 0.5f - 0.5f * cos(2f * PI.toFloat() * p)

/** Interpolation linéaire entre des paires (instant, valeur) d'une animation CSS. */
fun keyframes(p: Float, vararg stops: Pair<Float, Float>): Float {
    if (p <= stops.first().first) return stops.first().second
    for (i in 1 until stops.size) {
        val (t1, v1) = stops[i]
        val (t0, v0) = stops[i - 1]
        if (p <= t1) {
            val k = if (t1 == t0) 1f else (p - t0) / (t1 - t0)
            val e = k * k * (3 - 2 * k) // ease-in-out
            return v0 + (v1 - v0) * e
        }
    }
    return stops.last().second
}

private fun rgba(r: Int, g: Int, b: Int, a: Float) = Color(r, g, b, (a.coerceIn(0f, 1f) * 255).toInt())

/**
 * Décors animés des thèmes (pseudo-éléments CSS de la carte d'origine). [t] = horloge en ms,
 * [d] = densité (px par dp).
 */
fun DrawScope.drawDecor(decor: Decor, t: Long, d: Float, accent: Color) {
    val w = size.width
    val h = size.height
    when (decor) {
        Decor.Strobe -> if (phase(t, 500) >= 0.5f) drawRect(rgba(255, 23, 68, 0.10f))
        Decor.LightningFlash -> {
            drawRect(Brush.linearGradient(listOf(rgba(124, 77, 255, 0.06f), rgba(0, 200, 255, 0.06f)), Offset.Zero, Offset(w, h)))
            val p = phase(t, 3000)
            if (p in 0.86f..0.90f || p in 0.92f..0.95f) drawRect(rgba(255, 255, 255, if (p < 0.9f) 0.18f else 0.10f))
            val a = keyframes(p, 0f to 0.05f, 0.84f to 0.05f, 0.88f to 0.45f, 1f to 0.05f)
            drawEmoji("⚡", w - 14 * d, h / 2, 56f * d, a)
        }
        Decor.NeonScan -> {
            val p = phase(t, 3000)
            val x = -w + 2 * w * p
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xFF00FFFF), Color(0xFFFF00FF), Color.Transparent), x, x + w), Offset(x, 0f), Size(w, 2 * d))
        }
        Decor.HoloGrid -> {
            val step = 22 * d
            val c = rgba(0, 200, 255, 0.06f)
            var x = 0f; while (x < w) { drawLine(c, Offset(x, 0f), Offset(x, h), d); x += step }
            var y = 0f; while (y < h) { drawLine(c, Offset(0f, y), Offset(w, y), d); y += step }
        }
        Decor.HoloScan -> {
            val p = phase(t, 2800)
            val y = -3 * d + (h + 3 * d) * keyframes(p, 0f to 0f, 1f to 1f)
            val a = keyframes(p, 0f to 0f, 0.08f to 1f, 0.92f to 1f, 1f to 0f)
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, rgba(0, 200, 255, 0.85f * a), Color.Transparent)), Offset(0f, y), Size(w, 3 * d))
        }
        Decor.NuclearPulse -> {
            val a = 0.45f + 0.55f * breathe(phase(t, 2500))
            drawRect(Brush.radialGradient(listOf(rgba(249, 168, 37, 0.12f * a), Color.Transparent), Offset(w / 2, h / 2), maxOf(w, h) * 0.55f * 0.7f))
        }
        Decor.Radar -> {
            val r = 36 * d
            val c = Offset(w - 12 * d - r, h / 2)
            drawCircle(rgba(255, 160, 0, 0.25f), r, c, style = Stroke(d))
            drawCircle(rgba(255, 160, 0, 0.25f), r * 0.66f, c, style = Stroke(d))
            drawCircle(rgba(255, 160, 0, 0.3f), r * 0.33f, c, style = Stroke(d))
            rotate(360f * phase(t, 3000) - 90f, c) {
                drawArc(Brush.sweepGradient(listOf(rgba(255, 160, 0, 0.55f), Color.Transparent, Color.Transparent, Color.Transparent), c), 0f, 80f, true, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r))
            }
            drawCircle(Color(0xFFFFA000), 2.5f * d, c)
        }
        Decor.Confetti -> {
            val parts = listOf(
                Triple(0.08f, 0xFF69F0AE, 5f), Triple(0.20f, 0xFFFFEB3B, 4f), Triple(0.35f, 0xFFF48FB1, 6f), Triple(0.50f, 0xFF64B5F6, 5f),
                Triple(0.65f, 0xFFFFCC02, 4f), Triple(0.80f, 0xFFCE93D8, 5f), Triple(0.15f, 0xFF80CBC4, 3f), Triple(0.58f, 0xFFFFCC02, 5f),
            )
            parts.forEachIndexed { i, (x, col, s) ->
                val p = phase(t, 2800, i * 350)
                val a = keyframes(p, 0f to 0f, 0.08f to 1f, 0.92f to 1f, 1f to 0f)
                drawCircle(Color(col).copy(alpha = a), s * d / 2, Offset(w * x, h + 8 * d - 58 * d * p))
            }
        }
        Decor.Ecg -> {
            val bh = 28 * d
            val y0 = h - bh / 2
            val seg = 120 * d
            val off = -(phase(t, 2000) * seg)
            val path = Path()
            var x = off
            path.moveTo(x, y0)
            while (x < w + seg) {
                path.lineTo(x + seg * 0.35f, y0); path.lineTo(x + seg * 0.42f, y0 - bh * 0.15f); path.lineTo(x + seg * 0.47f, y0 + bh * 0.1f)
                path.lineTo(x + seg * 0.52f, y0 - bh * 0.45f); path.lineTo(x + seg * 0.57f, y0 + bh * 0.35f); path.lineTo(x + seg * 0.62f, y0)
                path.lineTo(x + seg, y0)
                x += seg
            }
            drawPath(path, rgba(0, 200, 83, 0.45f), style = Stroke(1.5f * d))
        }
        Decor.Scanlines -> {
            val p = phase(t, 9000)
            val a = if (p in 0.93f..0.94f) 0.4f else if (p in 0.96f..0.97f) 0.7f else 1f
            var y = 2 * d; while (y < h) { drawRect(rgba(0, 0, 0, 0.22f * a), Offset(0f, y), Size(w, 2 * d)); y += 4 * d }
        }
        Decor.MotionLines -> {
            val a = if (phase(t, 180) < 0.5f) 1f else 0.82f
            var y = 3 * d; while (y < h) { drawRect(rgba(0, 255, 65, 0.04f * a), Offset(0f, y), Size(w, d)); y += 4 * d }
        }
        Decor.Waves -> {
            val off = 22 * d * phase(t, 1600) - 22 * d
            var x = off; while (x < w) { drawRect(rgba(0, 188, 212, 0.25f), Offset(x + 18 * d, h - 18 * d), Size(4 * d, 18 * d)); x += 22 * d }
        }
        Decor.Stripes -> diagonal(rgba(255, 23, 68, 0.06f), 14 * d, 2 * d, 45f)
        Decor.Bubbles -> listOf(Triple(0.15f, 14f, 2800 to 0), Triple(0.38f, 9f, 3500 to 700), Triple(0.60f, 18f, 2500 to 1400), Triple(0.80f, 7f, 3200 to 2100))
            .forEach { (x, s, pd) ->
                val p = phase(t, pd.first, pd.second)
                val a = keyframes(p, 0f to 0f, 0.15f to 0.85f, 0.85f to 0.35f, 1f to 0f)
                val sc = 1f - 0.6f * p
                val r = s * d / 2 * sc
                val c = Offset(w * x + s * d / 2, h + s * d / 2 - 75 * d * p)
                drawCircle(Brush.radialGradient(listOf(rgba(57, 255, 20, 0.3f * a), Color.Transparent), c, r.coerceAtLeast(0.1f) * 1.4f), r * 1.4f, c)
            }
        Decor.TempBar -> {
            val a = 0.7f + 0.3f * breathe(phase(t, 2000))
            drawRect(Brush.horizontalGradient(listOf(Color(0xFFFF5722), Color(0xFFFF8F00), Color(0xFFFFCA28))), Offset(0f, h - 4 * d), Size(w, 4 * d), alpha = a)
        }
        Decor.Drain -> drawRoundRect(rgba(255, 111, 0, 0.18f), Offset(18 * d, h - 7 * d), Size(w - 36 * d, 3 * d), CornerRadius(2 * d))
        Decor.DoorRay -> {
            val p = breathe(phase(t, 2500))
            val rw = (20 + 25 * p) * d
            drawRect(Brush.horizontalGradient(listOf(rgba(255, 214, 0, 0.15f * (0.5f + 0.5f * p)), Color.Transparent), 52 * d, 52 * d + rw), Offset(52 * d, 0f), Size(rw, h))
        }
        Decor.WindowShimmer -> {
            val x = w * keyframes(phase(t, 3000), 0f to -0.4f, 0.5f to 1.1f, 1f to -0.4f)
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, rgba(128, 216, 255, 0.12f), Color.Transparent), x, x + w * 0.3f), Offset(x, 0f), Size(w * 0.3f, h))
        }
        Decor.PresencePing -> {
            val c = Offset(w - 18 * d - 31 * d, h / 2)
            for (k in 0..1) {
                val p = phase(t, 2200, k * 1100)
                drawCircle(rgba(0, 188, 212, 0.7f * (1 - p)), (4 + 26 * p) * d, c, style = Stroke(d))
            }
        }
        Decor.UpdateRing -> {
            val r = 27 * d
            val c = Offset(w - 16 * d - r, h / 2)
            drawCircle(rgba(0, 229, 255, 0.15f), r - 1.5f * d, c, style = Stroke(3 * d))
            rotate(360f * phase(t, 1300), c) { drawArc(Color(0xFF00E5FF), -135f, 90f, false, Offset(c.x - r + 1.5f * d, c.y - r + 1.5f * d), Size(2 * r - 3 * d, 2 * r - 3 * d), style = Stroke(3 * d)) }
        }
        Decor.ShieldScan, Decor.LockScan -> {
            val period = if (decor == Decor.ShieldScan) 3000 else 4000
            val col = if (decor == Decor.ShieldScan) rgba(0, 200, 83, 0.12f) else rgba(21, 101, 192, 0.1f)
            val top = -h + 3 * h * phase(t, period)
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, col, Color.Transparent), top, top + h), Offset(0f, top), Size(w, h))
        }
        Decor.PowerLines -> stripesH(rgba(118, 255, 3, 0.05f), 40 * d, 2 * d, 40 * d * phase(t, 1500))
        Decor.CyberLines -> diagonal(rgba(156, 39, 176, 0.07f), 11 * d, d, -45f)
        Decor.CyberGlitch -> {
            val p = phase(t, 5000)
            if (p in 0.89f..0.95f) {
                val (y, hh) = when {
                    p < 0.91f -> 0.28f to 1f
                    p < 0.93f -> 0.62f to 3f
                    else -> 0.18f to 1f
                }
                drawRect(Color(0xFF00E5FF), Offset(0f, h * y), Size(w, hh * d))
            }
        }
        Decor.VaporPulse -> drawRect(Brush.linearGradient(listOf(rgba(255, 0, 255, 0.06f), rgba(0, 255, 255, 0.06f)), Offset.Zero, Offset(w, h)), alpha = 0.5f + 0.5f * breathe(phase(t, 3000)))
        Decor.VaporGrid -> {
            val gh = 26 * d
            var y = h - gh; while (y < h) { drawRect(rgba(255, 0, 255, 0.18f), Offset(0f, y), Size(w, d)); y += 8 * d }
            var x = 0f; while (x < w) { drawRect(rgba(0, 255, 255, 0.1f), Offset(x, h - gh), Size(d, gh)); x += 22 * d }
        }
        Decor.Lava -> listOf(Triple(0.10f, 65f, 0), Triple(0.52f, 48f, 1800), Triple(0.76f, 38f, 3500)).forEach { (x, s, dl) ->
            val p = breathe(phase(t, 5000, dl))
            val r = s * d / 2 * (1 + 0.15f * p)
            val c = Offset(w * x + s * d / 2, h + s * d * 0.38f - 28 * d * p - s * d / 2 + s * d / 2)
            drawCircle(Brush.radialGradient(listOf(rgba(255, 87, 34, 0.38f * (0.5f + 0.35f * p / 0.85f + 0.15f)), Color.Transparent), c, r), r, c)
        }
        Decor.Smoke -> listOf(Triple(0.08f, 70f to 50f, 0), Triple(0.42f, 55f to 40f, 2000), Triple(0.70f, 45f to 35f, 4000)).forEach { (x, sz, dl) ->
            val p = breathe(phase(t, 6000, dl))
            val c = Offset(w * x + sz.first * d / 2 + 8 * d * p, h - sz.second * d / 2 + 10 * d - 22 * d * p)
            val r = sz.first * d / 2 * (1 + 0.2f * p)
            drawCircle(Brush.radialGradient(listOf(rgba(180, 180, 180, 0.18f * (0.45f + 0.4f * p)), Color.Transparent), c, r), r, c)
        }
        Decor.WindLines -> {
            val off = 63 * d * phase(t, 800) - 63 * d
            var x = off
            while (x < w) {
                drawRect(rgba(100, 200, 255, 0.07f), Offset(x + 28 * d, 0f), Size(4 * d, h))
                drawRect(rgba(100, 200, 255, 0.04f), Offset(x + 60 * d, 0f), Size(3 * d, h))
                x += 63 * d
            }
        }
        Decor.Leak -> listOf(0.20f to 0, 0.50f to 1200, 0.76f to 2400).forEach { (x, dl) ->
            val p = phase(t, 3500, dl)
            val a = keyframes(p, 0f to 0f, 0.1f to 1f, 0.8f to 0.8f, 1f to 0f)
            drawOval(rgba(30, 136, 229, 0.65f * a), Offset(w * x, -10 * d + 80 * d * p * p), Size(6 * d, 10 * d))
        }
        Decor.CloudPulse -> drawRect(Brush.radialGradient(listOf(rgba(144, 164, 174, 0.07f), Color.Transparent), Offset(w * 0.3f, h * 0.6f), maxOf(w, h) * 0.5f), alpha = 0.5f + 0.5f * breathe(phase(t, 4000)))
        Decor.SatWaves -> {
            val c = Offset(w - 22 * d, h / 2)
            for (k in 0..2) {
                val p = phase(t, 2000, k * 660)
                drawCircle(rgba(0, 230, 118, 0.7f * (1 - p)), (2 + 25.5f * p) * d, c, style = Stroke(1.5f * d))
            }
        }
        Decor.TipsGlow -> {
            val a = 0.5f + 0.5f * breathe(phase(t, 3000))
            val c = Offset(30 * d + 30 * d, h / 2)
            drawOval(Brush.radialGradient(listOf(rgba(255, 193, 7, 0.12f * a), Color.Transparent), c, 70 * d), Offset(30 * d, -0.2f * h), Size(60 * d, 1.4f * h))
        }
        Decor.SunRay -> drawRect(Brush.verticalGradient(listOf(Color.Transparent, rgba(255, 160, 0, 0.14f)), h * 0.45f, h), Offset(0f, h * 0.45f), Size(w, h * 0.55f), alpha = 0.6f + 0.4f * breathe(phase(t, 4000)))
        Decor.PlantBar -> {
            val p = breathe(phase(t, 3000))
            val shift = w * p
            drawRect(Brush.horizontalGradient(listOf(Color(0xFF00C853), Color(0xFF69F0AE), Color(0xFF00C853)), -shift, 2 * w - shift), Offset(0f, h - 4 * d), Size(w, 4 * d))
        }
        Decor.LightBeam -> {
            val a = 0.5f + 0.5f * breathe(phase(t, 2800))
            val bw = w * 0.55f
            val path = Path().apply { moveTo(-10 * d, h / 2); lineTo(-10 * d + bw, 0f); lineTo(-10 * d + bw, h); close() }
            drawPath(path, Brush.horizontalGradient(listOf(rgba(255, 214, 0, 0.2f), Color.Transparent), -10 * d, -10 * d + bw), alpha = a)
        }
        Decor.Notes -> listOf("♪", "♫", "♩", "♬").forEachIndexed { i, n ->
            val p = phase(t, 3000, i * 750)
            val a = keyframes(p, 0f to 0f, 0.2f to 0.85f, 0.8f to 0.4f, 1f to 0f)
            drawEmoji(n, w * (0.15f + 0.22f * i), h + 20 * d - 75 * d * p, 16 * d, a, accent)
        }
        Decor.Portal -> {
            val c = Offset(w / 2, h / 2)
            val r1 = maxOf(w, h) * 1.1f
            rotate(360f * phase(t, 3000), c) {
                drawCircle(Brush.sweepGradient(listOf(Color.Transparent, rgba(255, 23, 68, 0.5f), rgba(220, 0, 60, 0.3f), Color.Transparent, rgba(180, 0, 40, 0.25f), Color.Transparent), c), r1, c)
            }
            rotate(-360f * phase(t, 1800) + 90f, c) {
                drawCircle(Brush.sweepGradient(listOf(Color.Transparent, rgba(255, 100, 120, 0.4f), Color.Transparent, rgba(200, 0, 70, 0.3f), Color.Transparent), c), r1 * 0.68f, c)
            }
        }
        Decor.Void -> {
            val a = 0.5f + 0.5f * breathe(phase(t, 3000))
            val c = Offset(w * (-0.08f + 0.275f), h / 2)
            drawOval(Brush.radialGradient(listOf(Color.Transparent, rgba(170, 0, 255, 0.35f * a), rgba(100, 0, 200, 0.15f * a), Color.Transparent), c, w * 0.3f),
                Offset(c.x - w * 0.275f, c.y - h * 0.7f), Size(w * 0.55f, h * 1.4f))
            val dc = Offset(w * (-0.03f + 0.22f), h / 2)
            translate(dc.x, dc.y) {
                scale(1f, 0.28f, Offset.Zero) {
                    rotate(360f * phase(t, 3500), Offset.Zero) {
                        drawCircle(Brush.sweepGradient(listOf(Color.Transparent, rgba(213, 0, 249, 0.6f), rgba(140, 0, 200, 0.8f), rgba(80, 0, 150, 0.5f), Color.Transparent, rgba(60, 0, 120, 0.3f), Color.Transparent), Offset.Zero), w * 0.22f, Offset.Zero)
                    }
                }
            }
        }
        Decor.VoltGrid -> {
            val off = (40 * d * phase(t, 1200)) % (4 * d)
            var y = off + 3 * d; while (y < h) { drawRect(rgba(249, 249, 38, 0.045f), Offset(0f, y), Size(w, d)); y += 4 * d }
        }
        Decor.VoltZap -> {
            val p = phase(t, 1200)
            val a = keyframes(p, 0f to 0f, 0.74f to 0f, 0.8f to 1f, 1f to 0f)
            if (a > 0.01f) drawRect(Brush.verticalGradient(listOf(Color.Transparent, rgba(249, 249, 38, 0.7f), rgba(255, 255, 120, 1f), rgba(249, 249, 38, 0.7f), Color.Transparent)), Offset(w * 0.18f, 0f), Size(2 * d, h), alpha = a)
        }
        Decor.Nebula -> {
            data class Gas(val x: Float, val y: Float, val gw: Float, val gh: Float, val col: Color, val period: Int)
            listOf(
                Gas(-15f, -25f, 130f, 90f, rgba(206, 147, 216, 0.5f), 7000), Gas(50f, 15f, 110f, 75f, rgba(100, 181, 246, 0.4f), 9000),
                Gas(-1f, -10f, 95f, 65f, rgba(178, 223, 219, 0.35f), 11000),
            ).forEachIndexed { i, g ->
                val p = phase(t, g.period)
                val dx = sin(2 * PI * p).toFloat() * 10 * d
                val dy = cos(2 * PI * p).toFloat() * 7 * d
                val left = if (g.x < 0 && i == 2) w - 10 * d - g.gw * d else g.x * d
                val c = Offset(left + g.gw * d / 2 + dx, g.y * d + g.gh * d / 2 + dy)
                drawOval(Brush.radialGradient(listOf(g.col.copy(alpha = g.col.alpha * (0.6f + 0.4f * breathe(p))), Color.Transparent), c, g.gw * d * 0.6f),
                    Offset(c.x - g.gw * d * 0.7f, c.y - g.gh * d * 0.7f), Size(g.gw * d * 1.4f, g.gh * d * 1.4f))
            }
        }
        Decor.PrismFacets -> {
            drawRect(Brush.linearGradient(listOf(rgba(255, 255, 255, 0.04f), Color.Transparent), Offset.Zero, Offset(w * 0.35f, h * 0.35f)))
        }
        Decor.PrismSweep -> {
            val x = w * keyframes(phase(t, 4000), 0f to -0.5f, 1f to 1.5f)
            val sw = w * 0.45f
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, rgba(255, 30, 120, 0.14f), rgba(255, 165, 0, 0.12f), rgba(255, 240, 0, 0.12f),
                rgba(0, 255, 100, 0.10f), rgba(0, 200, 255, 0.12f), rgba(120, 0, 255, 0.10f), Color.Transparent), x, x + sw), Offset(x, 0f), Size(sw, h))
        }
        Decor.ArcadeGrid -> {
            val step = 28 * d
            val off = step * phase(t, 1800)
            val c = rgba(0, 229, 255, 0.28f)
            val horizon = h * 0.15f
            var y = off; while (y < h * 3) {
                // Perspective : les lignes se resserrent vers l'horizon.
                val yy = horizon + (h - horizon) * (y / (h * 3)).let { it * it }
                drawLine(c.copy(alpha = 0.28f * (yy - horizon) / (h - horizon)), Offset(0f, yy), Offset(w, yy), d)
                y += step
            }
            var k = -10; while (k <= 10) {
                val xb = w / 2 + k * step * 1.6f
                drawLine(c.copy(alpha = 0.18f), Offset(w / 2 + k * step * 0.25f, horizon), Offset(xb, h), d)
                k++
            }
        }
        Decor.ArcadeScan -> {
            val x = w * keyframes(phase(t, 3000), 0f to -0.5f, 1f to 1.5f)
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, rgba(0, 229, 255, 0.18f), Color.Transparent), x, x + w * 0.45f), Offset(x, 0f), Size(w * 0.45f, h))
        }
        Decor.DiamondFacets -> {
            drawRect(Brush.linearGradient(listOf(rgba(255, 255, 255, 0.07f), Color.Transparent), Offset.Zero, Offset(w * 0.38f, h * 0.38f)))
            drawRect(Brush.linearGradient(listOf(rgba(128, 222, 234, 0.06f), Color.Transparent), Offset(w, 0f), Offset(w * 0.62f, h * 0.38f)))
            drawRect(Brush.linearGradient(listOf(rgba(100, 180, 200, 0.05f), Color.Transparent), Offset(w, h), Offset(w * 0.62f, h * 0.62f)))
        }
        Decor.DiamondShimmer -> {
            val x = w * keyframes(phase(t, 3500), 0f to -0.3f, 1f to 1.3f)
            val sw = w * 0.22f
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, rgba(255, 255, 255, 0.28f), rgba(200, 240, 255, 0.18f), rgba(255, 255, 255, 0.22f), Color.Transparent), x, x + sw), Offset(x, 0f), Size(sw, h))
        }
        Decor.Quantum -> {
            val c = Offset(5 * d + 33 * d, h / 2)
            listOf(Triple(33f, rgba(105, 240, 174, 0.55f), 2800 to 1f), Triple(26f, rgba(0, 229, 255, 0.45f), 1900 to -1f)).forEachIndexed { i, (r, col, pd) ->
                val ang = 360f * phase(t, pd.first) * pd.second + i * 55f
                translate(c.x, c.y) {
                    rotate(ang, Offset.Zero) { scale(1f, 0.37f, Offset.Zero) { drawCircle(col, r * d, Offset.Zero, style = Stroke(1.5f * d)) } }
                }
            }
            val s = 1f + 0.35f * breathe(phase(t, 1500))
            drawCircle(Brush.radialGradient(listOf(Color.White, rgba(105, 240, 174, 0.9f), Color.Transparent), Offset(34 * d + 4 * d, h / 2), 4 * d * s), 4 * d * s, Offset(34 * d + 4 * d, h / 2))
        }
        Decor.Rain -> {
            val off = phase(t, 450)
            val dx = -5 * d * off
            val dy = 18 * d * off
            val c = rgba(120, 170, 255, 0.1f)
            var x = -h; while (x < w + h) {
                val sx = x + dx
                drawLine(c, Offset(sx, -4 * d + dy - 18 * d), Offset(sx - h * 0.176f, h + dy), d)
                x += 6 * d
            }
        }
        Decor.StormFlash -> {
            val p = phase(t, 3500)
            if ((p >= 0.80f && p < 0.81f) || (p >= 0.82f && p < 0.83f)) drawRect(rgba(220, 235, 255, 0.08f))
            else if (p >= 0.81f && p < 0.82f) drawRect(rgba(220, 235, 255, 0.016f))
        }
        Decor.Snow -> {
            val off = 72 * d * phase(t, 4000) - 36 * d
            listOf(Triple(36f, 1.5f, rgba(200, 230, 255, 0.65f) to Offset(0f, 0f)), Triple(58f, 1f, rgba(180, 215, 255, 0.45f) to Offset(14f, 10f)), Triple(82f, 2f, rgba(220, 240, 255, 0.35f) to Offset(28f, 20f)))
                .forEach { (step, r, cp) ->
                    var y = cp.second.y * d - step * d + off
                    while (y < h + step * d) {
                        var x = cp.second.x * d + step * d / 2
                        while (x < w) { drawCircle(cp.first, r * d, Offset(x, y + step * d / 2)); x += step * d }
                        y += step * d
                    }
                }
        }
        Decor.FrostShimmer -> {
            val dx = w * keyframes(phase(t, 5000), 0f to -1.5f, 0.5f to 1.5f, 1f to -1.5f)
            drawRect(Brush.linearGradient(listOf(Color.Transparent, rgba(200, 235, 255, 0.07f), Color.Transparent), Offset(dx + w * 0.38f, 0f), Offset(dx + w * 0.62f, h * 0.2f)))
        }
        Decor.Aurora -> {
            val p = breathe(phase(t, 6000))
            val shift = w * p
            drawRect(Brush.linearGradient(listOf(rgba(0, 200, 140, 0.13f), rgba(0, 150, 255, 0.11f), rgba(120, 0, 255, 0.09f), rgba(0, 200, 140, 0.13f)),
                Offset(-shift, 0f), Offset(2 * w - shift, h)))
        }
        Decor.WarnDot -> Unit // dessiné dans la colonne de droite
    }
}

private fun DrawScope.diagonal(color: Color, period: Float, width: Float, angle: Float) {
    clipRect {
        rotate(angle, Offset(size.width / 2, size.height / 2)) {
            val ext = size.width + size.height
            var x = -ext; while (x < ext) { drawRect(color, Offset(size.width / 2 + x, size.height / 2 - ext), Size(width, 2 * ext)); x += period }
        }
    }
}

private fun DrawScope.stripesH(color: Color, period: Float, width: Float, offset: Float) {
    var x = offset - period
    while (x < size.width) { drawRect(color, Offset(x + period - width, 0f), Size(width, size.height)); x += period }
}

/** Glyphe (emoji, note de musique) centré verticalement en [x], [y]. */
private fun DrawScope.drawEmoji(s: String, x: Float, y: Float, sizePx: Float, alpha: Float, color: Color = Color.White) {
    val paint = android.graphics.Paint().apply {
        textSize = sizePx
        isAntiAlias = true
        this.color = android.graphics.Color.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
        textAlign = android.graphics.Paint.Align.RIGHT
    }
    drawContext.canvas.nativeCanvas.drawText(s, x, y + sizePx * 0.35f, paint)
}
