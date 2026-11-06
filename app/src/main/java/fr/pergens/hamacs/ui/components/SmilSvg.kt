package fr.pergens.hamacs.ui.components

import android.content.Context
import android.util.Xml
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.ConcurrentHashMap

/*
 * Lecteur minimal de SVG animés en SMIL (icônes Meteocons de clock-weather-card) : formes path / circle /
 * rect, groupes, clip-path et mask (alpha), propriétés de remplissage et de trait héritées, et animations
 * <animateTransform> (translate, rotate) et <animate> (opacity, stroke-dashoffset), en mode linéaire ou
 * spline, répétées indéfiniment. Le moteur SVG d'Android (coil-svg) affiche ces fichiers sans animation.
 */

internal class SvgAnim(
    val attr: String,
    val type: String?,
    val values: List<FloatArray>,
    val keyTimes: FloatArray?,
    val splines: List<CubicBezierEasing>?,
    val begin: Float,
    val dur: Float,
) {
    /** Valeur à l'instant [t] (secondes), ou null avant le début. */
    fun at(t: Float): FloatArray? {
        if (values.isEmpty() || dur <= 0f || t < begin) return values.firstOrNull()?.takeIf { begin <= 0f }
        val p = ((t - begin) % dur) / dur
        if (values.size == 1) return values[0]
        val times = keyTimes ?: FloatArray(values.size) { it / (values.size - 1f) }
        var i = 0
        while (i < times.size - 2 && p >= times[i + 1]) i++
        val span = (times[i + 1] - times[i]).takeIf { it > 0f } ?: 1f
        var f = ((p - times[i]) / span).coerceIn(0f, 1f)
        splines?.getOrNull(i)?.let { f = it.transform(f) }
        val a = values[i]
        val b = values[i + 1]
        return FloatArray(maxOf(a.size, b.size)) { k ->
            val va = a.getOrElse(k) { 0f }
            va + (b.getOrElse(k) { va } - va) * f
        }
    }
}

internal class SvgNode(
    val tag: String,
    val attrs: Map<String, String>,
    val children: MutableList<SvgNode> = mutableListOf(),
    val anims: MutableList<SvgAnim> = mutableListOf(),
) {
    var path: Path? = null
}

class SvgDoc internal constructor(
    internal val width: Float,
    internal val height: Float,
    internal val rootNode: SvgNode,
    private val defs: Map<String, SvgNode>,
) {
    internal fun def(id: String): SvgNode? = defs[id]
    /** Contours de découpe déjà calculés (clipPath / mask), par identifiant. */
    internal val clips = HashMap<String, Path?>()
    val animated: Boolean by lazy { hasAnim(rootNode) }
    private fun hasAnim(n: SvgNode): Boolean = n.anims.isNotEmpty() || n.children.any { hasAnim(it) }
}

private fun floats(s: String): FloatArray =
    s.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.mapNotNull { it.toFloatOrNull() }.toFloatArray()

private fun seconds(s: String?): Float = s?.trim()?.removeSuffix("s")?.toFloatOrNull() ?: 0f

/** Analyse un document SVG (sous-ensemble utilisé par les icônes Meteocons). */
fun parseSmilSvg(text: String): SvgDoc? = runCatching {
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    parser.setInput(text.reader())
    val stack = ArrayDeque<SvgNode>()
    val defs = HashMap<String, SvgNode>()
    var root: SvgNode? = null
    var w = 128f
    var h = 128f
    while (true) {
        when (parser.next()) {
            XmlPullParser.END_DOCUMENT -> break
            XmlPullParser.START_TAG -> {
                val attrs = HashMap<String, String>()
                for (i in 0 until parser.attributeCount) attrs[parser.getAttributeName(i)] = parser.getAttributeValue(i)
                // `style="a:b;c:d"` : propriétés de présentation supplémentaires.
                attrs["style"]?.split(';')?.forEach { kv ->
                    val k = kv.substringBefore(':').trim()
                    val v = kv.substringAfter(':', "").trim()
                    if (k.isNotEmpty() && v.isNotEmpty()) attrs[k] = v
                }
                val tag = parser.name
                if (tag == "animate" || tag == "animateTransform") {
                    val values = attrs["values"]?.split(';')?.map { floats(it) }
                        ?: listOfNotNull(attrs["from"]?.let { floats(it) }, attrs["to"]?.let { floats(it) })
                    val splines = if (attrs["calcMode"] == "spline") attrs["keySplines"]?.split(';')?.mapNotNull {
                        val f = floats(it)
                        if (f.size == 4) CubicBezierEasing(f[0], f[1], f[2], f[3]) else null
                    } else null
                    stack.lastOrNull()?.anims?.add(
                        SvgAnim(
                            attrs["attributeName"] ?: "", attrs["type"], values,
                            attrs["keyTimes"]?.split(';')?.mapNotNull { it.trim().toFloatOrNull() }?.toFloatArray(),
                            splines, seconds(attrs["begin"]), seconds(attrs["dur"]),
                        ),
                    )
                    // Les balises d'animation sont vides : on ignore leur fermeture via un nœud factice.
                    stack.addLast(SvgNode("#anim", emptyMap()))
                    continue
                }
                val node = SvgNode(tag, attrs)
                node.path = when (tag) {
                    "path" -> attrs["d"]?.let { PathParser().parsePathString(it).toPath() }
                    "circle" -> {
                        val cx = attrs["cx"]?.toFloatOrNull() ?: 0f
                        val cy = attrs["cy"]?.toFloatOrNull() ?: 0f
                        val r = attrs["r"]?.toFloatOrNull() ?: 0f
                        Path().apply { addOval(Rect(cx - r, cy - r, cx + r, cy + r)) }
                    }
                    "rect" -> {
                        val x = attrs["x"]?.toFloatOrNull() ?: 0f
                        val y = attrs["y"]?.toFloatOrNull() ?: 0f
                        val rw = attrs["width"]?.toFloatOrNull() ?: 0f
                        val rh = attrs["height"]?.toFloatOrNull() ?: 0f
                        val rx = attrs["rx"]?.toFloatOrNull() ?: attrs["ry"]?.toFloatOrNull() ?: 0f
                        Path().apply { addRoundRect(RoundRect(x, y, x + rw, y + rh, CornerRadius(rx))) }
                    }
                    else -> null
                }
                if (attrs["fill-rule"] == "evenodd") node.path?.fillType = PathFillType.EvenOdd
                if (tag == "svg") {
                    attrs["viewBox"]?.let { floats(it) }?.takeIf { it.size == 4 }?.let { w = it[2]; h = it[3] }
                    root = node
                } else stack.lastOrNull()?.children?.add(node)
                attrs["id"]?.let { if (tag == "clipPath" || tag == "mask") defs[it] = node }
                stack.addLast(node)
            }
            XmlPullParser.END_TAG -> stack.removeLastOrNull()
        }
    }
    root?.let { SvgDoc(w, h, it, defs) }
}.getOrNull()

private val cache = ConcurrentHashMap<String, SvgDoc>()

/** Charge (et met en cache) un SVG des assets. */
fun loadSmilSvg(context: Context, asset: String): SvgDoc? = cache[asset] ?: runCatching {
    context.assets.open(asset).bufferedReader().use { it.readText() }
}.getOrNull()?.let { parseSmilSvg(it) }?.also { cache[asset] = it }

private fun parseColor(v: String?): Color? {
    if (v == null || v == "none" || v == "transparent") return null
    if (!v.startsWith("#")) return when (v) { "white" -> Color.White; "black" -> Color.Black; else -> null }
    val h = v.substring(1)
    val full = if (h.length == 3) h.map { "$it$it" }.joinToString("") else h
    return runCatching { Color(0xFF000000 or full.toLong(16)) }.getOrNull()
}

private fun urlRef(v: String?): String? = v?.trim()?.removePrefix("url(")?.removeSuffix(")")?.removePrefix("#")?.takeIf { v.startsWith("url(") }

/** Contour de découpe (clipPath ou mask alpha) : union des formes remplies qu'il contient. */
private fun SvgDoc.clipOf(id: String?): Path? {
    if (id == null) return null
    return clips.getOrPut(id) { buildClip(id) }
}

private fun SvgDoc.buildClip(id: String): Path? {
    val n = def(id) ?: return null
    var out: Path? = null
    fun walk(x: SvgNode) {
        x.path?.let { p -> out = out?.let { Path.combine(PathOperation.Union, it, p) } ?: Path().apply { addPath(p); fillType = p.fillType } }
        x.children.forEach { walk(it) }
    }
    walk(n)
    return out
}

private class Inherited(
    val fill: String? = null,
    val stroke: String? = null,
    val strokeWidth: Float = 1f,
    val cap: String? = null,
    val join: String? = null,
    val miter: Float = 4f,
    val dash: String? = null,
    val dashOffset: Float = 0f,
) {
    fun with(a: Map<String, String>, dashAnim: Float?) = Inherited(
        a["fill"] ?: fill, a["stroke"] ?: stroke, a["stroke-width"]?.toFloatOrNull() ?: strokeWidth,
        a["stroke-linecap"] ?: cap, a["stroke-linejoin"] ?: join, a["stroke-miterlimit"]?.toFloatOrNull() ?: miter,
        a["stroke-dasharray"] ?: dash, dashAnim ?: a["stroke-dashoffset"]?.toFloatOrNull() ?: dashOffset,
    )
}

private fun DrawScope.drawNode(doc: SvgDoc, n: SvgNode, inh: Inherited, alpha: Float, t: Float) {
    if (n.tag == "defs" || n.tag == "clipPath" || n.tag == "mask" || n.tag == "#anim") return
    var a = alpha * (n.attrs["opacity"]?.toFloatOrNull() ?: 1f)
    var dashAnim: Float? = null
    val transforms = ArrayList<Pair<String, FloatArray>>()
    n.anims.forEach { an ->
        val v = an.at(t) ?: return@forEach
        when (an.attr) {
            "opacity" -> a *= v.getOrElse(0) { 1f }.coerceIn(0f, 1f)
            "stroke-dashoffset" -> dashAnim = v.getOrElse(0) { 0f }
            "transform" -> transforms += (an.type ?: "translate") to v
        }
    }
    if (a <= 0.001f) return
    val style = inh.with(n.attrs, dashAnim)
    val clip = doc.clipOf(urlRef(n.attrs["clip-path"])) ?: doc.clipOf(urlRef(n.attrs["mask"]))
    withTransform({
        transforms.forEach { (type, v) ->
            when (type) {
                "translate" -> translate(v.getOrElse(0) { 0f }, v.getOrElse(1) { 0f })
                "rotate" -> rotate(v.getOrElse(0) { 0f }, Offset(v.getOrElse(1) { 0f }, v.getOrElse(2) { 0f }))
                "scale" -> scale(v.getOrElse(0) { 1f }, v.getOrElse(1) { v.getOrElse(0) { 1f } }, Offset.Zero)
            }
        }
    }) {
        val body: DrawScope.() -> Unit = {
            n.path?.let { p ->
                parseColor(style.fill ?: "#000")?.let { drawPath(p, it, a, Fill) }
                parseColor(style.stroke)?.let { c ->
                    val dash = style.dash?.let { floats(it) }?.takeIf { it.isNotEmpty() }?.let { if (it.size % 2 == 1) it + it else it }
                    drawPath(
                        p, c, a,
                        Stroke(
                            style.strokeWidth, style.miter,
                            when (style.cap) { "round" -> StrokeCap.Round; "square" -> StrokeCap.Square; else -> StrokeCap.Butt },
                            when (style.join) { "round" -> StrokeJoin.Round; "bevel" -> StrokeJoin.Bevel; else -> StrokeJoin.Miter },
                            dash?.let { PathEffect.dashPathEffect(it, style.dashOffset) },
                        ),
                    )
                }
            }
            n.children.forEach { drawNode(doc, it, style, a, t) }
        }
        if (clip != null) clipPath(clip) { body() } else body()
    }
}

/** Dessine [doc] dans la zone (centré, proportions conservées) à l'instant [t] en secondes. */
fun DrawScope.drawSmilSvg(doc: SvgDoc, t: Float) {
    val s = minOf(size.width / doc.width, size.height / doc.height)
    withTransform({
        translate((size.width - doc.width * s) / 2, (size.height - doc.height * s) / 2)
        scale(s, s, Offset.Zero)
    }) {
        drawNode(doc, doc.rootNode, Inherited(), 1f, t)
    }
}

@Composable
private fun rememberSecondsClock(enabled: Boolean): State<Float> {
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (true) withFrameMillis { clock.floatValue = (it - start) / 1000f }
    }
    return clock
}

/** Icône SVG des assets, animée (SMIL) si [animated] et si le fichier contient des animations. */
@Composable
fun SmilSvgIcon(asset: String, modifier: Modifier = Modifier, animated: Boolean = true) {
    val context = LocalContext.current
    val doc = remember(asset) { loadSmilSvg(context, asset) }
    val clock = rememberSecondsClock(animated && doc?.animated == true)
    Canvas(modifier) { doc?.let { drawSmilSvg(it, clock.value) } }
}
