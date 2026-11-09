package fr.pergens.hamacs.ui.avatar

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/** Variante complète : détourage et détection de visage par ML Kit. */
const val CUTOUT_AVAILABLE = true

private const val TAG = "Avatar"

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

/**
 * Détourage sur l'appareil : segmentation de sujet de ML Kit (services Google, modèle téléchargé une fois), sinon
 * segmentation « selfie » embarquée dans l'app. Retourne l'image avec le fond transparent, ou null.
 */
suspend fun cutout(context: Context, src: Bitmap): Bitmap? =
    runCatching { subjectCutout(context, src) }.onFailure { Log.w(TAG, "subject segmentation", it) }.getOrNull()
        ?: runCatching { selfieCutout(src) }.onFailure { Log.w(TAG, "selfie segmentation", it) }.getOrNull()

private suspend fun subjectCutout(context: Context, src: Bitmap): Bitmap? {
    val segmenter = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundBitmap().build())
    try {
        val modules = ModuleInstall.getClient(context)
        if (!modules.areModulesAvailable(segmenter).await().areModulesAvailable()) {
            // Premier usage : le modèle est téléchargé par les services Google (une minute au plus).
            val done = withTimeoutOrNull(90_000) {
                suspendCancellableCoroutine { cont ->
                    val listener = object : InstallStatusListener {
                        override fun onInstallStatusUpdated(update: ModuleInstallStatusUpdate) {
                            when (update.installState) {
                                ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> if (cont.isActive) cont.resume(true)
                                ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
                                ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> if (cont.isActive) cont.resume(false)
                                else -> Unit
                            }
                        }
                    }
                    modules.installModules(ModuleInstallRequest.newBuilder().addApi(segmenter).setListener(listener).build())
                        .addOnSuccessListener { if (it.areModulesAlreadyInstalled() && cont.isActive) cont.resume(true) }
                        .addOnFailureListener { if (cont.isActive) cont.resume(false) }
                }
            }
            if (done != true) return null
        }
        return segmenter.process(InputImage.fromBitmap(src, 0)).await().foregroundBitmap
    } finally {
        segmenter.close()
    }
}

private suspend fun selfieCutout(src: Bitmap): Bitmap? {
    val segmenter = Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build())
    try {
        val mask = segmenter.process(InputImage.fromBitmap(src, 0)).await()
        val buf = mask.buffer
        val mw = mask.width
        val mh = mask.height
        val out = src.copy(Bitmap.Config.ARGB_8888, true).apply { setHasAlpha(true) }
        val px = IntArray(out.width * out.height)
        out.getPixels(px, 0, out.width, 0, 0, out.width, out.height)
        for (y in 0 until out.height) for (x in 0 until out.width) {
            val mx = x * mw / out.width
            val my = y * mh / out.height
            val conf = buf.getFloat((my * mw + mx) * 4)
            // Bords adoucis : confiance 0,3 → 0,7 répartie sur toute l'opacité.
            val a = ((conf - 0.3f) / 0.4f).coerceIn(0f, 1f)
            val i = y * out.width + x
            px[i] = (px[i] and 0x00FFFFFF) or ((a * 255).roundToInt() shl 24)
        }
        out.setPixels(px, 0, out.width, 0, 0, out.width, out.height)
        return if (px.any { (it ushr 24) > 128 }) out else null
    } finally {
        segmenter.close()
    }
}

/** Plus grand visage de la photo (détection embarquée, hors ligne), pour le cadrage de départ ; null si aucun. */
suspend fun detectFace(src: Bitmap): android.graphics.Rect? = runCatching {
    val detector = com.google.mlkit.vision.face.FaceDetection.getClient(
        com.google.mlkit.vision.face.FaceDetectorOptions.Builder()
            .setPerformanceMode(com.google.mlkit.vision.face.FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).build()
    )
    try {
        detector.process(InputImage.fromBitmap(src, 0)).await().maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }?.boundingBox
    } finally {
        detector.close()
    }
}.onFailure { Log.w(TAG, "face detection", it) }.getOrNull()
