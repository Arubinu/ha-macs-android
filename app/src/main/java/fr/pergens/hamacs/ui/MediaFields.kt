package fr.pergens.hamacs.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.pergens.hamacs.App
import fr.pergens.hamacs.R
import fr.pergens.hamacs.config.s
import fr.pergens.hamacs.config.strs
import fr.pergens.hamacs.ui.components.HaIcon
import fr.pergens.hamacs.ui.components.tapHold
import fr.pergens.hamacs.ui.theme.Dash
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Réglages d'un lecteur multimédia (mode édition) : autres lecteurs accessibles par pastilles, couleurs de la pochette,
 * boutons de volume, bouton marche / arrêt, bouton favori (Music Assistant).
 */
@Composable
internal fun MediaFields(objKey: String, cfg: JsonObject) {
    val config = App.instance.config
    fun set(field: String, v: kotlinx.serialization.json.JsonElement?) = config.setBlockField(objKey, field, v)
    fun flag(k: String, def: Boolean) = (cfg[k] as? JsonPrimitive)?.content?.let { it == "true" } ?: def
    var pick by remember { mutableStateOf<String?>(null) }
    val players = cfg.strs("players")

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Autres lecteurs : liste « players » des entités ci-dessus (ajout, remplacement, retrait).
        if (players.isEmpty()) Text(
            "+  " + stringResource(R.string.media_add_player),
            Modifier.clip(RoundedCornerShape(12.dp)).tapHold({ pick = "player" }).padding(vertical = 10.dp, horizontal = 4.dp),
            style = TextStyle(fontSize = 15.sp, color = Dash.colors.accent, fontWeight = FontWeight.Medium),
        )
        EditSwitch(stringResource(R.string.media_art_colors), stringResource(R.string.media_art_colors_help), flag("use_art_colors", true)) {
            set("use_art_colors", if (it) null else JsonPrimitive(false))
        }
        EditSwitch(stringResource(R.string.media_volume_steps), stringResource(R.string.media_volume_steps_help), flag("show_volume_step_buttons", false)) {
            set("show_volume_step_buttons", if (it) JsonPrimitive(true) else null)
        }
        EditSwitch(stringResource(R.string.media_power_always), stringResource(R.string.media_power_always_help), flag("always_show_power_button", false)) {
            set("always_show_power_button", if (it) JsonPrimitive(true) else null)
        }
        // Bouton favori : à choisir ici ; ensuite, la liste des entités ci-dessus le remplace ou le retire.
        if (cfg.s("favorite_button") == null) Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).tapHold({ pick = "button" }).heightIn(min = 64.dp).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.media_favorite_button), style = TextStyle(fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Medium))
                Text(stringResource(R.string.media_favorite_button_help), style = TextStyle(fontSize = 13.sp, color = Color(255, 255, 255, 150)))
            }
            Text(stringResource(R.string.media_none), Modifier.padding(horizontal = 8.dp), style = TextStyle(fontSize = 14.sp, color = Dash.colors.accent))
        }
    }
    when (pick) {
        "player" -> EntityPicker("media_player", { pick = null }) { id ->
            if (id != cfg.s("entity") && id !in players) set("players", JsonArray((players + id).map { JsonPrimitive(it) }))
            pick = null
        }
        "button" -> EntityPicker("button", { pick = null }) { id -> set("favorite_button", JsonPrimitive(id)); pick = null }
    }
}
