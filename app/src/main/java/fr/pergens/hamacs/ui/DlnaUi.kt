package fr.pergens.hamacs.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
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
 * Son envoyé par Home Assistant au lecteur DLNA de la tablette, avec pause / reprise et arrêt pour le couper sur place.
 * Sur l'écran d'accueil : pastille complète (titre, artiste) en bas à gauche, zone libre. Sur les autres écrans :
 * version compacte calée à gauche de la barre d'onglets du haut, dans l'espace vide, pour ne couvrir aucun bloc
 * (en bas à gauche s'il n'y a pas de barre d'onglets).
 */
@Composable
fun DlnaNowPlaying(state: DashState) {
    val dlna = App.instance.dlna
    val now by dlna.nowPlaying.collectAsState()
    // Dernier contenu affiché, gardé pendant l'animation de sortie.
    val last = remember { arrayOfNulls<DlnaRenderer.NowPlaying>(1) }
    now?.let { last[0] = it }
    // Rien par-dessus les reglages.
    if (state.showSettings) return
    val menu = state.topMenu.takeIf { state.screen != state.homeScreen }
    val gap = with(LocalDensity.current) { 16.dp.toPx() }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            now != null,
            modifier = if (menu == null) Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 24.dp)
            else Modifier.layout { m, c ->
                // Bord droit à 16 dp de la barre d'onglets, centré sur sa hauteur.
                val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
                layout(p.width, p.height) {
                    p.place(
                        (menu.left - gap - p.width).toInt().coerceAtLeast(0),
                        (menu.center.y - p.height / 2f).toInt().coerceAtLeast(0),
                    )
                }
            },
            enter = fadeIn(), exit = fadeOut(),
        ) {
            val n = now ?: last[0] ?: return@AnimatedVisibility
            val shape = RoundedCornerShape(28.dp)
            Row(
                Modifier.widthIn(max = 520.dp).clip(shape).background(Color(0xF21C1C21), shape)
                    .border(1.dp, Dash.colors.accent.copy(alpha = 0.5f), shape)
                    .padding(start = if (menu == null) 18.dp else 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HaIcon("mdi:music-note", size = 20.dp, tint = Dash.colors.accent)
                if (menu == null) Column(Modifier.padding(start = 12.dp, end = 8.dp).weight(1f, fill = false).padding(vertical = 6.dp)) {
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
    Box(Modifier.size(40.dp).clip(CircleShape).tapHold(onClick), contentAlignment = Alignment.Center) {
        HaIcon(icon, size = 22.dp, tint = Color.White)
    }
}
