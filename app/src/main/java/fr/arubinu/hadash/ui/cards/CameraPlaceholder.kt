package fr.arubinu.hadash.ui.cards

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import fr.arubinu.hadash.R
import kotlin.math.roundToInt

/** Écran d'attente / d'erreur d'une caméra : fond flou, icône et libellé traduit, entièrement dessinés. */
enum class CameraPlaceholderKind { Loading, Offline }

private val Carlito = FontFamily(Font(R.font.carlito))
private val LOADING_COLOR = Color(0xFF74B3ED)
private val OFFLINE_COLOR = Color(0xFFED7478)

/**
 * Fond : photo floutée réduite à une grille 16 × 9, réinterpolée en bicubique (écart moyen < 1/255 avec
 * l'image d'origine). Calculé une seule fois, puis étiré avec filtrage : aucun fichier image.
 */
private val BG_GRID = intArrayOf(
    0x050D21, 0x06112B, 0x061834, 0x061E3C, 0x052040, 0x042343, 0x042644, 0x022340, 0x011E3B, 0x02203C, 0x03233F, 0x02203C, 0x021C38, 0x021833, 0x01142D, 0x010F24,
    0x0B1222, 0x0C172B, 0x0E1E37, 0x0F2642, 0x0E2A46, 0x0B2C49, 0x092F4B, 0x052A47, 0x032543, 0x042845, 0x052946, 0x052542, 0x06213D, 0x051C38, 0x051731, 0x051127,
    0x161A25, 0x181E2C, 0x1B2639, 0x1D3147, 0x1D364D, 0x193850, 0x143A53, 0x0A334F, 0x052D4A, 0x072F4C, 0x0A2E4B, 0x0D2A46, 0x0E2542, 0x0D1F3B, 0x0C1933, 0x0B132A,
    0x22252B, 0x282B32, 0x2C313B, 0x323C4A, 0x344655, 0x2F4757, 0x25475A, 0x123C55, 0x0B3451, 0x0E3551, 0x14304C, 0x1A2D47, 0x1A2843, 0x1A213C, 0x191C34, 0x151529,
    0x2A2F34, 0x34373B, 0x3D3D40, 0x45464B, 0x4B5359, 0x48565D, 0x3B5360, 0x204359, 0x153B54, 0x1A3550, 0x242F48, 0x2A2D44, 0x2B2941, 0x2D253B, 0x2A1F31, 0x211625,
    0x27313A, 0x333941, 0x404145, 0x4C494A, 0x575556, 0x575B5D, 0x4B5860, 0x334A5A, 0x294155, 0x2B364C, 0x312E43, 0x362D40, 0x3D2E3E, 0x402C39, 0x372430, 0x2D1B25,
    0x192735, 0x25303D, 0x343A43, 0x444347, 0x524D4F, 0x555459, 0x4F555D, 0x4A535D, 0x484E59, 0x3F3E4C, 0x3C3341, 0x423440, 0x4C3941, 0x49363D, 0x3F2E35, 0x34272C,
    0x101E2C, 0x1A2534, 0x2C2F3B, 0x423D43, 0x524A4B, 0x5A5557, 0x5D5C61, 0x686668, 0x706A68, 0x615859, 0x574A4C, 0x5D4C4C, 0x5D4E4D, 0x554949, 0x4B4342, 0x3E3939,
    0x1F262D, 0x242932, 0x37333A, 0x564544, 0x6F5950, 0x7D6A5E, 0x84796F, 0x8E857C, 0x968B80, 0x8D7F74, 0x857367, 0x847366, 0x7D7164, 0x73695E, 0x675D54, 0x574E46,
)

private val background: ImageBitmap by lazy {
    val gw = 16
    val gh = 9
    val w = 192
    val h = 108
    fun cubic(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float =
        p1 + 0.5f * t * (p2 - p0 + t * (2f * p0 - 5f * p1 + 4f * p2 - p3 + t * (3f * (p1 - p2) + p3 - p0)))
    fun channel(x: Int, y: Int, shift: Int): Float =
        ((BG_GRID[y.coerceIn(0, gh - 1) * gw + x.coerceIn(0, gw - 1)] shr shift) and 0xFF).toFloat()
    val px = IntArray(w * h)
    for (j in 0 until h) {
        // Centres de cellules (réduction « box ») : pixel j ↔ coordonnée de grille (j + 0,5) · gh / h − 0,5.
        val gy = (j + 0.5f) * gh / h - 0.5f
        val y0 = kotlin.math.floor(gy).toInt()
        val ty = gy - y0
        for (i in 0 until w) {
            val gx = (i + 0.5f) * gw / w - 0.5f
            val x0 = kotlin.math.floor(gx).toInt()
            val tx = gx - x0
            var argb = 0xFF shl 24
            for (shift in intArrayOf(16, 8, 0)) {
                val rows = FloatArray(4) { k ->
                    val y = y0 - 1 + k
                    cubic(channel(x0 - 1, y, shift), channel(x0, y, shift), channel(x0 + 1, y, shift), channel(x0 + 2, y, shift), tx)
                }
                val v = cubic(rows[0], rows[1], rows[2], rows[3], ty).roundToInt().coerceIn(0, 255)
                argb = argb or (v shl shift)
            }
            px[j * w + i] = argb
        }
    }
    Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * Reproduit les images d'attente / hors ligne d'origine (1280 × 720) : icône de 125 px centrée à 42 % de la
 * hauteur, libellé Carlito (hauteur de capitale 40 px, ligne de base à 481 px). Tout est proportionnel à
 * la hauteur, et le fond est recadré comme une image « crop ».
 */
@Composable
fun CameraPlaceholder(kind: CameraPlaceholderKind, modifier: Modifier) {
    val label = stringResource(if (kind == CameraPlaceholderKind.Loading) R.string.camera_loading else R.string.camera_offline)
    val measurer = rememberTextMeasurer()
    val bg = remember { background }
    Canvas(modifier) {
        // Fond « crop » : couvre la carte en conservant le 16:9 de la photo d'origine.
        val scale = maxOf(size.width / 1280f, size.height / 720f)
        val dw = 1280f * scale
        val dh = 720f * scale
        drawImage(
            bg,
            srcOffset = IntOffset.Zero, srcSize = IntSize(bg.width, bg.height),
            dstOffset = IntOffset(((size.width - dw) / 2).roundToInt(), ((size.height - dh) / 2).roundToInt()),
            dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
            filterQuality = FilterQuality.High,
        )
        val u = size.height / 720f
        val iconSize = 125f * u
        translate(size.width / 2 - iconSize / 2, 240f * u) {
            drawIcon(kind, iconSize)
        }
        // Carlito : hauteur de capitale ≈ 0,632 em → corps de 63 px pour 40 px de capitale.
        val style = TextStyle(color = Color.White, fontFamily = Carlito, fontSize = (63f * u).toSp())
        val text = measurer.measure(label, style, softWrap = false)
        val baseline = text.firstBaseline
        drawText(text, topLeft = Offset((size.width - text.size.width) / 2, 481f * u - baseline))
    }
}

/** Icônes redessinées en vectoriel d'après les originaux 125 × 125 (anneau de 8 px, traits arrondis). */
private fun DrawScope.drawIcon(kind: CameraPlaceholderKind, s: Float) {
    val k = s / 125f
    val color = if (kind == CameraPlaceholderKind.Loading) LOADING_COLOR else OFFLINE_COLOR
    drawCircle(color, radius = 57.6f * k, center = Offset(62.5f * k, 62.5f * k), style = Stroke(width = 7.8f * k))
    if (kind == CameraPlaceholderKind.Loading) {
        val path = Path().apply {
            moveTo(63f * k, 35.5f * k)
            lineTo(63f * k, 64.5f * k)
            lineTo(80.5f * k, 77.5f * k)
        }
        drawPath(path, color, style = Stroke(width = 9.6f * k, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    } else {
        // Point d'exclamation légèrement effilé vers le bas.
        val bar = Path().apply {
            moveTo(57.6f * k, 41f * k)
            cubicTo(57.6f * k, 34f * k, 67.4f * k, 34f * k, 67.4f * k, 41f * k)
            lineTo(65f * k, 72.5f * k)
            cubicTo(64.8f * k, 75.4f * k, 60.2f * k, 75.4f * k, 60f * k, 72.5f * k)
            close()
        }
        drawPath(bar, color)
        drawCircle(color, radius = 5.3f * k, center = Offset(62.5f * k, 85f * k))
    }
}
