package fr.pergens.hamacs.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.pergens.hamacs.ui.theme.Dash
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/*
 * Petites animations du bandeau des membres, dessinées par l'app (aucun fichier image) :
 * traits blancs et détails dans la couleur d'accent, sur une grille de 24 × 24 comme les icônes MDI.
 */

private val Line = Color.White

/** Unité de la grille 24 × 24 et épaisseur de trait. */
private fun DrawScope.u(v: Float) = v * size.minDimension / 24f
private fun DrawScope.stroke() = Stroke(width = u(1.6f), cap = StrokeCap.Round)

/** Globe qui tourne (méridiens) avec un repère de position qui flotte : présence / zone. */
@Composable
fun GlobeAnimation(size: Dp = 28.dp) {
    val accent = Dash.colors.accent
    val t = rememberInfiniteTransition(label = "globe")
    val turn by t.animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "turn")
    val bob by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "bob")
    Canvas(Modifier.size(size)) {
        val c = Offset(u(11f), u(13f))
        val r = u(8.5f)
        drawCircle(Line, r, c, style = stroke())
        val sphere = Path().apply { addOval(Rect(c, r)) }
        clipPath(sphere) {
            // Équateur et deux parallèles.
            drawLine(Line, Offset(c.x - r, c.y), Offset(c.x + r, c.y), u(1.2f))
            for (k in listOf(-1, 1)) {
                val y = c.y + k * r * 0.55f
                val half = r * 0.83f
                drawLine(Line.copy(alpha = 0.7f), Offset(c.x - half, y), Offset(c.x + half, y), u(1f))
            }
            // Méridiens : demi-ellipses face visible, largeur selon l'angle de rotation.
            for (k in 0 until 3) {
                val a = ((turn + k / 3f) % 1f) * PI.toFloat()
                val w = r * cos(a)
                if (abs(w) < u(0.3f)) {
                    drawLine(Line, Offset(c.x, c.y - r), Offset(c.x, c.y + r), u(1.2f))
                } else {
                    drawArc(
                        Line, if (w > 0) -90f else 90f, 180f, false,
                        Offset(c.x - abs(w), c.y - r), Size(abs(w) * 2, r * 2), style = Stroke(u(1.2f)),
                    )
                }
            }
        }
        // Repère de position (goutte), dans la couleur d'accent.
        translate(top = -u(1.2f) * bob) {
            val p = Offset(u(18f), u(5.5f))
            val pin = Path().apply {
                moveTo(p.x, p.y + u(5.5f))
                cubicTo(p.x - u(1.2f), p.y + u(3.5f), p.x - u(3f), p.y + u(2.2f), p.x - u(3f), p.y)
                cubicTo(p.x - u(3f), p.y - u(1.7f), p.x - u(1.7f), p.y - u(3f), p.x, p.y - u(3f))
                cubicTo(p.x + u(1.7f), p.y - u(3f), p.x + u(3f), p.y - u(1.7f), p.x + u(3f), p.y)
                cubicTo(p.x + u(3f), p.y + u(2.2f), p.x + u(1.2f), p.y + u(3.5f), p.x, p.y + u(5.5f))
                close()
            }
            drawPath(pin, Color.Black)
            drawPath(pin, accent, style = stroke())
            drawCircle(accent, u(1f), p)
        }
    }
}

/** Coureur qui rebondit, avec des traits de vitesse : nombre de pas. */
@Composable
fun RunnerAnimation(size: Dp = 28.dp) {
    val accent = Dash.colors.accent
    val t = rememberInfiniteTransition(label = "runner")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "phase")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            // Trois traits de vitesse qui filent vers la gauche et s'estompent.
            for (k in 0 until 3) {
                val p = (phase + k / 3f) % 1f
                val y = u(9f + k * 3.5f)
                val x = u(9f) - p * u(8f)
                val len = u(4f) * (1f - p * 0.5f)
                drawLine(accent.copy(alpha = 1f - p), Offset(x - len, y), Offset(x, y), u(1.4f), StrokeCap.Round)
            }
        }
        val hop = abs(sin(phase * 2 * PI.toFloat()))
        HaIcon("mdi:run", Modifier.padding(start = size * 0.18f, bottom = size * 0.08f * hop), size = size * 0.82f, tint = Line)
    }
}

/**
 * Batterie au niveau réel (3 barres) : en charge, les barres se remplissent en boucle avec un éclair ;
 * niveau faible (≤ 15 %), la dernière barre clignote en rouge.
 */
@Composable
fun BatteryAnimation(level: Int?, charging: Boolean, size: Dp = 28.dp) {
    val accent = Dash.colors.accent
    val t = rememberInfiniteTransition(label = "battery")
    val cycle by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "cycle")
    val blink by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "blink")
    Canvas(Modifier.size(size)) {
        // Boîtier et borne.
        drawRoundRect(Line, Offset(u(7f), u(5f)), Size(u(10f), u(16.5f)), CornerRadius(u(1.8f)), style = stroke())
        drawRoundRect(Line, Offset(u(10f), u(2.6f)), Size(u(4f), u(2.4f)), CornerRadius(u(0.6f)))
        val bars = when {
            charging -> (cycle * 4).toInt().coerceAtMost(3)
            level == null -> 0
            level <= 15 -> 1
            else -> ((level + 32) / 33).coerceIn(1, 3)
        }
        val low = !charging && level != null && level <= 15
        for (i in 0 until bars) {
            val top = u(16.6f) - i * u(4.3f)
            val color = if (low) Color(0xFFFF5252).copy(alpha = blink) else accent
            drawRoundRect(color, Offset(u(9.3f), top), Size(u(5.4f), u(3.2f)), CornerRadius(u(0.6f)))
        }
        if (charging) {
            // Éclair à droite du boîtier.
            val bolt = Path().apply {
                moveTo(u(21.5f), u(7f)); lineTo(u(18.8f), u(12.6f)); lineTo(u(21f), u(12.6f))
                lineTo(u(19.6f), u(18f)); lineTo(u(23f), u(11f)); lineTo(u(20.6f), u(11f)); close()
            }
            drawPath(bolt, accent.copy(alpha = 0.55f + 0.45f * blink))
        }
    }
}
