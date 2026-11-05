package fr.arubinu.hadash.ui

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
import fr.arubinu.hadash.App
import fr.arubinu.hadash.R
import fr.arubinu.hadash.config.BlockOverrides
import fr.arubinu.hadash.config.objs
import fr.arubinu.hadash.config.s
import fr.arubinu.hadash.ui.components.HaIcon
import fr.arubinu.hadash.ui.theme.Dash
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val Muted = Color(255, 255, 255, 150)

/**
 * Onglets d'un sélecteur de pages ([key] = `pages:<id>`) : icône et nom de chaque onglet, modifiables sur place.
 * « Rétablir » revient aux onglets d'origine (pages masquées par l'assistant comprises).
 */
@Composable
fun PagesFields(key: String) {
    val app = App.instance
    val rawDash by app.config.rawDashboard.collectAsState()
    val source by app.config.sourceDashboard.collectAsState()
    val original = remember(rawDash, key) { BlockOverrides.find(rawDash, key) } ?: return
    val current = BlockOverrides.find(source, key) ?: original
    val pages = current.objs("pages")
    val favorite = (app.config.rawBlocks.collectAsState().value[key]?.get("set") as? JsonObject)?.s("favorite") ?: current.s("favorite")
    fun save(list: List<JsonObject>) {
        val arr = JsonArray(list.map { JsonObject(it - "_key") })
        app.config.setBlockField(key, "pages", arr.takeIf { it != original["pages"] })
    }
    fun setPage(i: Int, k: String, v: String?) = save(pages.mapIndexed { j, p -> if (j == i) JsonObject(if (v == null) p - k else p + (k to JsonPrimitive(v))) else p })
    var iconFor by remember { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        pages.forEachIndexed { i, page ->
            val shape = RoundedCornerShape(14.dp)
            Row(
                Modifier.fillMaxWidth().clip(shape).background(Color(255, 255, 255, 10), shape).border(1.dp, Color.White.copy(alpha = 0.08f), shape)
                    .padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Dash.colors.menuActiveButtonBackground).clickable { iconFor = i },
                    contentAlignment = Alignment.Center,
                ) { HaIcon(page.s("icon") ?: "mdi:tab", size = 22.dp) }
                PageName(page.s("label").orEmpty(), Modifier.weight(1f).padding(start = 12.dp)) { setPage(i, "label", it.ifBlank { null }) }
                // Onglet favori : un seul ; toucher l'étoile allumée la retire.
                val pid = page.s("id")
                val fav = pid != null && pid == favorite
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable {
                        app.config.setBlockField(key, "favorite", if (fav) null else pid?.let { JsonPrimitive(it) })
                    },
                    contentAlignment = Alignment.Center,
                ) { HaIcon(if (fav) "mdi:star" else "mdi:star-outline", size = 24.dp, tint = if (fav) Dash.colors.accent else Muted) }
            }
        }
        Text(stringResource(R.string.pages_favorite_help), style = TextStyle(fontSize = 13.sp, color = Muted))
        val set = app.config.rawBlocks.collectAsState().value[key]?.get("set") as? JsonObject
        if (set != null && set.isNotEmpty()) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            TextLink(stringResource(R.string.pages_reset)) { app.config.setBlockField(key, "pages", null); app.config.setBlockField(key, "default", null); app.config.setBlockField(key, "favorite", null) }
        }
    }
    iconFor?.let { i ->
        IconPicker(onDismiss = { iconFor = null }) { ic -> iconFor = null; setPage(i, "icon", ic) }
    }
}

@Composable
private fun PageName(value: String, modifier: Modifier, onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    LaunchedEffect(text) {
        if (text == value) return@LaunchedEffect
        delay(600)
        onSave(text.trim())
    }
    Column(modifier) {
        Text(stringResource(R.string.tabs_name), style = TextStyle(fontSize = 12.sp, color = Muted))
        BasicTextField(
            text, { text = it }, singleLine = true,
            textStyle = TextStyle(fontSize = 17.sp, color = Color.White, fontWeight = FontWeight.Medium),
            cursorBrush = SolidColor(Dash.colors.accent),
        )
    }
}
