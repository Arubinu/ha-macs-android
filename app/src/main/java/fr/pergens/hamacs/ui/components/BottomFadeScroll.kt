package fr.pergens.hamacs.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.pergens.hamacs.ui.theme.Dash

/**
 * Hauteur de la fenêtre en pixels, suivie (taille d'écran changée sans recréer l'activité : la valeur lue une fois
 * resterait l'ancienne).
 */
@Composable
fun rememberRootHeight(): Int {
    val root = LocalView.current.rootView
    var height by remember(root) { mutableStateOf(root.height) }
    androidx.compose.runtime.DisposableEffect(root) {
        val listener = android.view.View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> height = v.height }
        root.addOnLayoutChangeListener(listener)
        onDispose { root.removeOnLayoutChangeListener(listener) }
    }
    return height
}

/**
 * Colonne limitée au bas de l'écran moins [bottomMargin] (= niveau du bas des caméras) : au-delà, elle défile seule
 * et s'efface en fondu en bas (et en haut une fois défilée) au lieu d'être coupée net.
 */
@Composable
fun BottomFadeScroll(
    modifier: Modifier = Modifier,
    bottomMargin: Dp = 24.dp,
    spacing: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val rootHeight = rememberRootHeight()
    val density = LocalDensity.current
    var top by remember { mutableStateOf<Float?>(null) }
    // Espace libre réparti au-dessus par une colonne ajustée : il reviendrait à cette liste si elle en avait besoin.
    val slack = fr.pergens.hamacs.ui.LocalColumnSlack.current?.intValue ?: 0
    val maxHeight = top?.let { with(density) { (rootHeight - it + slack).toDp() } - bottomMargin }
    val scroll = rememberScrollState()
    Box(
        modifier.fillMaxWidth()
            .onGloballyPositioned { top = it.positionInWindow().y }
            // Toujours une hauteur maximale finie (liste défilante dans une colonne qui défile) : provisoire tant
            // que la position n'est pas mesurée, sinon Compose plante.
            .heightIn(max = maxHeight?.coerceAtLeast(120.dp) ?: 400.dp),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
        val bg = Dash.colors.background
        if (scroll.canScrollForward) Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(64.dp)
                .background(Brush.verticalGradient(listOf(bg.copy(alpha = 0f), bg))),
        )
        if (scroll.canScrollBackward) Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(24.dp)
                .background(Brush.verticalGradient(listOf(bg, bg.copy(alpha = 0f)))),
        )
    }
}
