package fr.pergens.hamacs.ui.avatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.media.FaceDetector
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Variante « lite » (sans ML Kit, sans composant propriétaire) : pas de détourage, l'avatar est une photo ronde ;
 * visage trouvé par le détecteur intégré à Android pour le cadrage de départ.
 */
const val CUTOUT_AVAILABLE = false

@Suppress("UNUSED_PARAMETER")
suspend fun cutout(context: Context, src: Bitmap): Bitmap? = null

/** Plus grand visage de la photo (détecteur d'Android, hors ligne) ; null si aucun. */
suspend fun detectFace(src: Bitmap): android.graphics.Rect? = withContext(Dispatchers.Default) {
    runCatching {
        // Le détecteur d'Android exige une image RGB 565 de largeur paire.
        val w = src.width - src.width % 2
        val bmp = Bitmap.createBitmap(src, 0, 0, w, src.height).copy(Bitmap.Config.RGB_565, false)
        val faces = arrayOfNulls<FaceDetector.Face>(4)
        val n = FaceDetector(bmp.width, bmp.height, faces.size).findFaces(bmp, faces)
        val face = faces.take(n).filterNotNull().maxByOrNull { it.eyesDistance() } ?: return@runCatching null
        val mid = PointF().also { face.getMidPoint(it) }
        // Cadre du visage déduit de l'écart des yeux (proportions moyennes d'un visage).
        val e = face.eyesDistance()
        val fw = e * 2.4f
        val fh = fw * 1.25f
        val cy = mid.y + e * 0.35f
        android.graphics.Rect(
            (mid.x - fw / 2).roundToInt(), (cy - fh / 2).roundToInt(),
            (mid.x + fw / 2).roundToInt(), (cy + fh / 2).roundToInt(),
        )
    }.onFailure { Log.w("Avatar", "face detection", it) }.getOrNull()
}
