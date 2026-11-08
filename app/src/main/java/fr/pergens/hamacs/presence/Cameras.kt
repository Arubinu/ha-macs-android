package fr.pergens.hamacs.presence

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector

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

    /** Sélecteur CameraX limité à la caméra [id]. */
    @OptIn(ExperimentalCamera2Interop::class)
    fun selector(id: String): CameraSelector = CameraSelector.Builder()
        .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == id } }
        .build()
}
