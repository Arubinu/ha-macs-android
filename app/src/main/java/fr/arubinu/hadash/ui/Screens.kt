package fr.arubinu.hadash.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import fr.arubinu.hadash.App
import fr.arubinu.hadash.R
import fr.arubinu.hadash.config.Card
import fr.arubinu.hadash.config.b
import fr.arubinu.hadash.config.d
import fr.arubinu.hadash.config.o
import fr.arubinu.hadash.config.objs
import fr.arubinu.hadash.config.s
import fr.arubinu.hadash.ui.cards.RenderCard
import fr.arubinu.hadash.ui.components.tapHold
import fr.arubinu.hadash.ui.theme.Dash
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/** Affiche l'écran courant (screens.<id> de dashboard.json) avec un fondu. */
@Composable
fun ScreenHost(dashboard: JsonObject) {
    val state = LocalDashState.current
    val screens = dashboard.o("screens") ?: JsonObject(emptyMap())
    val settings by App.instance.config.settings.collectAsState()
    val lastMotion by App.instance.presence.lastMotion.collectAsState()

    // Retour à l'accueil après inactivité : ni contact, ni mouvement devant la caméra de la tablette.
    val idleScreen = dashboard.s("idle_screen") ?: dashboard.s("start_screen") ?: "home"
    val idleSeconds = settings.idleSeconds
    val lastActivity = maxOf(state.lastInteraction, if (settings.presenceDetection) lastMotion else 0L)
    LaunchedEffect(state.screen, lastActivity, idleScreen, idleSeconds, state.editMode) {
        if (idleSeconds > 0 && state.screen != idleScreen && !state.editMode) {
            delay((lastActivity + idleSeconds * 1000L - System.currentTimeMillis()).coerceAtLeast(0))
            if (state.popup == null && state.player == null && state.pin == null) state.screen = idleScreen
        }
    }

    Crossfade(targetState = state.screen, animationSpec = tween(300), label = "screen") { id ->
        val screen = screens.o(id) ?: screens.entries.firstOrNull()?.value as? JsonObject
        Box(Modifier.fillMaxSize().background(Dash.colors.background)) {
            if (screen == null) {
                Text(stringResource(R.string.no_screen, id), Modifier.align(Alignment.Center), color = Color.White)
            } else {
                screen.s("background_image")?.let { bg ->
                    AsyncImage(state.ha.resolveUrl(bg), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                when (screen.s("layout", "columns")) {
                    "overlay" -> OverlayScreen(screen)
                    else -> ColumnsScreen(screen)
                }
            }
        }
    }
}

/**
 * Disposition en colonnes (vue « sections »). Chaque colonne défile indépendamment ; une colonne
 * `"fit": true` répartit l'espace entre ses blocs pour occuper exactement la hauteur de l'écran
 * (et ne défile que si son contenu dépasse).
 */
@Composable
private fun ColumnsScreen(screen: Card) {
    val pad = screen.d("padding", 32.0).dp
    val padTop = screen.d("padding_top", 24.0).dp
    val gap = screen.d("column_gap", 32.0).dp
    val columns = screen.objs("columns")
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
        columns.forEachIndexed { index, col ->
            // Les marges latérales sont à l'intérieur de la zone de défilement : le rail de la première
            // colonne peut ainsi déborder jusqu'au bord de l'écran sans être rogné.
            val start = if (index == 0) pad else 0.dp
            val end = if (index == columns.lastIndex) pad else 0.dp
            BoxWithConstraints(Modifier.weight(col.d("weight", 1.0).toFloat()).fillMaxHeight()) {
                val minGap = col.d("gap")?.dp ?: Dash.shapes.gutter
                val cards = col.objs("cards")
                val scroll = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = start, end = end, top = padTop, bottom = padTop)
                if (col.b("fit")) {
                    FitColumn(
                        available = maxHeight - padTop * 2,
                        minGap = minGap,
                        flex = cards.map { it.b("flex") },
                        minFlex = cards.map { (it.d("min_height") ?: 160.0).dp },
                        spaceAfter = cards.map { it.d("space_after")?.dp },
                        modifier = scroll,
                    ) { cards.forEach { RenderCard(it) } }
                } else {
                    Column(scroll, verticalArrangement = Arrangement.spacedBy(minGap)) {
                        cards.forEach { RenderCard(it) }
                    }
                }
            }
        }
    }
}

/**
 * Colonne ajustée à la hauteur de l'écran :
 * - les cartes `"flex": true` (ex. M.A.C.S.) prennent l'espace restant, sans descendre sous `min_height` ;
 * - sans carte flexible, l'espace libre est réparti entre les blocs ;
 * - si le contenu dépasse malgré tout, la colonne défile (écart minimal conservé).
 */
@Composable
private fun FitColumn(
    available: Dp,
    minGap: Dp,
    flex: List<Boolean>,
    minFlex: List<Dp>,
    spaceAfter: List<Dp?>,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val loose = Constraints(minWidth = 0, maxWidth = width)
        val availablePx = available.roundToPx()
        // Écart après chaque bloc : `space_after` du bloc, sinon l'écart minimal de la colonne.
        val gapAfter = List(measurables.size) { i ->
            if (i == measurables.lastIndex) 0 else (spaceAfter.getOrNull(i) ?: minGap).roundToPx()
        }
        val fixed = measurables.mapIndexed { i, m -> if (flex.getOrElse(i) { false }) null else m.measure(loose) }
        val fixedHeight = fixed.sumOf { it?.height ?: 0 }
        val gaps = gapAfter.sum()
        val flexCount = flex.take(measurables.size).count { it }
        val leftover = availablePx - fixedHeight - gaps
        val placeables = measurables.mapIndexed { i, m ->
            fixed[i] ?: run {
                val h = maxOf(leftover / flexCount.coerceAtLeast(1), minFlex.getOrElse(i) { 160.dp }.roundToPx())
                m.measure(Constraints.fixed(width, h))
            }
        }
        val total = placeables.sumOf { it.height } + gaps
        // Sans carte flexible : l'espace libre est réparti entre les blocs (comme SpaceBetween).
        val extra = if (flexCount == 0 && total < availablePx && placeables.size > 1) (availablePx - total) / (placeables.size - 1) else 0
        val height = maxOf(total + extra * (placeables.size - 1).coerceAtLeast(0), minOf(availablePx, total))
        layout(width, height) {
            var y = 0
            placeables.forEachIndexed { i, p ->
                p.placeRelative(0, y)
                y += p.height + gapAfter[i] + extra
            }
        }
    }
}

/** Cartes superposées et positionnées (écran d'accueil M.A.C.S.). */
@Composable
private fun OverlayScreen(screen: Card) {
    Box(Modifier.fillMaxSize()) {
        screen.objs("cards").forEach { card -> Positioned(card) }
    }
}

@Composable
private fun BoxScope.Positioned(source: Card) {
    val state = LocalDashState.current
    // Carte réglée dans son éditeur (AlertTicker) : le placement enregistré sur la tablette prime.
    val blocks by App.instance.config.blocks.collectAsState()
    val card = if (source.s("type") in fr.arubinu.hadash.ui.cards.alerts.AlertHub.TYPES)
        blocks[fr.arubinu.hadash.ui.cards.alerts.AlertHub.cardKey(source)]?.o("config") ?: source
    else source
    val align = when (card.s("position", "fill")) {
        "top_start" -> Alignment.TopStart
        "top_center" -> Alignment.TopCenter
        "top_end" -> Alignment.TopEnd
        "center" -> Alignment.Center
        "bottom_start" -> Alignment.BottomStart
        "bottom_center" -> Alignment.BottomCenter
        "bottom_end" -> Alignment.BottomEnd
        else -> null
    }
    var m: Modifier = if (align == null) Modifier.fillMaxSize() else Modifier.align(align)
    // Marge à l'extérieur : `max_width` est la largeur réelle du bloc, comme dans l'aperçu de l'éditeur.
    card.d("margin")?.let { m = m.padding(it.dp) }
    card.d("max_width")?.let { m = m.widthIn(max = it.dp) }
    if (card.s("type") == "image") {
        // Largeur imposée, hauteur déduite des proportions réelles de l'image (pas de déformation).
        val width = card.d("width")
        val size = card.d("size")
        // Image introuvable (ex. image personnelle absente de cette installation) : bouton texte `name` / `icon`.
        var failed by remember(card.s("image")) { mutableStateOf(false) }
        if (failed && card.s("name") != null) {
            Row(
                m.clip(RoundedCornerShape(50)).border(1.dp, Dash.colors.accent.copy(alpha = 0.6f), RoundedCornerShape(50))
                    .tapHold({ state.perform(card.o("tap_action"), null, "none") })
                    .padding(horizontal = 28.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                card.s("icon")?.let { fr.arubinu.hadash.ui.components.HaIcon(it, size = 28.dp, tint = Dash.colors.accent) }
                Text(card.s("name")!!, Modifier.padding(start = 12.dp), style = TextStyle(fontSize = 22.sp, color = Color.White, letterSpacing = 2.sp))
            }
            return
        }
        AsyncImage(
            state.ha.resolveUrl(card.s("image")), null,
            when {
                width != null -> m.width(width.dp)
                size != null -> m.size(size.dp)
                else -> m
            }.tapHold({ state.perform(card.o("tap_action"), null, "none") }),
            contentScale = ContentScale.FillWidth,
            onError = { failed = true },
            // `accent_hue` : image pensée pour l'accent d'origine, recolorée avec la couleur principale.
            colorFilter = if (card.b("accent_hue")) {
                val accent = Dash.colors.accent
                remember(accent) { fr.arubinu.hadash.ui.theme.accentImageFilter(accent) }
            } else null,
        )
    } else {
        Box(m) { RenderCard(source, if (align == null) Modifier.fillMaxSize() else Modifier) }
    }
}
