package fr.pergens.hamacs.presence

import android.content.Context
import android.util.Log
import android.util.Size
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
 * Détection de mouvement devant la tablette avec sa caméra (choisie dans les réglages, sinon frontale, sinon webcam
 * USB), entièrement sur l'appareil :
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

    private var owner: LifecycleOwner? = null
    /** Aperçu affiché dans les réglages (null = aucun) : ajouté à la même caméra que l'analyse. */
    private var preview: androidx.camera.core.Preview.SurfaceProvider? = null

    /** Affiche (ou retire, null) l'aperçu de la caméra utilisée, sans interrompre la détection. */
    fun setPreview(provider: androidx.camera.core.Preview.SurfaceProvider?) {
        preview = provider
        owner?.let { start(it) }
    }

    /** Caméras présentes au dernier démarrage : une caméra branchée ou débranchée relance la détection. */
    private var knownIds: Set<String> = emptySet()
    /** Caméra ouverte (null = aucune). */
    private var current: String? = null
    private var listening = false

    private fun watchCameras() {
        if (listening) return
        listening = true
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        manager.registerAvailabilityCallback(object : android.hardware.camera2.CameraManager.AvailabilityCallback() {
            // Disponible / indisponible change aussi quand une app ouvre la caméra : seule la liste compte.
            override fun onCameraAvailable(id: String) { camerasChanged() }
            override fun onCameraUnavailable(id: String) { camerasChanged() }
        }, main)
    }

    /**
     * Liste des caméras changée : CameraX arrêté puis relancé (il ne suit pas seul un branchement / débranchement).
     * Une caméra débranchée pendant qu'elle est ouverte ne déclenche pas l'AvailabilityCallback (elle passe
     * d'« occupée » à « absente », indisponible dans les deux cas) : aussi vérifié à chaque erreur de la caméra.
     */
    private fun camerasChanged(): Boolean {
        val ids = Cameras.list(context).map { it.id }.toSet()
        if (ids == knownIds) return false
        Cameras.forget(knownIds - ids)
        knownIds = ids
        // Caméra en cours toujours branchée et toujours celle à utiliser : rien à relancer (relancer CameraX fige le
        // flux quelques secondes). Une caméra rebranchée sera réinitialisée quand elle sera choisie (Cameras.provider).
        val want = Cameras.pick(context, fr.pergens.hamacs.App.instance.config.settings.value.cameraId)?.id
        if (current != null && current in ids && want == current) {
            Log.i(TAG, "Caméras changées : $ids, $current conservée")
            return false
        }
        Log.i(TAG, "Caméras changées : $ids, CameraX relancé")
        current = null
        provider = null
        Cameras.reset(context) { owner?.let { start(it) } }
        return true
    }

    fun start(owner: LifecycleOwner) {
        this.owner = owner
        watchCameras()
        knownIds = Cameras.list(context).map { it.id }.toSet()
        // Aucune caméra : CameraX n'est pas initialisé (un échec d'initialisation reste en mémoire jusqu'à la fermeture
        // de l'app, une webcam branchée ensuite ne serait jamais vue) ; un branchement relancera la détection.
        if (knownIds.isEmpty()) {
            provider?.unbindAll()
            return
        }
        // Caméra choisie dans les réglages, sinon avant, sinon webcam USB, sinon arrière.
        val cam = Cameras.pick(context, fr.pergens.hamacs.App.instance.config.settings.value.cameraId) ?: return
        Cameras.provider(context, cam.id, onError = { failed(it) }) { p ->
            runCatching {
                provider = p
                val selector = Cameras.selector(cam.id)
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
                        // Fil à part : une image inattendue (format d'une webcam USB…) ne doit jamais faire planter l'app.
                        runCatching {
                            val now = System.currentTimeMillis()
                            if (now - lastFrame < 330) return@use
                            lastFrame = now
                            val plane = it.planes[0]
                            val grid = downsample(plane.buffer, plane.rowStride, it.width, it.height)
                            previous?.let { prev -> if (isMotion(prev, grid)) _lastMotion.value = now }
                            previous = grid
                        }.onFailure { e -> noteOnce("analyse", e) }
                    }
                }
                p.unbindAll()
                val shown = preview?.let { sp -> androidx.camera.core.Preview.Builder().build().also { it.surfaceProvider = sp } }
                val camera = if (shown != null) p.bindToLifecycle(owner, selector, analysis, shown)
                else p.bindToLifecycle(owner, selector, analysis)
                current = cam.id
                // Caméra en erreur : débranchée (liste revue tout de suite et 1 s après, le temps qu'Android la retire),
                // sinon nouvel essai dans 10 s.
                watched?.removeObservers(owner)
                watched = camera.cameraInfo.cameraState
                camera.cameraInfo.cameraState.observe(owner) { st ->
                    if (st.error == null || camerasChanged()) return@observe
                    main.postDelayed({ camerasChanged() }, 1_000)
                    if (st.type == androidx.camera.core.CameraState.Type.CLOSED) scheduleRetry("erreur caméra ${st.error?.code}")
                }
            }.onFailure { failed(it) }
        }
    }

    private fun failed(e: Throwable) {
        Log.w(TAG, "Caméra indisponible : ${e.message}")
        noteOnce("ouverture", e)
        scheduleRetry(e.message ?: "ouverture impossible")
    }

    /** État de la caméra suivi (un seul observateur, retiré à chaque nouvelle ouverture). */
    private var watched: androidx.lifecycle.LiveData<androidx.camera.core.CameraState>? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var retryPending = false

    /** Relance de la détection après une erreur de caméra (une seule relance en attente à la fois). */
    private fun scheduleRetry(why: String) {
        if (retryPending) return
        retryPending = true
        Log.w(TAG, "Nouvel essai de la caméra dans 10 s ($why)")
        main.postDelayed({
            retryPending = false
            owner?.let { start(it) }
        }, 10_000)
    }

    private fun noteOnce(what: String, e: Throwable) =
        fr.pergens.hamacs.CrashGuard.note(context, "presence:$what:${e.javaClass.simpleName}", "Caméra (présence)", "$what : ${e.message}", e)

    fun stop() {
        provider?.unbindAll()
        provider = null
        current = null
        owner = null
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
