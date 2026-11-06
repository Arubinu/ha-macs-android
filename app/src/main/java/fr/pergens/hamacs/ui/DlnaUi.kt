package fr.pergens.hamacs.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.pergens.hamacs.App
import fr.pergens.hamacs.R
import fr.pergens.hamacs.dlna.DlnaRenderer
import fr.pergens.hamacs.ui.components.HaIcon
import fr.pergens.hamacs.ui.components.tapHold
import fr.pergens.hamacs.ui.theme.Dash

/**
 * Lecture envoyée par Home Assistant au lecteur DLNA de la tablette : pastille en bas à gauche (titre, artiste) avec
 * pause / reprise et arrêt, pour couper le son sur place.
 */
@Composable
fun DlnaNowPlaying() {
    val dlna = App.instance.dlna
    val now by dlna.nowPlaying.collectAsState()
    // Dernier contenu affiché, gardé pendant l'animation de sortie.
    val last = remember { arrayOfNulls<DlnaRenderer.NowPlaying>(1) }
    now?.let { last[0] = it }
    Box(Modifier.fillMaxSize().padding(start = 24.dp, bottom = 24.dp), contentAlignment = Alignment.BottomStart) {
        AnimatedVisibility(now != null, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
            val n = now ?: last[0] ?: return@AnimatedVisibility
            val shape = RoundedCornerShape(28.dp)
            Row(
                Modifier.widthIn(max = 520.dp).clip(shape).background(Color(0xF21C1C21), shape)
                    .border(1.dp, Dash.colors.accent.copy(alpha = 0.5f), shape).padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HaIcon("mdi:music-note", size = 22.dp, tint = Dash.colors.accent)
                Column(Modifier.padding(horizontal = 12.dp).weight(1f, fill = false)) {
                    Text(
                        n.title ?: stringResource(R.string.dlna_playing), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Medium),
                    )
                    n.artist?.let {
                        Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontSize = 13.sp, color = Color(0xFFA3A3AD)))
                    }
                }
                ChipButton(if (n.playing) "mdi:pause" else "mdi:play") { dlna.togglePause() }
                ChipButton("mdi:stop") { dlna.stopPlayback() }
            }
        }
    }
}

@Composable
private fun ChipButton(icon: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).tapHold(onClick), contentAlignment = Alignment.Center) {
        HaIcon(icon, size = 24.dp, tint = Color.White)
    }
}
