package fr.pergens.hamacs.presence

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Détection de mouvement devant la tablette avec la caméra frontale, entièrement sur l'appareil :
 * l'image est réduite à une grille de luminance 32×24, comparée à la précédente ~3 fois par seconde.
 * Aucune image n'est conservée ni transmise.
 */
class PresenceDetector(private val context: Context) {
    private val _lastMotion = MutableStateFlow(0L)
    /** Horodatage du dernier mouvement détecté (0 = jamais). */
    val lastMotion: StateFlow<Long> = _lastMotion.asStateFlow()

    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var previous: IntArray? = null
    private var lastFrame = 0L
    @Volatile var sensitivity = 1

    fun start(owner: LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                val p = future.get()
                provider = p
                val selector = if (p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) CameraSelector.DEFAULT_FRONT_CAMERA
                else CameraSelector.DEFAULT_BACK_CAMERA
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(ResolutionStrategy(Size(320, 240), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { image ->
                    image.use {
                        val now = System.currentTimeMillis()
                        if (now - lastFrame < 330) return@use
                        lastFrame = now
                        val plane = it.planes[0]
                        val grid = downsample(plane.buffer, plane.rowStride, it.width, it.height)
                        previous?.let { prev -> if (isMotion(prev, grid)) _lastMotion.value = now }
                        previous = grid
                    }
                }
                p.unbindAll()
                p.bindToLifecycle(owner, selector, analysis)
            }.onFailure { Log.w(TAG, "Caméra indisponible : ${it.message}") }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        provider?.unbindAll()
        provider = null
        previous = null
    }

    private fun downsample(buf: java.nio.ByteBuffer, rowStride: Int, w: Int, h: Int): IntArray {
        val out = IntArray(GW * GH)
        val cw = w / GW
        val ch = h / GH
        for (gy in 0 until GH) for (gx in 0 until GW) {
            var sum = 0
            var n = 0
            // Échantillonnage clairsemé de chaque cellule : suffisant pour un écart de luminance.
            var y = gy * ch
            while (y < (gy + 1) * ch) {
                var x = gx * cw
                while (x < (gx + 1) * cw) {
                    sum += buf.get(y * rowStride + x).toInt() and 0xff
                    n++
                    x += 4
                }
                y += 4
            }
            out[gy * GW + gx] = if (n == 0) 0 else sum / n
        }
        return out
    }

    private fun isMotion(a: IntArray, b: IntArray): Boolean {
        // Compense les variations globales de lumière (nuages, lampe) avant de compter les cellules changées.
        val shift = (b.average() - a.average()).toInt()
        val threshold = when (sensitivity) { 0 -> 28; 2 -> 12; else -> 18 }
        val minCells = when (sensitivity) { 0 -> 24; 2 -> 6; else -> 12 }
        var changed = 0
        for (i in a.indices) if (abs(b[i] - a[i] - shift) > threshold) changed++
        return changed >= minCells
    }

    companion object {
        private const val TAG = "PresenceDetector"
        private const val GW = 32
        private const val GH = 24
    }
}
