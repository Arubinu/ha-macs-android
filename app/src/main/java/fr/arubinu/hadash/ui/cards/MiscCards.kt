package fr.arubinu.hadash.ui.cards

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
import fr.arubinu.hadash.config.Card
import fr.arubinu.hadash.config.d
import fr.arubinu.hadash.config.i
import fr.arubinu.hadash.config.objs
import fr.arubinu.hadash.config.s
import fr.arubinu.hadash.config.strs
import fr.arubinu.hadash.ha.EntityState
import fr.arubinu.hadash.ui.LocalDashState
import fr.arubinu.hadash.ui.components.DashCard
import fr.arubinu.hadash.ui.components.HaIcon
import fr.arubinu.hadash.ui.components.editOutline
import fr.arubinu.hadash.ui.components.tapHold
import fr.arubinu.hadash.ui.entity
import fr.arubinu.hadash.ui.theme.Dash
import fr.arubinu.hadash.ui.theme.parseCssColor
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Lecteur multimédia compact (mediocre-media-player-card). */
@Composable
fun MediaPlayerCard(card: Card, modifier: Modifier) {
    val state = LocalDashState.current
    val id = card.s("entity") ?: return
    val e = entity(id)
    fun svc(name: String, data: kotlinx.serialization.json.JsonObject? = null) = state.guard(id) {
        state.ha.fireService("media_player", name, data, buildJsonObject { put("entity_id", JsonPrimitive(id)) })
    }
    val playing = e?.state == "playing"
    val art = e?.attrString("entity_picture")

    DashCard(modifier.fillMaxWidth(), padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(78.dp).clip(RoundedCornerShape(4.dp)).background(Color(120, 120, 120)),
                contentAlignment = Alignment.Center,
            ) {
                if (art != null) AsyncImage(state.ha.resolveUrl(art), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else HaIcon("mdi:music", size = 44.dp, tint = Color.Black)
            }
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(
                    e?.attrString("media_title") ?: e?.attrString("source") ?: e?.name ?: "—",
                    style = TextStyle(fontSize = 17.sp, color = Color.White, fontWeight = FontWeight.Medium),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HaIcon("mdi:speaker", size = 14.dp, tint = Color(255, 255, 255, 160))
                    Text(
                        e?.attrString("media_artist") ?: card.s("name") ?: e?.name ?: "",
                        Modifier.padding(start = 4.dp),
                        style = TextStyle(fontSize = 13.sp, color = Color(255, 255, 255, 160), fontStyle = FontStyle.Italic),
                        maxLines = 1,
                    )
                }
                Row(Modifier.padding(top = 6.dp).editOutline(state.editMode, RoundedCornerShape(12.dp)).padding(if (state.editMode) 6.dp else 0.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    HaIcon("mdi:shuffle-variant", Modifier.tapHold({ svc("shuffle_set", buildJsonObject { put("shuffle", JsonPrimitive(e?.attrBool("shuffle") != true)) }) }), 20.dp,
                        tint = if (e?.attrBool("shuffle") == true) Dash.colors.accent else Color.White)
                    HaIcon("mdi:skip-previous", Modifier.tapHold({ svc("media_previous_track") }), 24.dp)
                    Box(Modifier.size(34.dp).background(Color.White, CircleShape).tapHold({ svc("media_play_pause") }), contentAlignment = Alignment.Center) {
                        HaIcon(if (playing) "mdi:pause" else "mdi:play", size = 24.dp, tint = Color.Black)
                    }
                    HaIcon("mdi:skip-next", Modifier.tapHold({ svc("media_next_track") }), 24.dp)
                    HaIcon(if (e?.attrString("repeat") == "off" || e?.attrString("repeat") == null) "mdi:repeat-off" else "mdi:repeat",
                        Modifier.tapHold({ svc("repeat_set", buildJsonObject { put("repeat", JsonPrimitive(if (e?.attrString("repeat") == "off") "all" else "off")) }) }), 20.dp)
                }
            }
            Column(Modifier.editOutline(state.editMode, RoundedCornerShape(12.dp)).padding(if (state.editMode) 6.dp else 0.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(28.dp)) {
                HaIcon(if (e?.attrBool("is_volume_muted") == true) "mdi:volume-off" else "mdi:volume-high",
                    Modifier.tapHold({ svc("volume_mute", buildJsonObject { put("is_volume_muted", JsonPrimitive(e?.attrBool("is_volume_muted") != true)) }) }), 22.dp)
                HaIcon("mdi:power", Modifier.tapHold({ svc("toggle") }), 22.dp)
            }
        }
    }
}

/** Page web embarquée (radar Windy…) : seule carte qui reste en WebView. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewCard(card: Card, modifier: Modifier) {
    val url = card.s("url") ?: return
    val state = LocalDashState.current
    // Clé du bloc : celle d'origine si l'adresse a été modifiée en mode édition.
    val key = card.s("_key") ?: fr.arubinu.hadash.config.BlockOverrides.keyOf(card) ?: url
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
                .tapHold({ state.editTarget = fr.arubinu.hadash.ui.EditTarget.Web(key) }),
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
