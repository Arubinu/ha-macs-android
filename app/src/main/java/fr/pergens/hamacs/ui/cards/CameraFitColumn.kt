package fr.pergens.hamacs.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.pergens.hamacs.config.Card
import fr.pergens.hamacs.config.b
import fr.pergens.hamacs.config.s
import fr.pergens.hamacs.ui.WallLogic
import fr.pergens.hamacs.ui.components.HaIcon
import fr.pergens.hamacs.ui.components.tapHold
import fr.pergens.hamacs.ui.theme.Dash

/** Hauteur de la tuile « +N ». */
private val MORE_HEIGHT = 56.dp

/**
 * Caméras d'un onglet, limitées au bas de l'écran moins [bottomMargin] : les premières (ordre de l'onglet, réglable
 * dans son éditeur) et, s'il en reste, une tuile « +N » qui ouvre le mur de caméras ([onMore]). Le nombre est
 * calculé d'après la largeur et le format de chaque carte, sans les composer : une caméra qui ne tient pas n'est
 * jamais chargée (pas de caméra sur batterie réveillée pour rien).
 */
@Composable
fun CameraFitColumn(cards: List<Card>, bottomMargin: Dp, onMore: () -> Unit) {
    val rootHeight = fr.pergens.hamacs.ui.components.rememberRootHeight()
    val density = LocalDensity.current
    var top by remember { mutableStateOf<Float?>(null) }
    val gap = Dash.shapes.gutter
    val shown = cards.filter { !it.b("hidden") }
    BoxWithConstraints(Modifier.fillMaxWidth().onGloballyPositioned { top = it.positionInWindow().y }) {
        // Position inconnue (première image) : rien de chargé tant que la place disponible n'est pas connue.
        val t = top ?: return@BoxWithConstraints
        // Espace libre réparti au-dessus par la colonne : il reviendrait aux caméras si elles en avaient besoin.
        val slack = fr.pergens.hamacs.ui.LocalColumnSlack.current?.intValue ?: 0
        val available = with(density) { (rootHeight - t + slack).toDp() } - bottomMargin
        val width = maxWidth.value
        // Autre chose qu'une caméra dans l'onglet : hauteur inconnue, tout est affiché.
        val n = if (shown.any { it.s("type") != "camera" }) shown.size
        else WallLogic.visibleCount(shown.map { width / parseAspect(it.s("aspect_ratio")) }, gap.value, available.value, MORE_HEIGHT.value)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
            shown.take(n).forEach { RenderCard(it) }
            if (n < shown.size) MoreCamerasTile(shown.size - n, onMore)
        }
    }
}

/** Tuile « +N » : icône de grille et nombre de caméras cachées ; ouvre le mur de caméras. */
@Composable
private fun MoreCamerasTile(count: Int, onClick: () -> Unit) {
    val c = Dash.colors
    Row(
        Modifier.fillMaxWidth().height(MORE_HEIGHT).clip(RoundedCornerShape(Dash.shapes.roundedRadius))
            .background(c.menuBackground).tapHold(onClick).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HaIcon("mdi:view-grid-outline", size = 24.dp, tint = c.menuActiveButton)
        Text(
            "+$count", Modifier.padding(start = 10.dp),
            style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium, color = c.menuActiveButton),
        )
    }
}
