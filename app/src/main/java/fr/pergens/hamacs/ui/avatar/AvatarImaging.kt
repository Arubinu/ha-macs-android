package fr.pergens.hamacs.ui.avatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Plus grand côté des images traitées (détourage, filtres) : largement assez pour un avatar. */
const val WORK_SIZE = 768

private const val TAG = "Avatar"

/** Réduit l'image pour que son plus grand côté fasse au plus [max] pixels. */
fun Bitmap.fit(max: Int = WORK_SIZE): Bitmap {
    val s = max.toFloat() / maxOf(width, height)
    if (s >= 1f) return copy(Bitmap.Config.ARGB_8888, true)
    return Bitmap.createScaledBitmap(this, (width * s).roundToInt(), (height * s).roundToInt(), true)
}

/** Lit une photo (fichier choisi sur l'appareil) en tenant compte de son orientation (EXIF). */
suspend fun decodePhoto(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        decodePhoto(bytes)
    }.onFailure { Log.w(TAG, "photo", it) }.getOrNull()
}

/** Décodage réduit + orientation EXIF. */
fun decodePhoto(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= WORK_SIZE * 2) sample *= 2
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val orientation = runCatching { ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val m = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        else -> return bmp.fit()
    }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).fit()
}

// Détourage et détection de visage : `Cutout.kt` de chaque variante (src/full : ML Kit, src/lite : sans).

/** Remet l'opacité de [mask] (image détourée) sur [image] (même taille), ex. après un filtre. */
fun Bitmap.withAlphaOf(mask: Bitmap): Bitmap {
    val out = copy(Bitmap.Config.ARGB_8888, true)
    val m = if (mask.width == width && mask.height == height) mask else Bitmap.createScaledBitmap(mask, width, height, true)
    val a = IntArray(width * height)
    val p = IntArray(width * height)
    m.getPixels(a, 0, width, 0, 0, width, height)
    out.getPixels(p, 0, width, 0, 0, width, height)
    for (i in p.indices) p[i] = (p[i] and 0x00FFFFFF) or (a[i] and 0xFF000000.toInt())
    // Copie d'une photo JPEG : marquée « sans transparence », l'opacité serait ignorée au dessin.
    out.setHasAlpha(true)
    out.setPixels(p, 0, width, 0, 0, width, height)
    return out
}

/** Zone non transparente d'une image détourée (gauche, haut, droite, bas), ou null. */
fun Bitmap.opaqueBounds(): IntArray? {
    val p = IntArray(width * height)
    getPixels(p, 0, width, 0, 0, width, height)
    var l = width; var t = height; var r = -1; var b = -1
    for (y in 0 until height) for (x in 0 until width) {
        if ((p[y * width + x] ushr 24) > 64) {
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
        }
    }
    return if (r < 0) null else intArrayOf(l, t, r, b)
}

// --- Filtres ---------------------------------------------------------------------------------------------

enum class AvatarFilter { None, Soft, Poster, Comic }

/** Applique un filtre (l'opacité est conservée). Calcul lourd : à appeler hors du thread principal. */
fun Bitmap.filtered(filter: AvatarFilter): Bitmap {
    if (filter == AvatarFilter.None) return this
    val w = width
    val h = height
    var p = IntArray(w * h)
    getPixels(p, 0, w, 0, 0, w, h)
    val alpha = IntArray(p.size) { p[it] and 0xFF000000.toInt() }
    when (filter) {
        AvatarFilter.Soft -> {
            repeat(3) { p = bilateral(p, w, h, 28f) }
            p = adjust(p, saturation = 1.2f, brightness = 8)
        }
        AvatarFilter.Poster -> {
            repeat(2) { p = bilateral(p, w, h, 32f) }
            p = posterize(adjust(p, saturation = 1.15f, brightness = 4), 6)
        }
        AvatarFilter.Comic -> {
            repeat(3) { p = bilateral(p, w, h, 30f) }
            p = outline(posterize(adjust(p, saturation = 1.25f, brightness = 6), 8), w, h)
        }
        AvatarFilter.None -> Unit
    }
    for (i in p.indices) p[i] = (p[i] and 0x00FFFFFF) or alpha[i]
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(p, 0, w, 0, 0, w, h) }
}

/** Filtre bilatéral 7 × 7 : lisse la peau et les aplats en gardant les contours. */
private fun bilateral(src: IntArray, w: Int, h: Int, sigmaColor: Float): IntArray {
    val r = 3
    val spatial = FloatArray((2 * r + 1) * (2 * r + 1)) { i ->
        val dx = i % (2 * r + 1) - r
        val dy = i / (2 * r + 1) - r
        exp(-(dx * dx + dy * dy) / (2f * 2.5f * 2.5f))
    }
    val range = FloatArray(256) { exp(-(it * it) / (2f * sigmaColor * sigmaColor)) }
    val out = IntArray(src.size)
    for (y in 0 until h) for (x in 0 until w) {
        val c = src[y * w + x]
        val cr = (c shr 16) and 0xFF; val cg = (c shr 8) and 0xFF; val cb = c and 0xFF
        var sr = 0f; var sg = 0f; var sb = 0f; var sw = 0f
        for (dy in -r..r) {
            val yy = (y + dy).coerceIn(0, h - 1)
            for (dx in -r..r) {
                val xx = (x + dx).coerceIn(0, w - 1)
                val n = src[yy * w + xx]
                val nr = (n shr 16) and 0xFF; val ng = (n shr 8) and 0xFF; val nb = n and 0xFF
                val d = (kotlin.math.abs(nr - cr) + kotlin.math.abs(ng - cg) + kotlin.math.abs(nb - cb)) / 3
                val wgt = spatial[(dy + r) * (2 * r + 1) + dx + r] * range[d]
                sr += nr * wgt; sg += ng * wgt; sb += nb * wgt; sw += wgt
            }
        }
        out[y * w + x] = (c and 0xFF000000.toInt()) or ((sr / sw).toInt() shl 16) or ((sg / sw).toInt() shl 8) or (sb / sw).toInt()
    }
    return out
}

private fun adjust(src: IntArray, saturation: Float, brightness: Int): IntArray = IntArray(src.size) { i ->
    val c = src[i]
    val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
    val l = 0.299f * r + 0.587f * g + 0.114f * b
    fun ch(v: Int) = (l + (v - l) * saturation + brightness).roundToInt().coerceIn(0, 255)
    (c and 0xFF000000.toInt()) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
}

private fun posterize(src: IntArray, levels: Int): IntArray {
    val step = 255f / (levels - 1)
    return IntArray(src.size) { i ->
        val c = src[i]
        fun q(v: Int) = ((v / step).roundToInt() * step).roundToInt().coerceIn(0, 255)
        (c and 0xFF000000.toInt()) or (q((c shr 16) and 0xFF) shl 16) or (q((c shr 8) and 0xFF) shl 8) or q(c and 0xFF)
    }
}

/** Contours sombres (gradient de luminance), façon bande dessinée. */
private fun outline(src: IntArray, w: Int, h: Int): IntArray {
    val lum = FloatArray(src.size) { val c = src[it]; 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF) }
    val out = src.copyOf()
    for (y in 1 until h - 1) for (x in 1 until w - 1) {
        val i = y * w + x
        val gx = lum[i - w + 1] + 2 * lum[i + 1] + lum[i + w + 1] - lum[i - w - 1] - 2 * lum[i - 1] - lum[i + w - 1]
        val gy = lum[i + w - 1] + 2 * lum[i + w] + lum[i + w + 1] - lum[i - w - 1] - 2 * lum[i - w] - lum[i - w + 1]
        val g = sqrt(gx * gx + gy * gy)
        if (g > 90f) {
            val k = max(0.2f, 1f - (g - 90f) / 120f)
            val c = src[i]
            out[i] = (c and 0xFF000000.toInt()) or ((((c shr 16) and 0xFF) * k).toInt() shl 16) or ((((c shr 8) and 0xFF) * k).toInt() shl 8) or ((c and 0xFF) * k).toInt()
        }
    }
    return out
}

/**
 * Image finale de 1024 × 1024 : [image] posée avec l'échelle [scale] (pixels de l'avatar par pixel de l'image) et
 * son coin haut gauche en ([x], [y]) dans le repère 1024.
 */
fun bakeAvatar(image: Bitmap, scale: Float, x: Float, y: Float): Bitmap {
    val size = fr.pergens.hamacs.config.AvatarStore.SIZE
    val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(image, Matrix().apply { postScale(scale, scale); postTranslate(x, y) }, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    return out
}
