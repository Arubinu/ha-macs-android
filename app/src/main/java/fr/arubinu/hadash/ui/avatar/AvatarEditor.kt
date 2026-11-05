package fr.arubinu.hadash.ui.avatar

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import fr.arubinu.hadash.App
import fr.arubinu.hadash.R
import fr.arubinu.hadash.config.AvatarStore
import fr.arubinu.hadash.ui.components.HaIcon
import fr.arubinu.hadash.ui.components.tapHold
import fr.arubinu.hadash.ui.theme.Dash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Panel = Color(0xFF222222)
private val Muted = Color(255, 255, 255, 150)

/** Cadrage : échelle (pixels de l'avatar 1024 par pixel de l'image) et coin haut gauche dans le repère 1024. */
private data class Framing(val scale: Float, val x: Float, val y: Float)

/**
 * Cadrage de départ, avec les repères d'un avatar fait à la main (1024 px) : haut des cheveux à 78 px du bord,
 * tête (oreilles comprises) sur 42 % de la largeur, centrée ; la tête dépasse ainsi nettement du cercle.
 * - Personnage détouré : tête repérée sur la silhouette (vraie photo comme avatar cartoon, que la détection de
 *   visage ne reconnaît pas).
 * - Photo ronde : visage détecté centré dans le cercle ; sinon photo couvrant le cercle.
 * Dans tous les cas, le bas de la photo atteint au moins le bas du cercle.
 *
 * Photo coupée à la poitrine (peu d'image sous la tête, ex. avatar généré) : à la taille de tête visée, son bas
 * s'arrêterait dans le cercle. Comme dans un éditeur photo, elle est alors agrandie en gardant le haut des cheveux
 * à sa place (jusqu'à 1,6 fois), plutôt que descendue (la tête ne dépasserait plus) ; la descente ne sert qu'en
 * dernier recours. Une photo prise de plus loin (ex. buste entier) n'est pas concernée.
 */
private fun initialFraming(image: Bitmap, cutout: Boolean, face: android.graphics.Rect?): Framing {
    val size = AvatarStore.SIZE.toFloat()
    val d = size * AvatarStore.CIRCLE
    val head = if (cutout) headOf(image) else null
    head?.let { (top, headCx, width) ->
        // Vraie photo (visage détecté) : le visage donne la taille et le centre horizontal, plus fiables que la
        // silhouette (cheveux volumineux ou en mèche, chignon, tête tournée). Avatar cartoon (visage non détecté) :
        // largeur et centre de la tête sur la silhouette. Le haut des cheveux reste le repère vertical.
        val faceOk = face != null && face.width() > 0
        var k = if (faceOk) size * 0.34f / face!!.width() else size * 0.42f / width
        val cx = if (faceOk) face!!.exactCenterX() else headCx
        // Haut de la tête coupé par la photo (cheveux au bord de l'image) : la coupure est placée juste au-dessus
        // du bord de l'avatar, où elle ne se voit pas, au lieu d'un haut de tête plat au-dessus du cercle.
        val anchor = if (top < 2f) -size * 0.03f else size * 78f / 1024f
        val reach = (size - anchor) / (image.height - top)
        if (reach > k) k = minOf(reach, k * 1.6f)
        return constrained(Framing(k, size / 2f - cx * k, anchor - top * k), image)
    }
    if (face != null && face.width() > 0) {
        val k = size * 0.36f / face.width()
        return constrained(Framing(k, size / 2f - face.exactCenterX() * k, size * 0.56f - face.exactCenterY() * k), image)
    }
    val k = d / minOf(image.width, image.height)
    return constrained(Framing(k, size / 2f - image.width * k / 2f, size - d / 2f - image.height * k / 2f), image)
}

/**
 * Tête d'une silhouette détourée : (haut, centre horizontal, largeur). Largeur = la plus grande largeur rencontrée
 * en descendant, jusqu'aux épaules (silhouette nettement plus large que la tête, une fois passé le cou).
 */
private fun headOf(image: Bitmap): Triple<Float, Float, Float>? {
    val w = image.width
    val h = image.height
    val px = IntArray(w * h)
    image.getPixels(px, 0, w, 0, 0, w, h)
    fun opaque(x: Int, y: Int) = (px[y * w + x] ushr 24) > 128
    // Axe du personnage : moyenne horizontale des pixels opaques (les points isolés du détourage pèsent peu).
    var sum = 0L; var count = 0L
    for (y in 0 until h) for (x in 0 until w) if (opaque(x, y)) { sum += x; count++ }
    if (count == 0L) return null
    val axis = (sum / count).toInt()
    val minRun = maxOf(4, w / 50)
    var top = -1
    var headW = 0
    var headCx = 0f
    for (y in 0 until h) {
        // Zone continue autour de l'axe (trous de 3 px tolérés) : ignore les restes de fond éloignés.
        var c = -1
        for (dx in 0..w / 8) { if (axis + dx < w && opaque(axis + dx, y)) { c = axis + dx; break }; if (axis - dx >= 0 && opaque(axis - dx, y)) { c = axis - dx; break } }
        if (c < 0) continue
        var l = c; var r = c; var gap = 0
        var x = c - 1
        while (x >= 0) { if (opaque(x, y)) { l = x; gap = 0 } else if (++gap > 3) break; x-- }
        gap = 0; x = c + 1
        while (x < w) { if (opaque(x, y)) { r = x; gap = 0 } else if (++gap > 3) break; x++ }
        val rw = r - l + 1
        if (top < 0) { if (rw < minRun) continue; top = y }
        // Fin de la tête : le cou (nettement plus étroit que la tête), ou au plus 1,3 largeur de tête sous le haut
        // des cheveux (cheveux longs sans cou visible). Les épaules s'élargissent progressivement : sans ces
        // limites, elles seraient comptées dans la tête.
        if (headW > 0 && y - top > headW * 0.5f && rw < headW * 0.8f) break
        if (headW > 0 && y - top > headW * 1.3f) break
        if (rw > headW) { headW = rw; headCx = (l + r) / 2f }
    }
    return if (top < 0 || headW < 8) null else Triple(top.toFloat(), headCx, headW.toFloat())
}

/** Le bas de la photo ne remonte jamais au-dessus du bas du cercle (= bas de l'avatar) : sinon, elle est descendue. */
private fun constrained(f: Framing, image: Bitmap): Framing {
    val size = AvatarStore.SIZE.toFloat()
    val bottom = f.y + image.height * f.scale
    return if (bottom < size) f.copy(y = size - image.height * f.scale) else f
}

/**
 * Création de l'avatar d'un membre : photo (caméra de la tablette, fichier, téléphone par QR code, photo de la
 * personne dans Home Assistant), détourage automatique, filtre, cadrage aux doigts, puis enregistrement.
 */
@Composable
fun AvatarEditor(memberId: String, name: String, personPicture: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = App.instance.config.avatars
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var cut by remember { mutableStateOf<Bitmap?>(null) }
    var cutting by remember { mutableStateOf(false) }
    var cutFailed by remember { mutableStateOf(false) }
    var face by remember { mutableStateOf<android.graphics.Rect?>(null) }
    var useCutout by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf(AvatarFilter.None) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var camera by remember { mutableStateOf(false) }
    var phone by remember { mutableStateOf(false) }
    /** Image affichée : photo filtrée, détourée si demandé (recalculée hors du thread principal). */
    var composite by remember { mutableStateOf<Bitmap?>(null) }
    var framing by remember { mutableStateOf<Framing?>(null) }
    val thumbs = remember { mutableStateMapOf<AvatarFilter, ImageBitmap>() }
    val filteredCache = remember { HashMap<AvatarFilter, Bitmap>() }
    val haFailed = stringResource(R.string.avatar_ha_failed)
    val fileFailed = stringResource(R.string.avatar_file_failed)

    fun setSource(b: Bitmap?) {
        if (b == null) { message = fileFailed; return }
        message = null
        source = b
        cut = null
        cutFailed = false
        filteredCache.clear()
        thumbs.clear()
        filter = AvatarFilter.None
        framing = null
        face = null
        cutting = true
        scope.launch {
            // Visage (cadrage de départ), puis détourage.
            face = detectFace(b)
            val c = withContext(Dispatchers.Default) { cutout(context, b) }
            cut = c
            cutFailed = c == null
            cutting = false
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { setSource(decodePhoto(context, uri)) }
    }

    // Image composée : filtre (mis en cache) + opacité du détourage.
    LaunchedEffect(source, cut, useCutout, filter) {
        val src = source ?: return@LaunchedEffect
        busy = true
        val cutoutOn = useCutout && cut != null
        composite = withContext(Dispatchers.Default) {
            val f = filteredCache.getOrPut(filter) { src.filtered(filter) }
            if (cutoutOn) f.withAlphaOf(cut!!) else f
        }
        busy = false
    }
    // Nouveau cadrage quand la photo ou le mode change (pas quand seul le filtre change).
    LaunchedEffect(source, cut, useCutout, cutting) {
        val src = source ?: return@LaunchedEffect
        if (cutting) return@LaunchedEffect
        val cutoutOn = useCutout && cut != null
        val img = if (cutoutOn) src.withAlphaOf(cut!!) else src
        framing = withContext(Dispatchers.Default) { initialFraming(img, cutoutOn, face) }
    }
    // Vignettes des filtres (petite image, rapide).
    LaunchedEffect(source, cut, useCutout) {
        val src = source ?: return@LaunchedEffect
        val small = src.fit(160)
        val mask = if (useCutout) cut?.fit(160) else null
        AvatarFilter.entries.forEach { f ->
            val t = withContext(Dispatchers.Default) { small.filtered(f).let { if (mask != null) it.withAlphaOf(mask) else it } }
            thumbs[f] = t.asImageBitmap()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth(0.86f).fillMaxHeight(0.9f).clip(RoundedCornerShape(20.dp)).background(Panel).padding(24.dp)) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                // --- Aperçu et cadrage
                // Aperçu, vignettes et aide répartis sur toute la hauteur.
                Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                    val mode = if (useCutout && cut != null) "cutout" else "photo"
                    val img = composite
                    val fr = framing
                    Box(Modifier.size(380.dp), contentAlignment = Alignment.Center) {
                        AvatarPreview(img, fr, mode, true, Modifier.fillMaxSize().pointerInput(img, mode) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                val f = framing ?: return@detectTransformGestures
                                val unit = AvatarStore.SIZE / size.width.toFloat()
                                val cx = centroid.x * unit
                                val cy = centroid.y * unit
                                val k = (f.scale * zoom).coerceIn(0.05f, 20f)
                                val z = k / f.scale
                                val next = Framing(k, cx - (cx - f.x) * z + pan.x * unit, cy - (cy - f.y) * z + pan.y * unit)
                                framing = img?.let { constrained(next, it) } ?: next
                            }
                        })
                        if (cutting || busy) CircularProgressIndicator(Modifier.size(40.dp), color = Dash.colors.accent)
                    }
                    // Rendu sur le tableau de bord, dans les deux états : encadré, avec une pastille par état.
                    val panel = RoundedCornerShape(18.dp)
                    Row(
                        Modifier.clip(panel).background(Color(0xFF19191D), panel).border(1.dp, Color(0xFF2C2C33), panel)
                            .padding(horizontal = 22.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                    ) {
                        listOf(true to R.string.avatar_preview_active, false to R.string.avatar_preview_inactive).forEach { (active, label) ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                AvatarPreview(img, fr, mode, active, Modifier.size(96.dp))
                                Row(
                                    Modifier.padding(top = 12.dp).clip(RoundedCornerShape(50))
                                        .background(if (active) Dash.colors.accent.copy(alpha = 0.14f) else Color(0xFF2A2A30))
                                        .padding(horizontal = 12.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (active) Dash.colors.accent else Color(0xFF8A8A94)))
                                    Text(
                                        stringResource(label), Modifier.padding(start = 7.dp),
                                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (active) Dash.colors.accent else Color(0xFFC8C8D0)),
                                    )
                                }
                            }
                        }
                    }
                    Text(stringResource(if (source != null) R.string.avatar_frame_help else R.string.avatar_pick_hint), Modifier.width(380.dp), style = TextStyle(fontSize = 13.sp, color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center))
                }
                // --- Réglages
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.avatar_title, name), Modifier.weight(1f), style = TextStyle(fontSize = 22.sp, color = Color.White, fontWeight = FontWeight.Medium))
                        Box(Modifier.size(44.dp).clip(CircleShape).tapHold(onDismiss), contentAlignment = Alignment.Center) {
                            HaIcon("mdi:close", size = 26.dp, tint = Color(255, 255, 255, 200))
                        }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Section(stringResource(R.string.avatar_source)) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SourceRow("mdi:camera-front-variant", R.string.avatar_src_camera, R.string.avatar_src_camera_desc) { camera = true }
                                SourceRow("mdi:image-outline", R.string.avatar_src_file, R.string.avatar_src_file_desc) {
                                    pickFile.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }
                                SourceRow("mdi:cellphone-arrow-down", R.string.avatar_src_phone, R.string.avatar_src_phone_desc) { phone = true }
                                if (personPicture != null) SourceRow("mdi:home-assistant", R.string.avatar_src_ha, R.string.avatar_src_ha_desc) {
                                    scope.launch {
                                        val req = ImageRequest.Builder(context).data(personPicture).allowHardware(false).build()
                                        val bmp = (context.imageLoader.execute(req) as? SuccessResult)?.drawable
                                            ?.let { (it as? android.graphics.drawable.BitmapDrawable)?.bitmap }
                                        if (bmp == null) message = haFailed else setSource(bmp.copy(Bitmap.Config.ARGB_8888, true).fit())
                                    }
                                }
                            }
                            message?.let { Text(it, style = TextStyle(fontSize = 13.sp, color = Color(0xFFFF6B6B))) }
                        }
                        if (source != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                                    Text(stringResource(R.string.avatar_cutout), style = TextStyle(fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Medium))
                                    Text(
                                        stringResource(when { cutting -> R.string.avatar_cutting; cutFailed -> R.string.avatar_cutout_failed; else -> R.string.avatar_cutout_help }),
                                        style = TextStyle(fontSize = 13.sp, color = if (cutFailed) Color(0xFFFFB74D) else Muted),
                                    )
                                }
                                Switch(
                                    useCutout && !cutFailed, { useCutout = it }, enabled = cut != null,
                                    colors = SwitchDefaults.colors(checkedTrackColor = Dash.colors.accent, checkedThumbColor = Color.Black),
                                )
                            }
                            Section(stringResource(R.string.avatar_filter)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    AvatarFilter.entries.forEach { f ->
                                        val selected = f == filter
                                        Column(
                                            Modifier.clip(RoundedCornerShape(14.dp)).tapHold({ filter = f })
                                                .border(2.dp, if (selected) Dash.colors.accent else Color.Transparent, RoundedCornerShape(14.dp)).padding(6.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                        ) {
                                            Box(Modifier.size(84.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF181818)), contentAlignment = Alignment.Center) {
                                                thumbs[f]?.let { Image(it, null, Modifier.fillMaxSize()) }
                                                    ?: CircularProgressIndicator(Modifier.size(20.dp), color = Muted, strokeWidth = 2.dp)
                                            }
                                            Text(stringResource(filterLabel(f)), Modifier.padding(top = 4.dp), style = TextStyle(fontSize = 13.sp, color = if (selected) Color.White else Muted))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // --- Actions
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (store.avatars.value.containsKey(memberId)) {
                            Pill("mdi:delete-outline", stringResource(R.string.avatar_remove)) { store.remove(memberId); onDismiss() }
                        }
                        Spacer(Modifier.weight(1f))
                        val img = composite
                        val fr = framing
                        Pill("mdi:check", stringResource(R.string.avatar_save), primary = true, enabled = img != null && fr != null && !busy && !cutting) {
                            if (img == null || fr == null) return@Pill
                            val mode = if (useCutout && cut != null) "cutout" else "photo"
                            scope.launch {
                                busy = true
                                val baked = withContext(Dispatchers.Default) { bakeAvatar(img, fr.scale, fr.x, fr.y) }
                                withContext(Dispatchers.IO) { store.save(memberId, baked, mode) }
                                busy = false
                                onDismiss()
                            }
                        }
                    }
                }
            }
        }
    }
    if (camera) AvatarCamera(onClose = { camera = false }) { b -> camera = false; setSource(b) }
    if (phone) PhoneDialog(onClose = { phone = false }) { bytes ->
        phone = false
        scope.launch { setSource(withContext(Dispatchers.Default) { decodePhoto(bytes) }) }
    }
}

private fun filterLabel(f: AvatarFilter) = when (f) {
    AvatarFilter.None -> R.string.avatar_filter_none
    AvatarFilter.Soft -> R.string.avatar_filter_soft
    AvatarFilter.Poster -> R.string.avatar_filter_poster
    AvatarFilter.Comic -> R.string.avatar_filter_comic
}

/** Aperçu de l'avatar avec le cadrage en cours (même dessin que sur le tableau de bord). */
@Composable
private fun AvatarPreview(image: Bitmap?, framing: Framing?, mode: String, active: Boolean, modifier: Modifier) {
    val bg = avatarBackground(active)
    val img = remember(image) { image?.asImageBitmap() }
    Canvas(modifier) {
        val unit = size.width / AvatarStore.SIZE
        drawAvatarWith(mode, bg, active, img != null && framing != null) { cf ->
            val f = framing!!
            translate(f.x * unit, f.y * unit) {
                scale(f.scale * unit, f.scale * unit, pivot = Offset.Zero) { drawImage(img!!, colorFilter = cf) }
            }
        }
    }
}

/** Source de la photo : ligne comme dans l'assistant de configuration (icône, titre, description, flèche). */
@Composable
private fun SourceRow(icon: String, title: Int, desc: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Color(0xFF19191D), shape).border(1.dp, Color(0xFF2C2C33), shape)
            .tapHold(onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Dash.colors.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            HaIcon(icon, size = 24.dp, tint = Dash.colors.accent)
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(stringResource(title), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White))
            Text(stringResource(desc), Modifier.padding(top = 2.dp), style = TextStyle(fontSize = 13.sp, color = Muted, lineHeight = 18.sp))
        }
        HaIcon("mdi:chevron-right", size = 22.dp, tint = Muted)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = TextStyle(fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Medium))
        content()
    }
}

@Composable
private fun Pill(icon: String, text: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val bg = when { !enabled -> Color(0xFF2A2A2A); primary -> Dash.colors.accent; else -> Color(0xFF333333) }
    val fg = when { !enabled -> Muted; primary -> Color.Black; else -> Color.White }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(bg).tapHold(if (enabled) onClick else null).heightIn(min = 44.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HaIcon(icon, size = 20.dp, tint = fg)
        Text(text, Modifier.padding(start = 8.dp), style = TextStyle(fontSize = 15.sp, color = fg, fontWeight = FontWeight.Medium))
    }
}

// --- Caméra de la tablette ----------------------------------------------------------------------------------

/**
 * Photo avec la caméra de la tablette (frontale de préférence), dans l'app : rien à ouvrir en dehors, même en mode
 * kiosque. Compte à rebours de 3 s pour avoir le temps de se placer. La détection de présence (qui utilise la même
 * caméra) est suspendue pendant la prise de vue.
 */
@Composable
private fun AvatarCamera(onClose: () -> Unit, onPhoto: (Bitmap) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    var failed by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    var front by remember { mutableStateOf(true) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val scope = rememberCoroutineScope()

    DisposableEffect(granted, front) {
        App.instance.presence.stop()
        var provider: ProcessCameraProvider? = null
        if (granted) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                runCatching {
                    val p = future.get()
                    provider = p
                    val wanted = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    val selector = if (p.hasCamera(wanted)) wanted else if (front) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                    val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    p.unbindAll()
                    p.bindToLifecycle(owner, selector, preview, capture)
                }.onFailure { failed = true }
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose {
            provider?.unbindAll()
            (context as? fr.arubinu.hadash.MainActivity)?.updatePresence()
        }
    }

    fun shoot() {
        scope.launch {
            for (i in 3 downTo 1) { countdown = i; delay(1000) }
            countdown = 0
            capture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val rotation = image.imageInfo.rotationDegrees
                    val raw = image.toBitmap()
                    image.close()
                    val m = Matrix().apply {
                        postRotate(rotation.toFloat())
                        // Caméra frontale : image en miroir, comme l'aperçu.
                        if (front) postScale(-1f, 1f)
                    }
                    onPhoto(Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).fit())
                }
                override fun onError(exception: ImageCaptureException) { failed = true }
            })
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth(0.7f).fillMaxHeight(0.85f).clip(RoundedCornerShape(20.dp)).background(Color.Black)) {
            if (granted && !failed) AndroidView({ previewView }, Modifier.fillMaxSize())
            else Text(
                stringResource(R.string.avatar_camera_unavailable), Modifier.align(Alignment.Center).padding(32.dp),
                style = TextStyle(fontSize = 16.sp, color = Muted),
            )
            if (countdown > 0) Text(
                "$countdown", Modifier.align(Alignment.Center),
                style = TextStyle(fontSize = 120.sp, color = Color.White, fontWeight = FontWeight.Bold),
            )
            Box(Modifier.align(Alignment.TopEnd).padding(12.dp).size(48.dp).clip(CircleShape).background(Color(0x99000000)).tapHold(onClose), contentAlignment = Alignment.Center) {
                HaIcon("mdi:close", size = 26.dp, tint = Color.White)
            }
            Row(Modifier.align(Alignment.BottomCenter).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("mdi:camera-flip-outline", stringResource(R.string.avatar_camera_switch)) { front = !front }
                Pill("mdi:camera", stringResource(R.string.avatar_camera_capture), primary = true, enabled = granted && !failed && countdown == 0) { shoot() }
            }
        }
    }
}

// --- Téléphone (QR code) ------------------------------------------------------------------------------------

@Composable
private fun PhoneDialog(onClose: () -> Unit, onPhoto: (ByteArray) -> Unit) {
    val scope = rememberCoroutineScope()
    val texts = PhoneUpload.Texts(
        stringResource(R.string.avatar_phone_page_title), stringResource(R.string.avatar_phone_page_take),
        stringResource(R.string.avatar_phone_page_choose), stringResource(R.string.avatar_phone_page_sending),
        stringResource(R.string.avatar_phone_page_done), stringResource(R.string.avatar_phone_page_error),
    )
    var url by remember { mutableStateOf<String?>(null) }
    var started by remember { mutableStateOf(false) }
    val upload = remember {
        PhoneUpload(texts) { bytes -> scope.launch(Dispatchers.Main) { onPhoto(bytes) } }
    }
    DisposableEffect(Unit) {
        url = runCatching { upload.start(scope) }.getOrNull()
        started = true
        onDispose { upload.stop() }
    }
    val qr = remember(url) { url?.let { PhoneUpload.qr(it).asImageBitmap() } }
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(Panel).padding(28.dp).width(420.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.avatar_phone_title), style = TextStyle(fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Medium))
            if (qr != null) {
                Text(stringResource(R.string.avatar_phone_help), style = TextStyle(fontSize = 14.sp, color = Muted))
                Image(qr, null, Modifier.size(260.dp).clip(RoundedCornerShape(12.dp)))
                Text(url ?: "", style = TextStyle(fontSize = 12.sp, color = Muted))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Dash.colors.accent, strokeWidth = 2.dp)
                    Text(stringResource(R.string.avatar_phone_waiting), Modifier.padding(start = 10.dp), style = TextStyle(fontSize = 14.sp, color = Color.White))
                }
            } else if (started) {
                Text(stringResource(R.string.avatar_phone_no_network), style = TextStyle(fontSize = 14.sp, color = Color(0xFFFFB74D)))
            }
            Pill("mdi:close", stringResource(R.string.cancel)) { onClose() }
        }
    }
}
