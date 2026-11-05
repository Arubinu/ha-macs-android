package fr.arubinu.hadash.config

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Avatars des membres créés sur la tablette (photo détourée ou photo ronde), enregistrés dans l'app :
 * une image 1024 × 1024 transparente par membre (`files/avatars/<id>.png`), déjà cadrée. Le cercle, le liseré
 * et la couleur du fond sont dessinés à l'affichage. Inclus dans l'export de la configuration.
 */
class AvatarStore(private val context: Context) {
    /** `cutout` : personnage détouré (le haut dépasse du cercle) ; `photo` : photo entière dans le cercle. */
    data class Meta(val mode: String, val version: Long)

    private val dir = File(context.filesDir, "avatars").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("avatars", Context.MODE_PRIVATE)

    private val _avatars = MutableStateFlow(read())
    /** Avatars enregistrés, par identifiant de membre (la version change à chaque enregistrement). */
    val avatars: StateFlow<Map<String, Meta>> = _avatars.asStateFlow()

    private fun read(): Map<String, Meta> = prefs.all.mapNotNull { (id, v) ->
        val parts = (v as? String)?.split(':') ?: return@mapNotNull null
        if (!File(dir, "$id.png").exists()) return@mapNotNull null
        id to Meta(parts[0], parts.getOrNull(1)?.toLongOrNull() ?: 0L)
    }.toMap()

    fun file(id: String) = File(dir, "$id.png")

    fun load(id: String): Bitmap? = runCatching { BitmapFactory.decodeFile(file(id).path) }.getOrNull()

    fun save(id: String, bitmap: Bitmap, mode: String) {
        file(id).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        prefs.edit().putString(id, "$mode:${System.currentTimeMillis()}").apply()
        _avatars.value = read()
    }

    fun remove(id: String) {
        file(id).delete()
        prefs.edit().remove(id).apply()
        _avatars.value = read()
    }

    /** Pour l'export : `{ id: { "mode": …, "png": base64 } }`. */
    fun export(): JsonObject = JsonObject(_avatars.value.mapNotNull { (id, meta) ->
        val bytes = runCatching { file(id).readBytes() }.getOrNull() ?: return@mapNotNull null
        id to JsonObject(mapOf("mode" to JsonPrimitive(meta.mode), "png" to JsonPrimitive(Base64.encodeToString(bytes, Base64.NO_WRAP))))
    }.toMap())

    /** Import : remplace tous les avatars par ceux du fichier (aucun si absent). */
    fun import(data: JsonObject?) {
        dir.listFiles()?.forEach { it.delete() }
        prefs.edit().clear().apply()
        data?.forEach { (id, v) ->
            val o = v as? JsonObject ?: return@forEach
            val bytes = runCatching { Base64.decode(o.s("png") ?: return@forEach, Base64.NO_WRAP) }.getOrNull() ?: return@forEach
            file(id).writeBytes(bytes)
            prefs.edit().putString(id, "${o.s("mode") ?: "cutout"}:${System.currentTimeMillis()}").apply()
        }
        _avatars.value = read()
    }

    companion object {
        /** Taille de l'image enregistrée et proportions du cercle (cercle de 836 posé en bas, liseré de 25). */
        const val SIZE = 1024
        const val CIRCLE = 836f / 1024f
        const val RING = 25f / 1024f
    }
}
