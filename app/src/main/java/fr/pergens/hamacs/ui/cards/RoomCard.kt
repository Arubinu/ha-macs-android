package fr.pergens.hamacs.ui.cards

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.pergens.hamacs.config.Card
import fr.pergens.hamacs.config.d
import fr.pergens.hamacs.config.i
import fr.pergens.hamacs.config.o
import fr.pergens.hamacs.config.objs
import fr.pergens.hamacs.config.s
import fr.pergens.hamacs.ha.Fmt
import fr.pergens.hamacs.ui.LocalDashState
import fr.pergens.hamacs.ui.components.DashCard
import fr.pergens.hamacs.ui.components.HaIcon
import fr.pergens.hamacs.ui.components.editOutline
import fr.pergens.hamacs.ui.components.tapHold
import fr.pergens.hamacs.ui.entity
import fr.pergens.hamacs.ui.theme.Dash
import fr.pergens.hamacs.ui.theme.named

@Composable
fun RoomGrid(card: Card, modifier: Modifier) {
    val gap = card.d("gap")?.dp ?: Dash.shapes.gutter
    val state = LocalDashState.current
    val key = card.s("_key") ?: fr.pergens.hamacs.config.BlockOverrides.roomGridKey(card)
    val editing = state.editMode && key != null
    val rooms = card.objs("rooms")
    // Mode édition : une tuile « Ajouter une pièce » termine la grille.
    val items: List<Card?> = if (editing) rooms + listOf(null) else rooms
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        items.withIndex().toList().rows(card.columns(2)).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { (i, room) ->
                    if (room != null) RoomCard(
                        room, Modifier.weight(1f), defaultHeight = card.i("height"),
                        onEdit = if (editing) ({ state.editTarget = fr.pergens.hamacs.ui.EditTarget.Room(key!!, i) }) else null,
                    ) else AddRoomTile(Modifier.weight(1f).height((card.i("height") ?: 180).dp)) {
                        fr.pergens.hamacs.ui.addRoom(key!!) { index -> state.editTarget = fr.pergens.hamacs.ui.EditTarget.Room(key, index) }
                    }
                }
                repeat(card.columns(2) - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * Carte « pièce » du dashboard : nom, capsules de capteurs, colonne de boutons ronds et
 * grand médaillon d'icône en bas à gauche (cyan quand l'entité principale est allumée).
 */
@Composable
fun RoomCard(card: Card, modifier: Modifier, defaultHeight: Int? = null, onEdit: (() -> Unit)? = null) {
    val state = LocalDashState.current
    val c = Dash.colors
    val main = card.s("entity")
    val mainState = entity(main)
    val on = mainState?.isActive == true
    val medallion by animateColorAsState(
        if (on) c.iconActiveBackground else c.iconInactiveBackground, tween(350), label = "medallion",
    )

    DashCard(
        modifier
            .height((card.i("height") ?: defaultHeight ?: 180).dp)
            .tapHold(
                onTap = { if (onEdit != null) onEdit() else state.perform(card.o("tap_action"), main) },
                onHold = { if (onEdit != null) onEdit() else state.perform(card.o("hold_action"), main, defaultAction = "more-info") },
            )
            .editOutline(state.editMode, RoundedCornerShape(card.d("radius")?.dp ?: Dash.shapes.roundedRadius)),
        background = c.menuBackground,
        radius = card.d("radius")?.dp ?: Dash.shapes.roundedRadius,
    ) {
        // Médaillon (img_cell) : 100dp, débordant de 18dp en bas à gauche.
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .offset(x = (-18).dp, y = 18.dp)
                .size(100.dp)
                .background(medallion, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            HaIcon(card.s("icon"), Modifier.offset(x = 3.dp, y = (-3).dp).alpha(0.6f), size = 60.dp, tint = Color.Black)
        }

        Row(Modifier.padding(14.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    card.s("name") ?: mainState?.name ?: "",
                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Color.White),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                card.objs("info").forEach { InfoCapsule(it) }
            }
            Column(
                Modifier.padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
                horizontalAlignment = Alignment.End,
            ) {
                card.objs("buttons").forEach { RoomButton(it, onEdit = onEdit) }
            }
        }
    }
}

/** Capsule capteur : icône + valeur formatée (`22,0°C`, `40%`…). */
@Composable
private fun InfoCapsule(info: Card) {
    val e = entity(info.s("entity"))
    val attr = info.s("attribute")
    val text = when {
        attr != null -> e?.attrString(attr)?.let { it + (info.s("suffix") ?: "") } ?: "—"
        else -> Fmt.state(e, info.i("decimals"), info.s("suffix"))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(0.7f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HaIcon(info.s("icon"), size = 15.dp)
        Text(text, Modifier.padding(start = 2.dp), style = TextStyle(fontSize = 14.sp, color = Color.White), maxLines = 1)
    }
}

/** Bouton rond 46dp, coloré selon l'état (first/second/third du thème). */
@Composable
fun RoomButton(btn: Card, modifier: Modifier = Modifier, onEdit: (() -> Unit)? = null) {
    val state = LocalDashState.current
    val c = Dash.colors
    val id = btn.s("entity")
    val e = entity(id)
    val active = e?.isActive == true
    val bg by animateColorAsState(
        when {
            btn.s("color_always") != null -> c.named(btn.s("color_always"), c.defaultButtonBackground)
            active -> c.named(btn.s("color"), c.firstButtonBackground)
            else -> c.named(btn.s("off_color"), c.defaultButtonBackground)
        },
        tween(250), label = "btn",
    )
    val icon = when {
        e != null && btn.s("icon_${e.state}") != null -> btn.s("icon_${e.state}")
        active && btn.s("icon_on") != null -> btn.s("icon_on")
        !active && btn.s("icon_off") != null -> btn.s("icon_off")
        else -> btn.s("icon") ?: e?.attrString("icon")
    }
    val size = btn.i("size", 46).dp
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(bg, CircleShape)
            .tapHold(
                onTap = { if (onEdit != null) onEdit() else state.perform(btn.o("tap_action"), id) },
                // Un bouton protégé par code l'est aussi à l'appui long (le détail permet d'allumer/éteindre).
                onHold = {
                    if (onEdit != null) { onEdit(); return@tapHold }
                    state.perform(btn.o("hold_action"), id, defaultAction = "more-info", protectedByConfig = btn.o("tap_action")?.get("pin") != null)
                },
            )
            .editOutline(state.editMode, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        HaIcon(icon, size = (size.value * 26 / 46).dp, tint = c.named(btn.s("icon_color"), Color.White))
    }
}

/** Bouton rond autonome (carte "button"). */
@Composable
fun RoundButton(card: Card, modifier: Modifier) = RoomButton(card, modifier)

/** Tuile du mode édition qui ajoute une pièce à la grille. */
@Composable
private fun AddRoomTile(modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Dash.shapes.roundedRadius)
    Box(
        modifier.editOutline(true, shape).clip(shape).tapHold({ onClick() }),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            HaIcon("mdi:plus-circle-outline", size = 40.dp, tint = Dash.colors.accent)
            Text(
                androidx.compose.ui.res.stringResource(fr.pergens.hamacs.R.string.room_add), Modifier.padding(top = 6.dp),
                style = TextStyle(fontSize = 15.sp, color = Dash.colors.accent, fontWeight = FontWeight.Medium),
            )
        }
    }
}
