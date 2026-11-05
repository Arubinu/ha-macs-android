package fr.arubinu.hadash.ui.cards

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import fr.arubinu.hadash.ui.components.editOutline
import androidx.compose.ui.viewinterop.AndroidView
import fr.arubinu.hadash.App
import fr.arubinu.hadash.config.Card
import fr.arubinu.hadash.config.d
import fr.arubinu.hadash.config.i
import fr.arubinu.hadash.config.o
import fr.arubinu.hadash.config.s
import fr.arubinu.hadash.ui.LocalDashState
import fr.arubinu.hadash.ui.components.DashCard
import fr.arubinu.hadash.ui.components.tapHold

/**
 * Personnage M.A.C.S.
 * - `"layer": "front"` : couche native au premier plan, fond transparent, débordement `overflow_bottom`
 *   par-dessus les cartes voisines (sans bloquer leurs appuis) ;
 * - par défaut (« inline ») : intégré à l'interface, les éléments dessinés après lui restent au-dessus.
 * Appui = parler, appui long = action configurable (ex. retour à l'accueil).
 */
@Composable
fun MacsCard(card: Card, modifier: Modifier) {
    val state = LocalDashState.current
    val macs = App.instance.macs
    val token = remember { Any() }
    val front = card.s("layer") == "front"
    val overflowPx = with(LocalDensity.current) { card.d("overflow_bottom", 0.0).dp.roundToPx() }
    val size = when {
        card.i("height") != null -> modifier.fillMaxWidth().height(card.i("height")!!.dp)
        card.d("aspect_ratio") != null -> modifier.fillMaxWidth().aspectRatio(card.d("aspect_ratio")!!.toFloat())
        else -> modifier.fillMaxSize()
    }
    val lastBounds = remember { arrayOfNulls<android.graphics.Rect>(1) }

    DisposableEffect(token) {
        if (front) macs.claimFront(token)
        onDispose { macs.release(token) }
    }
    // Les réglages s'affichent dans l'interface Compose, donc sous la couche au premier plan : on la masque.
    if (front) LaunchedEffect(state.showSettings) { macs.updateFront(token, lastBounds[0], state.showSettings) }

    DashCard(
        (if (!front) size else size.onGloballyPositioned { coords ->
            val b = coords.boundsInWindow()
            val rect = android.graphics.Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt() + overflowPx)
            lastBounds[0] = rect
            macs.updateFront(token, rect, state.showSettings)
        }).then(Modifier.editOutline(state.editMode, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))),
    ) {
        if (!front) {
            AndroidView(
                factory = { ctx -> android.widget.FrameLayout(ctx).also { macs.claimInline(token, it) } },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier.fillMaxSize().tapHold(
                onTap = {
                    // Mode édition : réglages de M.A.C.S. (capteurs, batterie, satellite…).
                    if (state.editInstead(fr.arubinu.hadash.ui.EditTarget.Macs())) return@tapHold
                    state.perform(card.o("tap_action"), null, defaultAction = "voice")
                },
                onHold = { state.perform(card.o("hold_action"), null, defaultAction = "none") },
            )
        )
    }
}
