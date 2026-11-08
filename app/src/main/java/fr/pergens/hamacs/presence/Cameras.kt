package fr.pergens.hamacs.presence

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat

/**
 * Caméras de l'appareil : avant, arrière et externes (webcam USB, vue par Android comme une caméra « externe » quand
 * l'appareil le permet, ex. Android sur Raspberry Pi). Choix enregistré par son identifiant Camera2 (pref `camera_id`,
 * vide = automatique) ; utilisé par la détection de présence et la photo des avatars.
 */
object Cameras {
    enum class Facing { Front, Back, External }

    class Info(val id: String, val facing: Facing)

    /** Caméras présentes (identifiants Camera2), avant d'abord, puis externes, puis arrière. */
    fun list(context: Context): List<Info> = runCatching {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        manager.cameraIdList.mapNotNull { id ->
            val facing = when (manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> Facing.Front
                CameraCharacteristics.LENS_FACING_BACK -> Facing.Back
                CameraCharacteristics.LENS_FACING_EXTERNAL -> Facing.External
                else -> return@mapNotNull null
            }
            Info(id, facing)
        }.sortedBy { listOf(Facing.Front, Facing.External, Facing.Back).indexOf(it.facing) }
    }.getOrDefault(emptyList())

    /**
     * Caméra à utiliser : celle choisie si elle est branchée, sinon l'avant, sinon une externe (USB), sinon l'arrière.
     * [prefer] : facing voulu en premier (bouton « changer de caméra » des avatars).
     */
    fun pick(context: Context, chosen: String?, prefer: Facing? = null): Info? {
        val all = list(context)
        chosen?.takeIf { it.isNotBlank() }?.let { id -> all.firstOrNull { it.id == id }?.let { return it } }
        prefer?.let { f -> all.firstOrNull { it.facing == f }?.let { return it } }
        return all.firstOrNull()
    }

    /** Liste de caméras pour laquelle CameraX a déjà été réinitialisé (une seule fois par liste, pas de boucle). */
    private var reinitFor: Set<String>? = null

    /**
     * CameraX prêt à ouvrir la caméra [id]. Sa liste de caméras est figée à l'initialisation : une webcam branchée
     * ensuite n'y figure pas (aperçu figé, détection muette). Dans ce cas il est arrêté puis réinitialisé.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun provider(context: Context, id: String, onError: (Throwable) -> Unit, onReady: (ProcessCameraProvider) -> Unit) {
        initialized = true
        val main = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val p = runCatching { future.get() }.getOrElse { onError(it); return@addListener }
            val known = runCatching { p.availableCameraInfos.map { Camera2CameraInfo.from(it).cameraId }.toSet() }.getOrDefault(emptySet())
            val now = list(context).map { it.id }.toSet()
            // Absente de sa liste, ou débranchée depuis son initialisation (elle y est encore, mais inutilisable).
            if ((id !in known || id in gone) && reinitFor != now) {
                reinitFor = now
                gone.clear()
                p.unbindAll()
                p.shutdownAsync().addListener({ provider(context, id, onError, onReady) }, main)
                return@addListener
            }
            onReady(p)
        }, main)
    }

    /** CameraX déjà initialisé (sinon rien à arrêter ; l'initialiser sans caméra laisserait un échec en mémoire). */
    private var initialized = false

    /** Caméras débranchées depuis l'initialisation de CameraX : à réinitialiser avant de les rouvrir. */
    private val gone = mutableSetOf<String>()

    /** Caméras [ids] débranchées sans relancer CameraX (le flux en cours continue). */
    fun forget(ids: Set<String>) { gone += ids }

    /**
     * Caméra branchée ou débranchée : CameraX ne suit pas (une caméra disparue reste bloquée en fermeture et empêche
     * d'en ouvrir une autre ; une nouvelle n'est pas dans sa liste). Il est arrêté, puis [then] relance l'ouverture
     * (au plus tard après 5 s si l'arrêt traîne).
     */
    fun reset(context: Context, then: () -> Unit) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var done = false
        val once = Runnable { if (!done) { done = true; reinitFor = null; gone.clear(); then() } }
        if (!initialized) { handler.post(once); return }
        initialized = false
        handler.postDelayed(once, 5_000)
        val main = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                val p = future.get()
                p.unbindAll()
                p.shutdownAsync().addListener({ handler.post(once) }, main)
            }.onFailure { handler.post(once) }
        }, main)
    }

    /** Sélecteur CameraX limité à la caméra [id]. */
    @OptIn(ExperimentalCamera2Interop::class)
    fun selector(id: String): CameraSelector = CameraSelector.Builder()
        .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == id } }
        .build()
}
