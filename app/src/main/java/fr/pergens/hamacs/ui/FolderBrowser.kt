package fr.pergens.hamacs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.pergens.hamacs.R
import fr.pergens.hamacs.ui.components.HaIcon
import fr.pergens.hamacs.ui.components.tapHold
import fr.pergens.hamacs.ui.theme.Dash

/** Dossier de l'explorateur : nom affiché et identifiant (chemin relatif, `media-source://…`). */
class FolderNode(val label: String, val id: String)

/**
 * Explorateur de dossiers : fil d'Ariane (retour à un niveau parent), sous-dossiers du dossier courant, et
 * « Choisir ce dossier ». [children] liste les sous-dossiers d'un identifiant.
 */
@Composable
fun FolderBrowser(
    title: String,
    root: FolderNode,
    children: suspend (String) -> List<FolderNode>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var stack by remember { mutableStateOf(listOf(root)) }
    var items by remember { mutableStateOf<List<FolderNode>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val current = stack.last()
    LaunchedEffect(current.id) {
        items = null
        error = null
        runCatching { children(current.id) }
            .onSuccess { items = it }
            .onFailure { error = it.message ?: it.javaClass.simpleName }
    }

    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(20.dp)
        Box(Modifier.fillMaxWidth(0.42f).clip(shape).background(Color(0xF2222222), shape).padding(20.dp)) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-11).dp).size(48.dp).clip(CircleShape).tapHold(onDismiss),
                contentAlignment = Alignment.Center,
            ) { HaIcon("mdi:close", size = 26.dp, tint = Color(255, 255, 255, 200)) }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, Modifier.padding(end = 48.dp), style = TextStyle(fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Medium))
                // Fil d'Ariane : toucher un niveau y revient.
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    stack.forEachIndexed { i, node ->
                        if (i > 0) HaIcon("mdi:chevron-right", size = 18.dp, tint = Color.White.copy(alpha = 0.4f))
                        val last = i == stack.lastIndex
                        Text(
                            node.label,
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !last) { stack = stack.take(i + 1) }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            style = TextStyle(fontSize = 14.sp, color = if (last) Color.White else Dash.colors.accent, fontWeight = if (last) FontWeight.Medium else FontWeight.Normal),
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 420.dp)) {
                    // Liste figée pour cet affichage : elle repasse à null pendant le chargement du dossier suivant.
                    val list = items
                    val err = error
                    when {
                        err != null -> Text(stringResource(R.string.browse_error, err), Modifier.padding(8.dp), style = TextStyle(fontSize = 14.sp, color = Color(0xFFFF6B6B)))
                        list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp), color = Dash.colors.accent, strokeWidth = 3.dp)
                        list.isEmpty() -> Text(stringResource(R.string.browse_empty), Modifier.align(Alignment.Center), style = TextStyle(fontSize = 14.sp, color = Color.White.copy(alpha = 0.5f)))
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(list, key = { it.id }) { node ->
                                val rowShape = RoundedCornerShape(12.dp)
                                Row(
                                    Modifier.fillMaxWidth().clip(rowShape).background(Color(255, 255, 255, 10), rowShape)
                                        .clickable { stack = stack + node }.heightIn(min = 48.dp).padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    HaIcon("mdi:folder-outline", size = 22.dp, tint = Dash.colors.accent)
                                    Text(node.label, Modifier.weight(1f).padding(start = 12.dp), style = TextStyle(fontSize = 15.sp, color = Color.White))
                                    HaIcon("mdi:chevron-right", size = 20.dp, tint = Color.White.copy(alpha = 0.4f))
                                }
                            }
                        }
                    }
                }
                val btnShape = RoundedCornerShape(12.dp)
                Text(
                    stringResource(R.string.browse_pick),
                    Modifier.fillMaxWidth().clip(btnShape).background(Dash.colors.accent, btnShape).border(1.dp, Dash.colors.accent, btnShape)
                        .clickable { onPick(current.id) }.padding(vertical = 12.dp),
                    style = TextStyle(fontSize = 15.sp, color = Color.Black, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
                )
            }
        }
    }
}
