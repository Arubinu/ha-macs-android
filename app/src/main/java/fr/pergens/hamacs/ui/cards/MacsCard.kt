package fr.pergens.hamacs.ui.cards

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import fr.pergens.hamacs.ui.components.editOutline
import androidx.compose.ui.viewinterop.AndroidView
import fr.pergens.hamacs.App
import fr.pergens.hamacs.config.Card
import fr.pergens.hamacs.config.d
import fr.pergens.hamacs.config.i
import fr.pergens.hamacs.config.o
import fr.pergens.hamacs.config.s
import fr.pergens.hamacs.ui.LocalDashState
import fr.pergens.hamacs.ui.components.DashCard
import fr.pergens.hamacs.ui.components.tapHold

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
        val onTap = {
            // Mode édition : réglages de M.A.C.S. (capteurs, batterie, satellite…).
            if (!state.editInstead(fr.pergens.hamacs.ui.EditTarget.Macs())) state.perform(card.o("tap_action"), null, defaultAction = "voice")
        }
        // Grand cercle invisible centré sur M.A.C.S. (90 % de la plus petite dimension de la tuile) : toucher = parler,
        // appui long = `hold_action` (ex. retour à l'accueil), zones noires comprises. Hors du cercle : rien, sauf en mode
        // édition (réglages de M.A.C.S.) ; la tuile touche le coin des réglages, un doigt qui débordait changeait de page.
        // Le doigt posé (et glissé) sur M.A.C.S. : ses yeux le suivent. Observation seule (rien n'est consommé).
        val fill = card.s("position") == "fill"
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    var down = false
                    while (true) {
                        val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        val c = e.changes.firstOrNull { it.pressed }
                        if (c == null) {
                            if (down) macs.lookEnd()
                            down = false
                            continue
                        }
                        down = true
                        // Couche au premier plan : la WebView déborde en bas de `overflow_bottom`.
                        val area = this.size
                        val h = area.height + (if (front) overflowPx else 0)
                        macs.look(c.position.x / area.width.coerceAtLeast(1), c.position.y / h.coerceAtLeast(1))
                    }
                }
            }.pointerInput(card, state.editMode) {
                val zone = this
                fun inCircle(p: androidx.compose.ui.geometry.Offset): Boolean {
                    val w = zone.size.width.toFloat()
                    val h = zone.size.height.toFloat()
                    val r = minOf(w, h) * 0.45f
                    val dx = p.x - w / 2f
                    val dy = p.y - h / 2f
                    return dx * dx + dy * dy <= r * r
                }
                detectTapGestures(
                    onTap = { p -> if (inCircle(p)) onTap() else state.editInstead(fr.pergens.hamacs.ui.EditTarget.Macs()) },
                    // Grand M.A.C.S. de l'accueil (plein écran) : pas d'appui long, le doigt posé sert à guider ses yeux.
                    onLongPress = { p ->
                        if (!fill && inCircle(p) && !state.editMode) state.perform(card.o("hold_action"), null, defaultAction = "none")
                    },
                )
            }
        )
    }
}
