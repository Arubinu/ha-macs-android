package fr.pergens.hamacs.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import fr.pergens.hamacs.ui.PlaybackLogic
import fr.pergens.hamacs.ui.theme.Dash
import kotlinx.coroutines.delay

/** Avancement d'une lecture (position, durée) et déplacement dans la vidéo. Relié à un lecteur par [bind]. */
@Stable
class PlaybackProgress {
    var position by mutableLongStateOf(0L)
        internal set
    var duration by mutableLongStateOf(0L)
        internal set
    var live by mutableStateOf(false)
        internal set
    internal var seeker: ((Long) -> Unit)? = null

    val fraction: Float get() = PlaybackLogic.fraction(position, duration)
    val hasProgress: Boolean get() = PlaybackLogic.hasProgress(duration, live)

    fun seek(ms: Long) {
        position = ms
        seeker?.invoke(ms)
    }
}

@Composable
fun rememberPlaybackProgress(): PlaybackProgress = remember { PlaybackProgress() }

/** Suit la position du lecteur (10 fois par seconde pendant la lecture) et permet de s'y déplacer. */
@Composable
fun PlaybackProgress.bind(player: Player) {
    val p = this
    DisposableEffect(player) {
        p.seeker = { ms ->
            // Lecture finie : un déplacement relance la lecture depuis ce point.
            if (player.playbackState == Player.STATE_ENDED) player.playWhenReady = true
            player.seekTo(ms)
        }
        onDispose { p.seeker = null }
    }
    LaunchedEffect(player) {
        while (true) {
            val d = player.duration
            p.duration = if (d == C.TIME_UNSET) 0L else d.coerceAtLeast(0L)
            p.live = player.isCurrentMediaItemLive
            p.position = player.currentPosition.coerceAtLeast(0L)
            delay(if (player.isPlaying) 100L else 250L)
        }
    }
}

/**
 * Barre d'avancement fine, aux couleurs du tableau de bord : piste discrète, partie lue en couleur d'accent.
 * [seekable] : toucher ou glisser pour se déplacer (zone tactile de 32dp, curseur rond pendant le geste).
 */
@Composable
fun PlaybackBar(progress: PlaybackProgress, modifier: Modifier = Modifier, seekable: Boolean = false, onSeek: () -> Unit = {}) {
    val accent = Dash.colors.accent
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val shown = if (dragging) dragFraction else progress.fraction
    val track by animateDpAsState(if (dragging) 6.dp else 4.dp, tween(150), label = "track")
    BoxWithConstraints(
        modifier.fillMaxWidth().height(if (seekable) 32.dp else track)
            .then(
                if (!seekable) Modifier else Modifier
                    .pointerInput(progress) {
                        detectTapGestures { o ->
                            progress.seek(PlaybackLogic.positionAt(o.x / size.width, progress.duration))
                            onSeek()
                        }
                    }
                    .pointerInput(progress) {
                        detectHorizontalDragGestures(
                            onDragStart = { o -> dragging = true; dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                            onDragEnd = {
                                progress.seek(PlaybackLogic.positionAt(dragFraction, progress.duration))
                                dragging = false
                                onSeek()
                            },
                            onDragCancel = { dragging = false },
                        ) { change, delta ->
                            change.consume()
                            dragFraction = (dragFraction + delta / size.width).coerceIn(0f, 1f)
                        }
                    },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        val w = maxWidth
        Box(Modifier.fillMaxWidth().height(track).clip(CircleShape).background(Color(0x40FFFFFF))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(shown).background(accent))
        }
        if (seekable) {
            val thumb by animateDpAsState(if (dragging) 16.dp else 12.dp, tween(150), label = "thumb")
            Box(
                Modifier.offset(x = w * shown - thumb / 2).size(thumb).clip(CircleShape).background(Color.White)
                    .padding(3.dp).clip(CircleShape).background(accent),
            )
        }
    }
}

/** Temps écoulé et durée de part et d'autre de la barre : « 0:03 ━━━━━━──── 0:06 ». */
@Composable
fun PlaybackTimeline(progress: PlaybackProgress, modifier: Modifier = Modifier, onSeek: () -> Unit = {}) {
    val style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White, fontFeatureSettings = "tnum")
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(PlaybackLogic.clock(progress.position), style = style)
        PlaybackBar(progress, Modifier.weight(1f).padding(horizontal = 12.dp), seekable = true, onSeek = onSeek)
        Text(PlaybackLogic.clock(progress.duration), style = style.copy(color = Color(255, 255, 255, 180)))
    }
}
