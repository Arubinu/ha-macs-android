package fr.pergens.hamacs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.pergens.hamacs.App
import fr.pergens.hamacs.R
import fr.pergens.hamacs.config.BlockOverrides
import fr.pergens.hamacs.config.s
import fr.pergens.hamacs.ui.cards.mgcColor
import fr.pergens.hamacs.ui.components.HaIcon
import fr.pergens.hamacs.ui.theme.Dash
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val Muted = Color(255, 255, 255, 150)
private val RowBg = Color(255, 255, 255, 10)

private fun JsonObject.flag(k: String, def: Boolean) = (this[k] as? JsonPrimitive)?.content?.let { it != "false" } ?: def
private fun JsonObject.with(k: String, v: JsonElement?) = JsonObject(if (v == null) this - k else this + (k to v))

/** Rôle d'une entité du graphique, pour la ranger dans la bonne section de l'éditeur. */
private enum class Role { Header, Curve, Band }

private fun roleOf(o: JsonObject, index: Int) = when {
    o.s("y_axis") == "secondary" -> Role.Band
    index == 0 && !o.flag("show_graph", true) -> Role.Header
    else -> Role.Curve
}

/**
 * Éditeur d'un graphique multi-courbes [key] (`mgc:…`), propre à cette carte : titre, valeur affichée en grand,
 * courbes (nom affiché, entité, couleur de la carte d'origine) et bande jour/nuit. Les changements sont
 * appliqués immédiatement ; « Rétablir » revient à dashboard.json.
 */
@Composable
fun MultiGraphFields(key: String) {
    val app = App.instance
    val source by app.config.sourceDashboard.collectAsState()
    val raw by app.config.rawBlocks.collectAsState()
    val card = remember(source, key) { BlockOverrides.find(source, key, "mini_graph") } ?: return
    val set = raw[key]?.get("set") as? JsonObject
    val originalEntities = card["entities"] as? JsonArray ?: return
    val entities = ((set?.get("entities") as? JsonArray) ?: originalEntities).map { e ->
        e as? JsonObject ?: JsonObject(mapOf("entity" to e))
    }
    val effective = JsonObject(card + (set ?: JsonObject(emptyMap())))
    val accent = Dash.colors.accent

    fun saveEntities(list: List<JsonObject>) {
        val arr = JsonArray(list)
        app.config.setBlockField(key, "entities", arr.takeIf { it != originalEntities })
    }
    fun update(i: Int, o: JsonObject) = saveEntities(entities.mapIndexed { j, e -> if (j == i) o else e })

    // Choix d'entité en cours : (index à remplacer, ou -1 = nouvelle courbe / -2 = nouvelle bande), domaine proposé.
    var picking by remember { mutableStateOf<Pair<Int, String?>?>(null) }

    // --- Titre ---------------------------------------------------------------------------------------------
    SectionTitle(stringResource(R.string.graph_title))
    Box(Modifier.fillMaxWidth().padding(start = 14.dp)) {
        DebouncedField(
            value = effective.s("name") ?: "",
            label = stringResource(R.string.graph_title_hint),
        ) { text -> app.config.setBlockField(key, "name", JsonPrimitive(text).takeIf { text != (card.s("name") ?: "") }) }
    }

    // --- Valeur affichée en grand --------------------------------------------------------------------------
    val headerIndex = entities.indices.firstOrNull { roleOf(entities[it], it) == Role.Header }
    SectionTitle(stringResource(R.string.graph_header_value))
    if (headerIndex != null) {
        Box(Modifier.padding(start = 14.dp)) {
            EntityChoice(entities[headerIndex].s("entity") ?: "") { picking = headerIndex to "sensor" }
        }
    } else {
        // Valeur de la 1re courbe, ou la plus basse / la plus haute des courbes (ex. meilleur prix), avec son nom.
        val from = effective.s("state_from")?.takeIf { it == "min" || it == "max" } ?: ""
        Row(Modifier.padding(start = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("" to R.string.graph_header_first_curve, "min" to R.string.graph_header_min, "max" to R.string.graph_header_max).forEach { (v, label) ->
                val sel = v == from
                val shape = RoundedCornerShape(50)
                Text(
                    stringResource(label),
                    Modifier.clip(shape).background(if (sel) accent else RowBg, shape).border(1.dp, if (sel) accent else Color.White.copy(alpha = 0.12f), shape)
                        .clickable { app.config.setBlockField(key, "state_from", JsonPrimitive(v).takeIf { v != (card.s("state_from") ?: "") }) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    style = TextStyle(fontSize = 14.sp, color = if (sel) Color.Black else Color.White),
                )
            }
        }
        Text(stringResource(R.string.graph_header_help), Modifier.padding(start = 14.dp, top = 4.dp), style = TextStyle(fontSize = 12.sp, color = Muted))
    }

    // --- Courbes -------------------------------------------------------------------------------------------
    SectionTitle(stringResource(R.string.graph_curves))
    Column(Modifier.fillMaxWidth().padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entities.forEachIndexed { i, e ->
            if (roleOf(e, i) != Role.Curve) return@forEachIndexed
            CurveRow(
                color = mgcColor(effective, e, i, accent),
                name = e.s("name") ?: "",
                entityId = e.s("entity") ?: "",
                onName = { n -> update(i, e.with("name", JsonPrimitive(n).takeIf { n.isNotBlank() })) },
                onPick = { picking = i to (e.s("entity")?.substringBefore('.') ?: "sensor") },
                onDelete = { saveEntities(entities.filterIndexed { j, _ -> j != i }) },
            )
        }
        AddButton(stringResource(R.string.graph_add_curve)) {
            picking = -1 to (entities.firstOrNull { roleOf(it, entities.indexOf(it)) == Role.Curve }?.s("entity")?.substringBefore('.') ?: "sensor")
        }
    }

    // --- Bande jour / nuit -------------------------------------------------------------------------------
    SectionTitle(stringResource(R.string.graph_band))
    val bandIndex = entities.indices.firstOrNull { roleOf(entities[it], it) == Role.Band }
    Column(Modifier.fillMaxWidth().padding(start = 14.dp)) {
        if (bandIndex != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { EntityChoice(entities[bandIndex].s("entity") ?: "") { picking = bandIndex to null } }
                IconButton("mdi:delete-outline") { saveEntities(entities.filterIndexed { j, _ -> j != bandIndex }) }
            }
            Text(stringResource(R.string.graph_band_help), style = TextStyle(fontSize = 12.sp, color = Muted))
        } else AddButton(stringResource(R.string.graph_add_band)) {
            // Jour / nuit : le soleil de Home Assistant (sun.sun), sans capteur à créer ; modifiable ensuite.
            saveEntities(entities + JsonObject(mapOf(
                "entity" to JsonPrimitive("sun.sun"), "name" to JsonPrimitive(app.getString(R.string.graph_band_day)), "color" to JsonPrimitive("#9FA4B0"),
                "y_axis" to JsonPrimitive("secondary"), "smoothing" to JsonPrimitive(false), "show_state" to JsonPrimitive(false),
                "show_line" to JsonPrimitive(false), "show_points" to JsonPrimitive(false), "show_legend" to JsonPrimitive(false),
            )))
        }
    }

    // --- Retour à la carte d'origine ------------------------------------------------------------------------
    if (set != null && set.isNotEmpty()) Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.CenterEnd) {
        TextLink(stringResource(R.string.graph_reset)) {
            app.config.setBlockField(key, "entities", null)
            app.config.setBlockField(key, "name", null)
            app.config.setBlockField(key, "state_from", null)
        }
    }

    picking?.let { (index, domain) ->
        EntityPicker(domain, onDismiss = { picking = null }) { id ->
            picking = null
            val friendly = app.ha.states.value[id]?.name
            when (index) {
                // Nouvelle courbe : ajoutée avant la bande jour/nuit, avec le nom de l'entité.
                -1 -> {
                    val curve = JsonObject(mapOf("entity" to JsonPrimitive(id), "name" to JsonPrimitive(friendly ?: id), "show_legend" to JsonPrimitive(true)))
                    val at = entities.indexOfFirst { roleOf(it, entities.indexOf(it)) == Role.Band }.takeIf { it >= 0 } ?: entities.size
                    saveEntities(entities.toMutableList().apply { add(at, curve) })
                }
                // Nouvelle bande jour/nuit : axe secondaire, remplissage gris sans courbe (comme la carte d'origine).
                -2 -> saveEntities(entities + JsonObject(mapOf(
                    "entity" to JsonPrimitive(id), "name" to JsonPrimitive(friendly ?: id), "color" to JsonPrimitive("#9FA4B0"),
                    "y_axis" to JsonPrimitive("secondary"), "smoothing" to JsonPrimitive(false), "show_state" to JsonPrimitive(false),
                    "show_line" to JsonPrimitive(false), "show_points" to JsonPrimitive(false), "show_legend" to JsonPrimitive(false),
                )))
                else -> update(index, entities[index].with("entity", JsonPrimitive(id)))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(top = 10.dp, bottom = 2.dp), style = TextStyle(fontSize = 13.sp, color = Dash.colors.accent, fontWeight = FontWeight.SemiBold))
}

/** Champ texte enregistré automatiquement une fraction de seconde après la dernière frappe. */
@Composable
private fun DebouncedField(value: String, label: String, onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    LaunchedEffect(text) {
        if (text == value) return@LaunchedEffect
        delay(600)
        onSave(text.trim())
    }
    OutlinedTextField(
        text, { text = it }, Modifier.fillMaxWidth().padding(start = 14.dp), label = { Text(label) }, singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Dash.colors.accent, cursorColor = Dash.colors.accent),
    )
}

/** Entité choisie (nom et identifiant) ; un toucher ouvre la liste des entités. */
@Composable
private fun EntityChoice(id: String, onClick: () -> Unit) {
    EntityCard(id, onClick = onClick) { EntityCardAction("mdi:swap-horizontal", tint = Dash.colors.accent, onClick = onClick) }
}

/**
 * Courbe : pastille de sa couleur, nom affiché (modifiable sur place), entité (toucher pour la changer),
 * suppression.
 */
@Composable
private fun CurveRow(color: Color, name: String, entityId: String, onName: (String) -> Unit, onPick: () -> Unit, onDelete: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    var text by remember(name) { mutableStateOf(name) }
    LaunchedEffect(text) {
        if (text == name) return@LaunchedEffect
        delay(600)
        onName(text.trim())
    }
    Row(
        Modifier.fillMaxWidth().clip(shape).background(RowBg, shape).border(1.dp, color.copy(alpha = 0.35f), shape)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(color))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            // Nom affiché dans la légende, modifiable directement.
            BasicTextField(
                text, { text = it }, singleLine = true,
                textStyle = TextStyle(fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Medium),
                cursorBrush = SolidColor(Dash.colors.accent),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) Text(stringResource(R.string.graph_curve_name), style = TextStyle(fontSize = 16.sp, color = Muted))
                        inner()
                    }
                },
            )
            Box(Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onPick)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f, fill = false)) { EntityName(entityId) }
                    HaIcon("mdi:swap-horizontal", Modifier.padding(start = 6.dp), 16.dp, Dash.colors.accent)
                }
            }
        }
        IconButton("mdi:delete-outline", onDelete)
    }
}

@Composable
private fun IconButton(icon: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        HaIcon(icon, size = 22.dp, tint = Muted)
    }
}
