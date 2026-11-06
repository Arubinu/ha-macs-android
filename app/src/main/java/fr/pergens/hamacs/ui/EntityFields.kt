package fr.pergens.hamacs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.pergens.hamacs.App
import fr.pergens.hamacs.R
import fr.pergens.hamacs.config.BlockOverrides
import fr.pergens.hamacs.config.s
import fr.pergens.hamacs.ui.components.HaIcon
import fr.pergens.hamacs.ui.theme.Dash
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val Muted = Color(255, 255, 255, 150)

/** Libellé d'un champ de configuration désignant une entité. */
@Composable
private fun fieldLabel(field: String): String = when (field) {
    "entity" -> stringResource(R.string.field_entity)
    "person" -> stringResource(R.string.field_person)
    "battery" -> stringResource(R.string.field_battery)
    "motion" -> stringResource(R.string.field_motion)
    "media_player" -> stringResource(R.string.field_media_player)
    "home_sensor" -> stringResource(R.string.field_home_sensor)
    "steps_sensor" -> stringResource(R.string.field_steps_sensor)
    "battery_sensor" -> stringResource(R.string.field_battery_sensor)
    "charger_sensor" -> stringResource(R.string.field_charger_sensor)
    "calendars" -> stringResource(R.string.field_calendars)
    "fullscreen_entity" -> stringResource(R.string.field_fullscreen_entity)
    else -> field
}

private fun entityValue(v: JsonElement?): String? = (v as? JsonPrimitive)?.content?.takeIf { BlockOverrides.isEntityId(it) }
private fun entityList(v: JsonElement?): List<String>? =
    (v as? JsonArray)?.takeIf { a -> a.isNotEmpty() && a.all { entityValue(it) != null } }?.map { (it as JsonPrimitive).content }

/** Entité en cours de choix : objet [key], champ [field], position dans une liste ([index] = -1 : ajout). */
private data class Pick(val key: String, val field: String, val original: JsonElement, val index: Int?, val domain: String?)

/**
 * Entités d'un bloc modifiables en mode édition : chaque champ de l'objet de configuration [key] qui
 * désigne une entité (ou une liste d'entités), plus les informations secondaires (`info`) d'une pièce.
 */
@Composable
fun EntityFields(key: String, type: String? = null) {
    val app = App.instance
    val source by app.config.sourceDashboard.collectAsState()
    val raw by app.config.rawBlocks.collectAsState()
    val obj = remember(source, key, type) {
        BlockOverrides.find(source, key, type) ?: type?.let { BlockOverrides.findWithEntity(source, key, it) }
    } ?: return
    var picking by remember { mutableStateOf<Pick?>(null) }

    // Lignes (objet, champ, valeur d'origine) : l'objet lui-même puis ses informations secondaires.
    val rows = buildList {
        obj.forEach { (field, v) -> if (entityValue(v) != null || entityList(v) != null) add(Triple(key, field, v)) }
        // Caméra : batterie et capteurs de mouvement proposés même s'ils ne sont pas encore renseignés.
        if (obj.s("type") == "camera") {
            if (obj["fullscreen_entity"] == null) add(Triple(key, "fullscreen_entity", kotlinx.serialization.json.JsonNull))
            if (obj["battery"] == null) add(Triple(key, "battery", kotlinx.serialization.json.JsonNull))
            if (obj["motion"] == null) add(Triple(key, "motion", JsonArray(emptyList())))
        }
        (obj["info"] as? JsonArray)?.forEach { item ->
            val o = item as? JsonObject ?: return@forEach
            val k = BlockOverrides.keyOf(o) ?: return@forEach
            add(Triple(k, "info", o["entity"]!!))
        }
        // Courbes d'un graphique multi-entités : une ligne par entité, avec son nom.
        (obj["entities"] as? JsonArray)?.forEach { item ->
            val o = item as? JsonObject ?: return@forEach
            val k = BlockOverrides.keyOf(o) ?: return@forEach
            add(Triple(k, "sub:" + (o.s("name") ?: k), o["entity"]!!))
        }
    }
    if (rows.isEmpty()) return

    // Une seule entité : le titre de section est directement son rôle (« Entité »…), sans sous-titre répété.
    val solo = rows.size == 1
    val soloLabel = rows.singleOrNull()?.second?.let { f -> if (f.startsWith("sub:")) f.removePrefix("sub:") else if (f == "info") null else fieldLabel(f) }
    Text(
        if (solo && soloLabel != null) soloLabel else stringResource(R.string.edit_entities), Modifier.padding(top = 6.dp),
        style = TextStyle(fontSize = 13.sp, color = Dash.colors.accent, fontWeight = FontWeight.SemiBold),
    )
    var infoIndex = 0
    // Contenu de la section légèrement en retrait sous son titre.
    Column(Modifier.fillMaxWidth().padding(start = 14.dp)) {
    rows.forEach { (k, field, original) ->
        val set = raw[k]?.get("set") as? JsonObject
        val storeField = if (field == "info" || field.startsWith("sub:")) "entity" else field
        val current = set?.get(storeField) ?: original
        val label = when {
            field == "info" -> stringResource(R.string.edit_entity_info, ++infoIndex)
            field.startsWith("sub:") -> field.removePrefix("sub:")
            // Caméra : flux affiché dans la carte (léger) et flux du plein écran (le plus défini).
            field == "entity" && k == key && obj.s("type") == "camera" -> stringResource(R.string.field_preview_entity)
            else -> fieldLabel(field)
        }
        val overridden = set?.get(storeField) != null
        // Champ simple encore vide (JsonNull) : ligne « — » à toucher pour choisir l'entité.
        val single = entityValue(current) ?: if (current is kotlinx.serialization.json.JsonNull) "—" else null
        if (single != null) {
            EntityRow(label.takeUnless { solo && soloLabel != null }, single, overridden, onReset = { app.config.setBlockField(k, storeField, null) }) {
                picking = Pick(k, storeField, original, null, entityValue(original)?.substringBefore('.') ?: when (storeField) { "battery" -> "sensor"; "fullscreen_entity" -> "camera"; else -> null })
            }
        } else {
            val list = entityList(current) ?: entityList(original) ?: emptyList()
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (solo && soloLabel != null) "" else label, Modifier.weight(1f), style = TextStyle(fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Medium))
                    if (overridden) TextLink(stringResource(R.string.edit_entity_reset)) { app.config.setBlockField(k, storeField, null) }
                }
                val domain = list.firstOrNull()?.substringBefore('.') ?: if (storeField == "motion") "binary_sensor" else null
                list.forEachIndexed { i, id ->
                    EntityCard(id, Modifier.padding(top = 6.dp), onClick = { picking = Pick(k, storeField, original, i, domain) }) {
                        EntityCardAction("mdi:swap-horizontal", tint = Dash.colors.accent) { picking = Pick(k, storeField, original, i, domain) }
                        EntityCardAction("mdi:delete-outline") {
                            val newList = list.filterIndexed { j, _ -> j != i }
                            app.config.setBlockField(k, storeField, JsonArray(newList.map { JsonPrimitive(it) }).takeIf { it != original })
                        }
                    }
                }
                AddButton(stringResource(R.string.edit_entity_add)) { picking = Pick(k, storeField, original, -1, domain) }
            }
        }
    }
    }

    picking?.let { p ->
        EntityPicker(p.domain, onDismiss = { picking = null }) { chosen ->
            picking = null
            val set = raw[p.key]?.get("set") as? JsonObject
            val value: JsonElement = if (p.index == null) JsonPrimitive(chosen) else {
                val list = (entityList(set?.get(p.field)) ?: entityList(p.original) ?: emptyList()).toMutableList()
                if (p.index < 0 || p.index >= list.size) list += chosen else list[p.index] = chosen
                JsonArray(list.distinct().map { JsonPrimitive(it) })
            }
            // Valeur identique à dashboard.json : la surcharge est retirée.
            app.config.setBlockField(p.key, p.field, value.takeIf { it != p.original })
        }
    }
}

/** Bouton « Ajouter » : toute la largeur, contour de la couleur d'accent. */
@Composable
internal fun AddButton(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.padding(top = 6.dp).fillMaxWidth().clip(shape).border(1.5.dp, Dash.colors.accent, shape)
            .clickable(onClick = onClick).heightIn(min = 44.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        HaIcon("mdi:plus", size = 20.dp, tint = Dash.colors.accent)
        Text(text, Modifier.padding(start = 6.dp), style = TextStyle(fontSize = 15.sp, color = Dash.colors.accent, fontWeight = FontWeight.Medium))
    }
}

@Composable
internal fun TextLink(text: String, onClick: () -> Unit) {
    Text(
        text, Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        style = TextStyle(fontSize = 14.sp, color = Dash.colors.accent, fontWeight = FontWeight.Medium),
    )
}

/** Nom de l'entité et, en italique, son identifiant. */
@Composable
internal fun EntityName(id: String, modifier: Modifier = Modifier) {
    val e = entity(id)
    Column(modifier) {
        Text(e?.name ?: id, style = TextStyle(fontSize = 14.sp, color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(id, style = TextStyle(fontSize = 12.sp, color = Muted, fontStyle = FontStyle.Italic), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EntityRow(label: String?, id: String, overridden: Boolean, onReset: () -> Unit, onClick: () -> Unit) {
    // Rôle de l'entité au-dessus, puis la carte de l'entité (icône, nom, identifiant, valeur, changer).
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (label != null || overridden) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label ?: "", Modifier.weight(1f).padding(bottom = 4.dp), style = TextStyle(fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Medium))
            if (overridden) TextLink(stringResource(R.string.edit_entity_reset), onReset)
        }
        EntityCard(id, onClick = onClick) {
            EntityCardAction("mdi:swap-horizontal", tint = Dash.colors.accent, onClick = onClick)
        }
    }
}

/** Liste des entités de Home Assistant, filtrée par type (domaine) et par recherche. */
@Composable
internal fun EntityPicker(domain: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val states by App.instance.ha.states.collectAsState()
    var query by remember { mutableStateOf("") }
    var allDomains by remember { mutableStateOf(domain == null) }
    val list = remember(states, query, allDomains) {
        val q = query.trim().lowercase()
        states.values
            .filter { allDomains || it.entityId.substringBefore('.') == domain }
            .filter { q.isEmpty() || it.entityId.lowercase().contains(q) || it.name.lowercase().contains(q) }
            .sortedBy { it.name.lowercase() }
    }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier.fillMaxWidth(0.5f).fillMaxHeight(0.85f).clip(shape).background(Color(0xF2222222), shape).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.edit_entity_pick), Modifier.weight(1f), style = TextStyle(fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Medium))
                Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                    HaIcon("mdi:close", size = 24.dp, tint = Color(255, 255, 255, 200))
                }
            }
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text(stringResource(R.string.edit_entity_search)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = Dash.colors.accent, cursorColor = Dash.colors.accent,
                ),
            )
            if (domain != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.edit_entity_all_domains), Modifier.weight(1f), style = TextStyle(fontSize = 14.sp, color = Color.White))
                Switch(allDomains, { allDomains = it }, colors = SwitchDefaults.colors(checkedTrackColor = Dash.colors.accent, checkedThumbColor = Color.Black))
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(list, key = { it.entityId }) { e ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(e.entityId) }.padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Icône de l'entité, comme dans les fenêtres de réglage.
                        Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
                            HaIcon(entityIcon(e.entityId, e), size = 19.dp, tint = Color.White.copy(alpha = 0.8f))
                        }
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(e.name, style = TextStyle(fontSize = 15.sp, color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(e.entityId, style = TextStyle(fontSize = 12.sp, color = Muted, fontStyle = FontStyle.Italic), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(fr.pergens.hamacs.ha.Fmt.state(e), style = TextStyle(fontSize = 13.sp, color = Muted), maxLines = 1)
                    }
                }
            }
        }
    }
}
