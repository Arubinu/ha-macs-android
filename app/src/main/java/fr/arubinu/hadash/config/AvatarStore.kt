package fr.arubinu.hadash.config

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * Avatars des membres créés sur la tablette (photo détourée ou photo ronde), enregistrés dans l'app :
 * - `files/avatars/<id>.png` : image 1024 × 1024 transparente, déjà cadrée (affichage) ;
 * - `<id>.src.jpg`, `<id>.mask.png` et `<id>.json` : photo d'origine, masque du détourage (niveaux de gris) et
 *   réglages (détourage, filtre, cadrage), pour rouvrir l'éditeur sur l'avatar existant.
 * Le cercle, le liseré et la couleur du fond sont dessinés à l'affichage. Le tout est inclus dans l'export.
 */
class AvatarStore(private val context: Context) {
    /** `cutout` : personnage détouré (le haut dépasse du cercle) ; `photo` : photo entière dans le cercle. */
    data class Meta(val mode: String, val version: Long)

    /** Réglages de l'éditeur pour un avatar existant (cadrage dans le repère 1024). */
    data class Edit(
        val source: Bitmap, val cut: Bitmap?, val useCutout: Boolean, val filter: String,
        val scale: Float, val x: Float, val y: Float,
    )

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
    private fun srcFile(id: String) = File(dir, "$id.src.jpg")
    private fun maskFile(id: String) = File(dir, "$id.mask.png")
    private fun editFile(id: String) = File(dir, "$id.json")

    fun load(id: String): Bitmap? = runCatching { BitmapFactory.decodeFile(file(id).path) }.getOrNull()

    /**
     * Enregistre l'avatar affiché et, pour pouvoir le retoucher plus tard, la photo d'origine, le détourage et les
     * réglages de l'éditeur.
     */
    fun save(
        id: String, bitmap: Bitmap, mode: String,
        source: Bitmap, cut: Bitmap?, useCutout: Boolean, filter: String, scale: Float, x: Float, y: Float,
    ) {
        file(id).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        srcFile(id).outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (cut != null) maskFile(id).outputStream().use { alphaToGray(cut).compress(Bitmap.CompressFormat.PNG, 100, it) }
        else maskFile(id).delete()
        editFile(id).writeText(buildJsonObject {
            put("cutout", useCutout); put("filter", filter); put("scale", scale); put("x", x); put("y", y)
        }.toString())
        prefs.edit().putString(id, "$mode:${System.currentTimeMillis()}").apply()
        _avatars.value = read()
    }

    /** Réglages de l'éditeur de l'avatar [id], ou null (avatar d'une ancienne version, ou absent). */
    fun loadEdit(id: String): Edit? = runCatching {
        val src = BitmapFactory.decodeFile(srcFile(id).path)?.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        val o = Json.parseToJsonElement(editFile(id).readText()) as JsonObject
        val mask = maskFile(id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
        Edit(
            source = src, cut = mask?.let { grayToAlpha(src, it) },
            useCutout = o.b("cutout", true), filter = o.s("filter") ?: "None",
            scale = o["scale"]?.jsonPrimitive?.floatOrNull ?: return null,
            x = o["x"]?.jsonPrimitive?.floatOrNull ?: 0f, y = o["y"]?.jsonPrimitive?.floatOrNull ?: 0f,
        )
    }.getOrNull()

    fun remove(id: String) {
        listOf(file(id), srcFile(id), maskFile(id), editFile(id)).forEach { it.delete() }
        prefs.edit().remove(id).apply()
        _avatars.value = read()
    }

    private fun b64(f: File): String? = runCatching { Base64.encodeToString(f.readBytes(), Base64.NO_WRAP) }.getOrNull()

    /** Pour l'export : `{ id: { "mode", "png", "src", "mask", "edit" } }`. */
    fun export(): JsonObject = JsonObject(_avatars.value.mapNotNull { (id, meta) ->
        val png = b64(file(id)) ?: return@mapNotNull null
        id to buildJsonObject {
            put("mode", meta.mode)
            put("png", png)
            b64(srcFile(id))?.let { put("src", it) }
            b64(maskFile(id))?.let { put("mask", it) }
            runCatching { editFile(id).readText() }.getOrNull()?.let { put("edit", it) }
        }
    }.toMap())

    /** Import : remplace tous les avatars par ceux du fichier (aucun si absent). */
    fun import(data: JsonObject?) {
        dir.listFiles()?.forEach { it.delete() }
        prefs.edit().clear().apply()
        data?.forEach { (id, v) ->
            val o = v as? JsonObject ?: return@forEach
            fun bytes(k: String) = o.s(k)?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
            file(id).writeBytes(bytes("png") ?: return@forEach)
            bytes("src")?.let { srcFile(id).writeBytes(it) }
            bytes("mask")?.let { maskFile(id).writeBytes(it) }
            o.s("edit")?.let { editFile(id).writeText(it) }
            prefs.edit().putString(id, "${o.s("mode") ?: "cutout"}:${System.currentTimeMillis()}").apply()
        }
        _avatars.value = read()
    }

    companion object {
        /** Taille de l'image enregistrée et proportions du cercle (cercle de 836 posé en bas, liseré de 25). */
        const val SIZE = 1024
        const val CIRCLE = 836f / 1024f
        const val RING = 25f / 1024f

        /** Opacité du détourage → image en niveaux de gris (compacte en PNG). */
        private fun alphaToGray(cut: Bitmap): Bitmap {
            val px = IntArray(cut.width * cut.height)
            cut.getPixels(px, 0, cut.width, 0, 0, cut.width, cut.height)
            for (i in px.indices) { val a = px[i] ushr 24; px[i] = (0xFF shl 24) or (a shl 16) or (a shl 8) or a }
            return Bitmap.createBitmap(px, cut.width, cut.height, Bitmap.Config.ARGB_8888)
        }

        /** Masque en niveaux de gris → photo [src] avec cette opacité (même forme que le détourage d'origine). */
        private fun grayToAlpha(src: Bitmap, mask: Bitmap): Bitmap {
            val m = if (mask.width == src.width && mask.height == src.height) mask else Bitmap.createScaledBitmap(mask, src.width, src.height, true)
            val p = IntArray(src.width * src.height)
            val a = IntArray(p.size)
            src.getPixels(p, 0, src.width, 0, 0, src.width, src.height)
            m.getPixels(a, 0, src.width, 0, 0, src.width, src.height)
            for (i in p.indices) p[i] = (p[i] and 0x00FFFFFF) or ((a[i] and 0xFF) shl 24)
            return Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888).apply { setHasAlpha(true); setPixels(p, 0, src.width, 0, 0, src.width, src.height) }
        }
    }
}
