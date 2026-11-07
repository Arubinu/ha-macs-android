package fr.pergens.hamacs.ui.cards

import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.pergens.hamacs.config.Card
import fr.pergens.hamacs.config.d
import fr.pergens.hamacs.config.objs
import fr.pergens.hamacs.config.s
import fr.pergens.hamacs.ui.LocalDashState
import fr.pergens.hamacs.ui.components.HaIcon
import fr.pergens.hamacs.ui.components.tapHold
import fr.pergens.hamacs.ui.theme.Dash

private const val TRANSITION_MS = 320

/**
 * Équivalent du couple `state-switch` (entity: hash) + `embedded-view-card` + menu en pilules.
 * - nav "pills" : barre de pilules centrée au-dessus du contenu (Pièces / Membres / …)
 * - nav "rail"  : onglet vertical collé au bord gauche de l'écran (Météo / Carburant / Radar)
 * Le changement de page glisse dans le sens de l'onglet choisi.
 */
@Composable
fun SwitcherCard(card: Card, modifier: Modifier) {
    val state = LocalDashState.current
    val id = card.s("id") ?: card.hashCode().toString()
    val pages = card.objs("pages")
    if (pages.isEmpty()) return
    val pageIds = pages.mapNotNull { it.s("id") }
    SideEffect {
        state.registerSwitcher(id, pageIds.toSet())
        card.s("favorite")?.takeIf { it in pageIds }?.let { state.favorites[id] = it } ?: state.favorites.remove(id)
    }
    val current = state.pages[id]?.takeIf { it in pageIds } ?: card.s("favorite")?.takeIf { it in pageIds }
        ?: card.s("default") ?: pageIds.first()
    val select = { p: String -> state.pages[id] = p; state.lastInteraction = System.currentTimeMillis() }
    val vertical = card.s("nav") == "rail"

    val content = @Composable {
        AnimatedContent(
            targetState = current,
            transitionSpec = {
                val dir = if (pageIds.indexOf(targetState) >= pageIds.indexOf(initialState)) 1 else -1
                val spec = tween<androidx.compose.ui.unit.IntOffset>(TRANSITION_MS, easing = FastOutSlowInEasing)
                val fade = tween<Float>(TRANSITION_MS, easing = FastOutSlowInEasing)
                if (vertical) {
                    (slideInVertically(spec) { it / 6 * dir } + fadeIn(fade)) togetherWith
                        (slideOutVertically(spec) { -it / 6 * dir } + fadeOut(fade))
                } else {
                    (slideInHorizontally(spec) { it / 4 * dir } + fadeIn(fade)) togetherWith
                        (slideOutHorizontally(spec) { -it / 4 * dir } + fadeOut(fade))
                }
            },
            label = "switcher-$id",
        ) { pageId ->
            val page = pages.firstOrNull { it.s("id") == pageId } ?: pages.first()
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dash.shapes.gutter)) {
                page.objs("cards").forEach { RenderCard(it) }
            }
        }
    }

    when (card.s("nav", "pills")) {
        "rail" -> Box(modifier.fillMaxWidth()) {
            content()
            // Mode édition : une pastille ✎ sous le rail ouvre l'éditeur des onglets (noms, icônes, favori).
            val pagesKey = card.s("_key")?.takeIf { it.startsWith("pages:") } ?: fr.pergens.hamacs.config.BlockOverrides.pagesKey(card)
            Rail(
                pages, current, select, edgeOffset = card.d("rail_offset", 32.0).dp,
                onEdit = if (state.editMode && pagesKey != null) ({ state.editTarget = fr.pergens.hamacs.ui.EditTarget.Pages(pagesKey) }) else null,
            )
        }
        "none" -> Box(modifier.fillMaxWidth()) { content() }
        else -> Column(modifier.fillMaxWidth()) {
            // Mode édition : une pilule ✎ ouvre l'éditeur des onglets (onglets de caméras).
            val tabsKey = card.s("_key")?.takeIf { it.startsWith("tabs:") } ?: fr.pergens.hamacs.config.BlockOverrides.cameraTabsKey(card)
            // Onglets de caméras : leur fenêtre complète ; autres onglets : nom et icône.
            val pagesKey = card.s("_key")?.takeIf { it.startsWith("pages:") } ?: fr.pergens.hamacs.config.BlockOverrides.pagesKey(card)
            PillMenu(pages, current, select, onEdit = when {
                !state.editMode -> null
                tabsKey != null -> ({ state.editTarget = fr.pergens.hamacs.ui.EditTarget.Tabs(tabsKey) })
                pagesKey != null -> ({ state.editTarget = fr.pergens.hamacs.ui.EditTarget.Pages(pagesKey) })
                else -> null
            }, onWall = if (tabsKey != null && !state.editMode) ({ state.wall = fr.pergens.hamacs.ui.WallRequest(page = current) }) else null)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/**
 * Menu en pilules : conteneur `border-radius: 18px` + bordure de 4px de la couleur du menu,
 * boutons de 32px de haut arrondis à 18px (donc des pilules parfaites, au centre comme aux extrémités).
 */
@Composable
fun PillMenu(pages: List<Card>, current: String, onSelect: (String) -> Unit, onEdit: (() -> Unit)? = null, onWall: (() -> Unit)? = null) {
    val c = Dash.colors
    val state = fr.pergens.hamacs.ui.LocalDashState.current
    val outer = RoundedCornerShape(Dash.shapes.menuRadius)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                // Barre la plus haute de l'écran : la pastille « en lecture » du lecteur DLNA se place à sa gauche.
                .onGloballyPositioned { lc ->
                    val r = lc.boundsInWindow()
                    val cur = state.topMenu
                    if (cur == null || r.top <= cur.top + 1f) state.topMenu = r
                }
                .clip(outer)
                .background(c.menuBackground, outer)
                .padding(Dash.shapes.menuBorder),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pages.forEach { p ->
                val pid = p.s("id") ?: return@forEach
                val active = pid == current
                val bg by animateColorAsState(
                    if (active) c.menuActiveButtonBackground else c.menuInactiveButtonBackground,
                    tween(TRANSITION_MS), label = "pill",
                )
                val pill = RoundedCornerShape(Dash.shapes.menuRadius)
                Row(
                    Modifier
                        .clip(pill)
                        .background(bg, pill)
                        .tapHold({ onSelect(pid) })
                        .heightIn(min = 32.dp)
                        .padding(start = 14.dp, end = if (active && p.s("label") != null) 18.dp else 14.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HaIcon(p.s("icon"), size = 16.dp, tint = if (active) c.menuActiveButton else c.menuInactiveButton)
                    if (active) p.s("label")?.let {
                        Text(it, Modifier.padding(start = 8.dp), style = TextStyle(fontSize = 14.sp, color = c.menuActiveButton), maxLines = 1)
                    }
                }
            }
            // Onglets de caméras : mur de caméras (toutes celles de l'onglet en plein écran).
            if (onWall != null) {
                val pill = RoundedCornerShape(Dash.shapes.menuRadius)
                Box(
                    Modifier.padding(start = 4.dp).clip(pill).background(c.menuInactiveButtonBackground, pill).tapHold({ onWall() })
                        .heightIn(min = 32.dp).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { HaIcon("mdi:view-grid-outline", size = 16.dp, tint = c.menuInactiveButton) }
            }
            if (onEdit != null) {
                val pill = RoundedCornerShape(Dash.shapes.menuRadius)
                Box(
                    Modifier.padding(start = 4.dp).clip(pill).border(1.dp, c.accent, pill).tapHold({ onEdit() })
                        .heightIn(min = 32.dp).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { HaIcon("mdi:pencil", size = 16.dp, tint = c.accent) }
            }
        }
    }
}

/**
 * Rail vertical (mod-card « position: absolute; margin-left: -32px ») : collé au bord gauche de l'écran,
 * arrondi à droite uniquement, bouton actif sur fond clair.
 */
@Composable
private fun Rail(pages: List<Card>, current: String, onSelect: (String) -> Unit, edgeOffset: Dp, onEdit: (() -> Unit)? = null) {
    val c = Dash.colors
    val r = Dash.shapes.menuRadius
    val border = Dash.shapes.menuBorder
    val outer = RoundedCornerShape(topEnd = r, bottomEnd = r)
    Column(
        Modifier
            .offset(x = -edgeOffset, y = 16.dp)
            .clip(outer)
            .background(c.menuBackground, outer)
            .padding(top = border, bottom = border, end = border),
    ) {
        pages.forEach { p ->
            val pid = p.s("id") ?: return@forEach
            val active = pid == current
            val bg by animateColorAsState(
                if (active) c.menuActiveButtonBackground else c.menuInactiveButtonBackground,
                tween(TRANSITION_MS), label = "rail",
            )
            val shape = RoundedCornerShape(topEnd = r, bottomEnd = r)
            Box(
                Modifier.size(32.dp).clip(shape).background(bg, shape).tapHold({ onSelect(pid) }),
                contentAlignment = Alignment.Center,
            ) { HaIcon(p.s("icon"), size = 16.dp, tint = if (active) c.menuActiveButton else c.menuInactiveButton) }
        }
        if (onEdit != null) {
            val shape = RoundedCornerShape(topEnd = r, bottomEnd = r)
            Box(
                Modifier.padding(top = 4.dp).size(32.dp).clip(shape).border(1.dp, c.accent, shape).tapHold({ onEdit() }),
                contentAlignment = Alignment.Center,
            ) { HaIcon("mdi:pencil", size = 16.dp, tint = c.accent) }
        }
    }
}
