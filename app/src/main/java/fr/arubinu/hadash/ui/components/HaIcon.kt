package fr.arubinu.hadash.ui.components

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.arubinu.hadash.ui.theme.MdiFont
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * Registre des icônes : Material Design Icons (police) et hass-hue-icons (tracés SVG 24×24).
 * Chargé une fois au démarrage depuis les assets générés par tools/gen-icons.mjs.
 */
object IconRegistry {
    private var mdi: Map<String, Int> = emptyMap()
    private var hueRaw: JsonObject = JsonObject(emptyMap())
    private val hueCache = ConcurrentHashMap<String, Path>()

    fun init(context: Context) {
        if (mdi.isNotEmpty()) return
        val json = Json
        mdi = context.assets.open("mdi.json").bufferedReader().use { r ->
            json.parseToJsonElement(r.readText()).jsonObject.mapValues { it.value.jsonPrimitive.int }
        }
        hueRaw = context.assets.open("hue.json").bufferedReader().use { r ->
            json.parseToJsonElement(r.readText()).jsonObject
        }
    }

    fun mdiChar(name: String): String? = mdi[name]?.let { String(Character.toChars(it)) }

    /** Toutes les icônes disponibles (`mdi:…` puis `hue:…`), pour le choix d'une icône en mode édition. */
    val allIcons: List<String> by lazy { mdi.keys.map { "mdi:$it" } + hueRaw.keys.map { "hue:$it" } }

    fun huePath(name: String): Path? = hueCache[name] ?: run {
        val d = hueRaw[name]?.jsonPrimitive?.content ?: return null
        PathParser().parsePathString(d).toPath().also { hueCache[name] = it }
    }
}

@Composable
fun HaIcon(
    icon: String?,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = Color.White,
) {
    if (icon.isNullOrBlank()) return
    val prefix = icon.substringBefore(':', "mdi")
    val name = icon.substringAfter(':')
    when (prefix) {
        "hue" -> {
            val path = remember(name) { IconRegistry.huePath(name) }
            if (path != null) {
                Canvas(modifier.size(size)) {
                    val s = this.size.minDimension / 24f
                    scale(s, s, pivot = androidx.compose.ui.geometry.Offset.Zero) { drawPath(path, tint) }
                }
                return
            }
            MdiGlyph("help-circle-outline", modifier, size, tint)
        }
        else -> MdiGlyph(name, modifier, size, tint)
    }
}

@Composable
private fun MdiGlyph(name: String, modifier: Modifier, size: Dp, tint: Color) {
    val glyph = remember(name) { IconRegistry.mdiChar(name) ?: IconRegistry.mdiChar("help-circle-outline") ?: "?" }
    val fontSize = with(LocalDensity.current) { size.toSp() }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Text(
            glyph,
            style = TextStyle(fontFamily = MdiFont, fontSize = fontSize, lineHeight = fontSize, color = tint, textAlign = TextAlign.Center),
        )
    }
}
