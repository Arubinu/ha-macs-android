package fr.pergens.hamacs.ui.cards

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import fr.pergens.hamacs.config.Card
import fr.pergens.hamacs.config.d
import fr.pergens.hamacs.config.i
import fr.pergens.hamacs.config.objs
import fr.pergens.hamacs.config.s
import fr.pergens.hamacs.config.strs
import fr.pergens.hamacs.ha.EntityState
import fr.pergens.hamacs.ui.LocalDashState
import fr.pergens.hamacs.ui.components.DashCard
import fr.pergens.hamacs.ui.components.HaIcon
import fr.pergens.hamacs.ui.components.editOutline
import fr.pergens.hamacs.ui.components.tapHold
import fr.pergens.hamacs.ui.entity
import fr.pergens.hamacs.ui.theme.Dash
import fr.pergens.hamacs.ui.theme.parseCssColor
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Page web embarquée (radar Windy…) : seule carte qui reste en WebView. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewCard(card: Card, modifier: Modifier) {
    val url = card.s("url") ?: return
    val state = LocalDashState.current
    // Clé du bloc : celle d'origine si l'adresse a été modifiée en mode édition.
    val key = card.s("_key") ?: fr.pergens.hamacs.config.BlockOverrides.keyOf(card) ?: url
    val radius = card.d("radius")?.dp ?: Dash.shapes.roundedRadius
    DashCard(
        modifier.fillMaxWidth().aspectRatio(card.d("aspect_ratio", 1.45).toFloat()),
        radius = radius,
    ) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(-1, -1)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    setBackgroundColor(android.graphics.Color.BLACK)
                    webViewClient = WebViewClient()
                    tag = url
                    loadUrl(url)
                }
            },
            // Adresse changée (mode, zoom, position…) : rechargement.
            update = { wv -> if (wv.tag != url) { wv.tag = url; wv.loadUrl(url) } },
            modifier = Modifier.fillMaxSize(),
        )
        // Mode édition : la page web capte les touchers, un calque transparent ouvre les réglages.
        if (state.editMode) Box(
            Modifier.fillMaxSize().editOutline(true, RoundedCornerShape(radius))
                .tapHold({ state.editTarget = fr.pergens.hamacs.ui.EditTarget.Web(key) }),
        )
    }
}

/** Télécommande Apple TV (popup bubble-card). */
@Composable
fun RemoteCard(card: Card, modifier: Modifier) {
    val state = LocalDashState.current
    val remote = card.s("entity") ?: "remote.salon_appletv"
    val player = card.s("media_player") ?: "media_player.salon_appletv"
    val keys = listOf(
        "mdi:power-off" to "suspend", "mdi:chevron-up" to "up", "mdi:power" to "wakeup",
        "mdi:chevron-left" to "left", "mdi:circle" to "select", "mdi:chevron-right" to "right",
        "mdi:arrow-left" to "menu", "mdi:chevron-down" to "down", "mdi:home" to "home",
        "mdi:skip-previous" to "skip_backward", "mdi:play-pause" to "play_pause", "mdi:skip-next" to "skip_forward",
        "mdi:volume-mute" to "volume_mute", "mdi:volume-minus" to "volume_down", "mdi:volume-plus" to "volume_up",
    )
    // Toute la télécommande (touches et applications) est protégée par le réglage de l'entité remote.
    fun send(cmd: String) = state.guard(remote) {
        state.ha.fireService(
            "remote", "send_command",
            data = buildJsonObject { put("command", JsonPrimitive(cmd)) },
            target = buildJsonObject { put("entity_id", JsonPrimitive(remote)) },
        )
    }
    Row(modifier.fillMaxWidth().then(Modifier.editOutline(state.editMode, androidx.compose.foundation.shape.RoundedCornerShape(Dash.shapes.cardRadius))), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            keys.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (icon, cmd) ->
                        val shape = RoundedCornerShape(12.dp)
                        Box(
                            Modifier.weight(1f).aspectRatio(1f).clip(shape)
                                .background(if (cmd == "select") Color(255, 255, 255, 40) else Color(255, 255, 255, 13), shape)
                                .tapHold({ send(cmd) }),
                            contentAlignment = Alignment.Center,
                        ) { HaIcon(icon, size = if (cmd == "select") 20.dp else 32.dp) }
                    }
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            card.objs("apps").ifEmpty {
                listOf(
                    buildJsonObject { put("name", JsonPrimitive("YouTube")); put("icon", JsonPrimitive("mdi:youtube")); put("color", JsonPrimitive("white")); put("background", JsonPrimitive("red")) },
                    buildJsonObject { put("name", JsonPrimitive("Netflix")); put("icon", JsonPrimitive("mdi:netflix")); put("color", JsonPrimitive("red")); put("background", JsonPrimitive("black")) },
                )
            }.forEach { app ->
                val shape = RoundedCornerShape(12.dp)
                Box(
                    Modifier.fillMaxWidth().aspectRatio(1f).clip(shape)
                        .background(parseCssColor(app.s("background")) ?: Color.DarkGray, shape)
                        .tapHold({
                            state.guard(remote) {
                                state.ha.fireService(
                                    "media_player", "select_source",
                                    data = buildJsonObject { put("source", JsonPrimitive(app.s("name") ?: "")) },
                                    target = buildJsonObject { put("entity_id", JsonPrimitive(player)) },
                                )
                            }
                        }),
                    contentAlignment = Alignment.Center,
                ) { HaIcon(app.s("icon"), size = 48.dp, tint = parseCssColor(app.s("color")) ?: Color.White) }
            }
        }
    }
}
