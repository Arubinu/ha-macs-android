package fr.pergens.hamacs.update

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.pergens.hamacs.App
import fr.pergens.hamacs.BuildConfig
import fr.pergens.hamacs.R
import fr.pergens.hamacs.ui.MarkdownText
import fr.pergens.hamacs.ui.Sheet
import fr.pergens.hamacs.ui.components.HaIcon
import fr.pergens.hamacs.ui.components.tapHold
import fr.pergens.hamacs.ui.theme.Dash

/**
 * « Quoi de neuf » après une mise à jour : la version vue en dernier est mémorisée (prefs `whats_new`, non exportées) ;
 * au premier démarrage d'une nouvelle version, ses notes de Release s'affichent une fois. Notes gardées au moment de
 * l'installation par l'app ([remember]), sinon relues sur GitHub. Première installation : rien n'est affiché.
 */
object WhatsNew {
    sealed interface Notes {
        data class Found(val text: String) : Notes
        data object None : Notes
        data object Failed : Notes
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("whats_new", Context.MODE_PRIVATE)

    /** Version dont les nouveautés restent à montrer, ou null (déjà vue, ou première installation). */
    fun pending(ctx: Context): String? {
        val current = BuildConfig.VERSION_NAME
        val last = prefs(ctx).getString("last", null)
        if (last == null) { seen(ctx); return null }
        return current.takeIf { it != last && Updater.newer(it, last) }
    }

    fun seen(ctx: Context) {
        prefs(ctx).edit().putString("last", BuildConfig.VERSION_NAME).remove("notes").remove("notes_version").apply()
    }

    /** Notes de la version en cours d'installation (lues par la nouvelle version au démarrage). */
    fun remember(ctx: Context, version: String, notes: String) {
        prefs(ctx).edit().putString("notes_version", version).putString("notes", notes).commit()
    }

    suspend fun notes(ctx: Context, version: String): Notes {
        val p = prefs(ctx)
        if (p.getString("notes_version", null) == version) p.getString("notes", null)?.takeIf { it.isNotBlank() }?.let { return Notes.Found(it) }
        return runCatching { App.instance.updater.notesFor(version) }
            .fold({ it?.takeIf { n -> n.isNotBlank() }?.let { n -> Notes.Found(n) } ?: Notes.None }, { Notes.Failed })
    }
}

/** Fenêtre des nouveautés de [version] ; [preloaded] : notes déjà chargées (sinon chargement ici). */
@Composable
fun WhatsNewSheet(version: String, preloaded: WhatsNew.Notes? = null, onDismiss: () -> Unit) {
    val notes by produceState(preloaded) { if (value == null) value = WhatsNew.notes(App.instance, version) }
    Sheet(onDismiss, 0.55f) {
        Box(
            Modifier.align(Alignment.TopEnd).size(44.dp).clip(CircleShape).tapHold(onDismiss),
            contentAlignment = Alignment.Center,
        ) { HaIcon("mdi:close", size = 26.dp, tint = Color(255, 255, 255, 200)) }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.padding(end = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                HaIcon("mdi:party-popper", size = 26.dp, tint = Dash.colors.accent)
                Text(
                    stringResource(R.string.whats_new_title, version), Modifier.padding(start = 12.dp),
                    style = TextStyle(fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Medium),
                )
            }
            when (val n = notes) {
                null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = Dash.colors.accent, strokeWidth = 2.5.dp)
                }
                is WhatsNew.Notes.Found -> {
                    val scroll = rememberScrollState()
                    MarkdownText(n.text, Modifier.fillMaxWidth().heightIn(max = 620.dp).scrollFade(scroll).verticalScroll(scroll))
                }
                else -> Text(
                    stringResource(if (n == WhatsNew.Notes.None) R.string.whats_new_none else R.string.whats_new_failed),
                    style = TextStyle(fontSize = 14.sp, color = Color(0xFFA3A3AD), lineHeight = 20.sp),
                )
            }
        }
    }
}

/** Fondu en haut / en bas d'une zone qui défile, seulement là où il reste du contenu. */
internal fun Modifier.scrollFade(scroll: androidx.compose.foundation.ScrollState, size: androidx.compose.ui.unit.Dp = 28.dp): Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val f = size.toPx() / this.size.height
        if (scroll.value > 0) drawRect(
            androidx.compose.ui.graphics.Brush.verticalGradient(0f to Color.Transparent, f to Color.Black),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
        if (scroll.value < scroll.maxValue) drawRect(
            androidx.compose.ui.graphics.Brush.verticalGradient(1f - f to Color.Black, 1f to Color.Transparent),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
    }
