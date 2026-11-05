package fr.arubinu.hadash.ui.avatar

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import fr.arubinu.hadash.config.AvatarStore
import fr.arubinu.hadash.ui.theme.Dash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fond du cercle d'un membre non sélectionné. */
val AvatarInactiveBackground = Color(0xFF181818)

private val Grayscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * Dessin d'un avatar composé, avec les proportions d'origine : cercle de 836/1024 posé en bas et centré,
 * liseré blanc de 25/1024 à l'intérieur, image enregistrée de 1024 × 1024 déjà cadrée.
 * - `cutout` : fond du cercle (gris foncé, ou couleur d'accent quand le membre est sélectionné), liseré, puis le
 *   personnage vu à travers le cercle ET tout ce qui est au-dessus de son centre : le bas reste dans le cercle,
 *   la tête dépasse et passe devant le liseré.
 * - `photo` : photo dans le cercle, en noir et blanc quand le membre n'est pas sélectionné, liseré par-dessus.
 */
fun DrawScope.drawAvatar(image: ImageBitmap?, mode: String, background: Color, active: Boolean) {
    val dst = IntSize(size.width.toInt(), size.height.toInt())
    drawAvatarWith(mode, background, active, image != null) { filter -> drawImage(image!!, dstSize = dst, colorFilter = filter) }
}

/** Même dessin, le personnage étant dessiné par [subject] (l'éditeur le place selon le cadrage en cours). */
fun DrawScope.drawAvatarWith(mode: String, background: Color, active: Boolean, hasSubject: Boolean, subject: DrawScope.(ColorFilter?) -> Unit) {
    val s = size.minDimension
    val r = s * AvatarStore.CIRCLE / 2f
    val c = Offset(size.width / 2f, size.height - r)
    val ring = s * AvatarStore.RING
    drawCircle(background, r, c)
    if (mode == "photo") {
        if (hasSubject) clipPath(Path().apply { addOval(Rect(c, r - ring / 2f)) }) { subject(if (active) null else Grayscale) }
        drawCircle(Color.White, r - ring / 2f, c, style = Stroke(ring))
    } else {
        drawCircle(Color.White, r - ring / 2f, c, style = Stroke(ring))
        if (hasSubject) {
            val clip = Path().apply {
                op(Path().apply { addOval(Rect(c, r)) }, Path().apply { addRect(Rect(0f, 0f, size.width, c.y)) }, PathOperation.Union)
            }
            clipPath(clip) { subject(null) }
        }
    }
}

/** Couleur du fond du cercle, en fondu entre gris foncé et couleur d'accent. */
@Composable
fun avatarBackground(active: Boolean): Color =
    animateColorAsState(if (active) Dash.colors.accent else AvatarInactiveBackground, tween(400), label = "avatarBg").value

/**
 * Avatar d'un membre : avatar créé sur la tablette s'il existe, sinon photo de la personne Home Assistant
 * ([personPicture], en rond, noir et blanc quand non sélectionnée), sinon image de [fallbackUrl]
 * (paire d'images `_active` / `_inactive` toute faite).
 */
@Composable
fun MemberAvatar(memberId: String, active: Boolean, personPicture: String?, fallbackUrl: String?, modifier: Modifier = Modifier) {
    val store = fr.arubinu.hadash.App.instance.config.avatars
    val avatars by store.avatars.collectAsState()
    val meta = avatars[memberId]
    val background = avatarBackground(active)
    when {
        meta != null -> {
            val image by produceState<ImageBitmap?>(null, memberId, meta.version) {
                value = withContext(Dispatchers.IO) { store.load(memberId)?.asImageBitmap() }
            }
            Canvas(modifier) { drawAvatar(image, meta.mode, background, active) }
        }
        personPicture != null -> BoxWithConstraints(modifier) {
            // Photo de Home Assistant (URL) : même géométrie, l'image est posée dans le disque intérieur.
            val s = minOf(maxWidth, maxHeight)
            val inner = s * (AvatarStore.CIRCLE - 2 * AvatarStore.RING)
            Canvas(Modifier.fillMaxSize()) { drawAvatar(null, "photo", background, active) }
            AsyncImage(
                model = personPicture, contentDescription = memberId, contentScale = ContentScale.Crop,
                colorFilter = if (active) null else Grayscale,
                modifier = Modifier.align(Alignment.BottomCenter).offset(y = -s * AvatarStore.RING).size(inner).clip(CircleShape),
            )
        }
        else -> androidx.compose.foundation.layout.Box(modifier) {
            // Image absente ou introuvable : le cercle seul reste visible, avec une silhouette.
            var failed by androidx.compose.runtime.remember(fallbackUrl) { androidx.compose.runtime.mutableStateOf(fallbackUrl == null) }
            if (failed) {
                Canvas(Modifier.fillMaxSize()) { drawAvatar(null, "photo", background, active) }
                fr.arubinu.hadash.ui.components.HaIcon(
                    "mdi:account", Modifier.align(Alignment.BottomCenter).offset(y = (-14).dp),
                    size = 40.dp, tint = if (active) Color.Black else Color(0xFF8A8A94),
                )
            } else AsyncImage(
                model = fallbackUrl, contentDescription = memberId, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(), onError = { failed = true },
            )
        }
    }
}
