package fr.pergens.hamacs.ui.cards

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.SuccessResult
import fr.pergens.hamacs.App
import fr.pergens.hamacs.R
import fr.pergens.hamacs.config.Card
import fr.pergens.hamacs.config.b
import fr.pergens.hamacs.config.s
import fr.pergens.hamacs.config.strs
import fr.pergens.hamacs.ha.EntityState
import fr.pergens.hamacs.ui.LocalDashState
import fr.pergens.hamacs.ui.Sheet
import fr.pergens.hamacs.ui.components.DashCard
import fr.pergens.hamacs.ui.components.HaIcon
import fr.pergens.hamacs.ui.components.editOutline
import fr.pergens.hamacs.ui.components.tapHold
import fr.pergens.hamacs.ui.entity
import fr.pergens.hamacs.ui.theme.Dash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.OffsetDateTime

/** Fonctions d'un lecteur (`supported_features` de Home Assistant). */
private object MF {
    const val PAUSE = 1L
    const val SEEK = 2L
    const val VOLUME_SET = 4L
    const val VOLUME_MUTE = 8L
    const val PREVIOUS = 16L
    const val NEXT = 32L
    const val TURN_ON = 128L
    const val TURN_OFF = 256L
    const val VOLUME_STEP = 1024L
    const val SELECT_SOURCE = 2048L
    const val STOP = 4096L
    const val PLAY = 16384L
    const val SHUFFLE = 32768L
    const val SOUND_MODE = 65536L
    const val BROWSE = 131072L
    const val REPEAT = 262144L
    const val GROUPING = 524288L
    const val SEARCH = 4194304L
}

private fun EntityState?.has(f: Long) = this != null && ((attrDouble("supported_features")?.toLong() ?: 0L) and f) != 0L
private val EntityState?.isOff get() = this == null || isUnavailable || state == "off" || state == "standby"
private val Muted = Color(0xFFA3A3AD)

/** Position actuelle (s) : `media_position` + temps écoulé depuis `media_position_updated_at` pendant la lecture. */
@Composable
private fun livePosition(e: EntityState?): Pair<Double, Double>? {
    val duration = e?.attrDouble("media_duration")?.takeIf { it > 0 } ?: return null
    val base = e.attrDouble("media_position") ?: 0.0
    val at = e.attrString("media_position_updated_at")?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(e.state) { while (e.state == "playing") { now = System.currentTimeMillis(); delay(500) } }
    val pos = if (e.state == "playing" && at != null) base + (now - at) / 1000.0 else base
    return pos.coerceIn(0.0, duration) to duration
}

private fun clock(s: Double): String {
    val t = s.toLong()
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
}

/** Couleur dominante de la pochette (moyenne des pixels assez saturés), pour teinter le lecteur. */
@Composable
private fun artColor(url: String?): Color? {
    val ctx = LocalContext.current
    val c by produceState<Color?>(null, url) {
        value = url?.let {
            withContext(Dispatchers.IO) {
                runCatching {
                    val r = coil.Coil.imageLoader(ctx).execute(ImageRequest.Builder(ctx).data(it).allowHardware(false).size(32).build())
                    val bmp = ((r as? SuccessResult)?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return@runCatching null
                    val small = Bitmap.createScaledBitmap(bmp, 16, 16, true)
                    var rs = 0.0; var gs = 0.0; var bs = 0.0; var n = 0.0
                    for (y in 0 until 16) for (x in 0 until 16) {
                        val p = small.getPixel(x, y)
                        val hsv = FloatArray(3)
                        android.graphics.Color.colorToHSV(p, hsv)
                        val w = (hsv[1] * hsv[2]).toDouble() + 0.05
                        rs += android.graphics.Color.red(p) * w; gs += android.graphics.Color.green(p) * w; bs += android.graphics.Color.blue(p) * w; n += w
                    }
                    Color((rs / n).toInt(), (gs / n).toInt(), (bs / n).toInt())
                }.getOrNull()
            }
        }
    }
    return c
}

/** État du tableau de bord (un seul dans l'app), pour demander le code d'accès d'un lecteur protégé. */
private var dash: fr.pergens.hamacs.ui.DashState? = null

/** Appel d'un service media_player sur [id] (code d'accès demandé si le bloc est protégé). */
private fun svc(id: String, name: String, data: JsonObject? = null) {
    val run = { App.instance.ha.fireService("media_player", name, data, buildJsonObject { put("entity_id", id) }) }
    dash?.guard(id) { run() } ?: run()
}

/**
 * Lecteur multimédia (inspiré de la Mediocre Media Player Card) : pochette et couleurs de la pochette, titre, artiste,
 * album, progression en direct (toucher pour se déplacer), commandes selon les fonctions du lecteur, volume, marche /
 * arrêt ; plusieurs lecteurs (`players`) avec des pastilles pour passer de l'un à l'autre. Toucher la pochette ou le
 * titre ouvre la grande fenêtre (bibliothèque, recherche, file d'attente, source, enceintes).
 */
@Composable
fun MediaPlayerCard(card: Card, modifier: Modifier) {
    val state = LocalDashState.current
    val main = card.s("entity") ?: return
    val players = (listOf(main) + card.strs("players")).distinct()
    var current by remember(players) { mutableStateOf(main) }
    val id = current.takeIf { it in players } ?: main
    val e = entity(id)
    val off = e.isOff
    val art = e?.attrString("entity_picture")?.let { state.ha.resolveUrl(it) }.takeIf { !off }
    val useArt = card["use_art_colors"]?.let { (it as? JsonPrimitive)?.content != "false" } ?: true
    val tint by animateColorAsState(artColor(art).takeIf { useArt } ?: Dash.colors.accent.copy(alpha = 0f), tween(600), label = "art")
    var big by remember { mutableStateOf(false) }
    dash = state
    if (big) MediaSheet(card, id, onSwitch = { current = it }, players = players) { big = false }

    DashCard(modifier.fillMaxWidth(), padding = 0.dp) {
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(tint.copy(alpha = tint.alpha * 0.35f), Color.Transparent))))
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (players.size > 1) PlayerChips(players, id) { current = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(art, 84.dp, Modifier.tapHold({ if (!state.editInstead(fr.pergens.hamacs.ui.EditTarget.Entity(main))) big = true }))
                Column(
                    Modifier.weight(1f).padding(horizontal = 16.dp).tapHold({ if (!state.editInstead(fr.pergens.hamacs.ui.EditTarget.Entity(main))) big = true }),
                ) {
                    Titles(e, card.s("name").takeIf { id == main }, off, 18.sp)
                }
                PowerButton(id, e, card.b("always_show_power_button"))
            }
            if (!off) {
                Progress(id, e, compact = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Controls(id, e, small = true)
                    Spacer(Modifier.width(16.dp))
                    Volume(id, e, card.b("show_volume_step_buttons"), Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PlayerChips(players: List<String>, selected: String, onPick: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        players.forEach { p ->
            val pe = entity(p)
            val sel = p == selected
            val shape = RoundedCornerShape(16.dp)
            Row(
                Modifier.clip(shape).background(if (sel) Dash.colors.accent.copy(alpha = 0.18f) else Color(0x14FFFFFF), shape)
                    .border(1.dp, if (sel) Dash.colors.accent else Color.Transparent, shape)
                    .tapHold({ onPick(p) }).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HaIcon(if (pe?.state == "playing") "mdi:music-note" else "mdi:speaker", size = 15.dp, tint = if (pe?.state == "playing") Dash.colors.accent else Muted)
                Text(pe?.name ?: p, Modifier.padding(start = 6.dp), style = TextStyle(fontSize = 13.sp, color = Color.White), maxLines = 1)
            }
        }
    }
}

@Composable
private fun Artwork(url: String?, size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size / 8)
    Box(modifier.size(size).clip(shape).background(Color(0x1FFFFFFF), shape), contentAlignment = Alignment.Center) {
        if (url != null) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else HaIcon("mdi:music", size = size * 0.5f, tint = Color(0x80FFFFFF))
    }
}

@Composable
private fun Titles(e: EntityState?, name: String?, off: Boolean, size: androidx.compose.ui.unit.TextUnit) {
    val title = if (off) name ?: e?.name ?: "—" else e?.attrString("media_title") ?: e?.attrString("source") ?: name ?: e?.name ?: "—"
    Text(title, style = TextStyle(fontSize = size, color = Color.White, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
    val sub = if (off) stringResource(if (e == null || e.isUnavailable) R.string.media_unavailable else R.string.media_off)
    else listOfNotNull(e?.attrString("media_artist") ?: e?.attrString("media_album_artist"), e?.attrString("media_album_name")).joinToString(" · ")
        .ifEmpty { e?.attrString("app_name") ?: name ?: e?.name.orEmpty() }
    Text(sub, Modifier.padding(top = 2.dp), style = TextStyle(fontSize = size * 0.75f, color = Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
    // Lecteur en pause ou au repos : son état ; sinon la source ou l'application.
    if (!off) (e?.attrString("source") ?: e?.attrString("app_name"))?.takeIf { it != e?.attrString("media_title") }?.let {
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            HaIcon("mdi:speaker", size = 13.dp, tint = Muted)
            Text(it, Modifier.padding(start = 4.dp), style = TextStyle(fontSize = size * 0.65f, color = Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PowerButton(id: String, e: EntityState?, always: Boolean) {
    val off = e.isOff
    if (!(off && e.has(MF.TURN_ON)) && !(always && e.has(MF.TURN_OFF))) return
    Box(Modifier.size(44.dp).clip(CircleShape).tapHold({ svc(id, if (off) "turn_on" else "turn_off") }), contentAlignment = Alignment.Center) {
        HaIcon("mdi:power", size = 24.dp, tint = if (off) Muted else Color.White)
    }
}

/** Barre de progression : temps écoulé / durée ; toucher ou glisser pour se déplacer (si le lecteur le permet). */
@Composable
private fun Progress(id: String, e: EntityState?, compact: Boolean) {
    val (pos, duration) = livePosition(e) ?: return
    var drag by remember { mutableStateOf<Float?>(null) }
    val value = drag ?: (pos / duration).toFloat()
    Column {
        SlimSlider(
            value, Dash.colors.accent, Modifier.fillMaxWidth(), enabled = e.has(MF.SEEK), thick = !compact,
            onChange = { drag = it },
            onDone = { drag?.let { svc(id, "media_seek", buildJsonObject { put("seek_position", it * duration) }) }; drag = null },
        )
        Row(Modifier.fillMaxWidth()) {
            Text(clock(value * duration), Modifier.weight(1f), style = TextStyle(fontSize = 12.sp, color = Muted))
            Text(clock(duration), style = TextStyle(fontSize = 12.sp, color = Muted))
        }
    }
}

/** Aléatoire, précédent, lecture / pause, suivant, répétition (off → tout → un) : seulement ce que le lecteur gère. */
@Composable
private fun Controls(id: String, e: EntityState?, small: Boolean) {
    val playing = e?.state == "playing"
    val s = if (small) 1f else 1.35f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((14 * s).dp)) {
        if (e.has(MF.SHUFFLE)) {
            val on = e?.attrBool("shuffle") == true
            IconTap("mdi:shuffle-variant", (20 * s).dp, if (on) Dash.colors.accent else Color.White) { svc(id, "shuffle_set", buildJsonObject { put("shuffle", !on) }) }
        }
        if (e.has(MF.PREVIOUS)) IconTap("mdi:skip-previous", (26 * s).dp) { svc(id, "media_previous_track") }
        Box(
            Modifier.size((40 * s).dp).background(Dash.colors.accent, CircleShape).clip(CircleShape)
                .tapHold({ svc(id, if (playing && !e.has(MF.PAUSE) && e.has(MF.STOP)) "media_stop" else "media_play_pause") }),
            contentAlignment = Alignment.Center,
        ) {
            HaIcon(if (playing) (if (e.has(MF.PAUSE)) "mdi:pause" else "mdi:stop") else "mdi:play", size = (26 * s).dp, tint = Color.Black)
        }
        if (e.has(MF.NEXT)) IconTap("mdi:skip-next", (26 * s).dp) { svc(id, "media_next_track") }
        if (e.has(MF.REPEAT)) {
            val r = e?.attrString("repeat") ?: "off"
            IconTap(when (r) { "one" -> "mdi:repeat-once"; "all" -> "mdi:repeat"; else -> "mdi:repeat-off" }, (20 * s).dp, if (r == "off") Color.White else Dash.colors.accent) {
                svc(id, "repeat_set", buildJsonObject { put("repeat", when (r) { "off" -> "all"; "all" -> "one"; else -> "off" }) })
            }
        }
    }
}

/**
 * Curseur fin aux couleurs du tableau de bord : piste de 4 dp (6 dp dans la grande fenêtre), petit rond ; toucher ou
 * glisser déplace la valeur, relâcher l'envoie ([onDone]).
 */
@Composable
private fun SlimSlider(
    value: Float, color: Color, modifier: Modifier = Modifier, enabled: Boolean = true, thick: Boolean = false,
    onChange: (Float) -> Unit, onDone: () -> Unit,
) {
    val track = if (thick) 6.dp else 4.dp
    val thumb = if (thick) 16.dp else 12.dp
    BoxWithConstraints(
        modifier.height(28.dp).then(
            if (!enabled) Modifier else Modifier
                .pointerInput(Unit) {
                    detectTapGestures { o -> onChange((o.x / size.width).coerceIn(0f, 1f)); onDone() }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> onChange((o.x / size.width).coerceIn(0f, 1f)) },
                        onDragEnd = onDone, onDragCancel = onDone,
                    ) { change, _ -> change.consume(); onChange((change.position.x / size.width).coerceIn(0f, 1f)) }
                },
        ),
        contentAlignment = Alignment.CenterStart,
    ) {
        val v = value.coerceIn(0f, 1f)
        val shape = RoundedCornerShape(track)
        Box(Modifier.fillMaxWidth().height(track).clip(shape).background(Color(0x33FFFFFF), shape))
        Box(Modifier.fillMaxWidth(v).height(track).clip(shape).background(color, shape))
        if (enabled) Box(
            Modifier.offset(x = (maxWidth - thumb) * v).size(thumb).background(color, CircleShape),
        )
    }
}

@Composable
private fun IconTap(icon: String, size: Dp, tint: Color = Color.White, onClick: () -> Unit) {
    Box(Modifier.size(size + 16.dp).clip(CircleShape).tapHold(onClick), contentAlignment = Alignment.Center) {
        HaIcon(icon, size = size, tint = tint)
    }
}

/** Volume : muet, curseur (relâché = envoyé), boutons − / + en option. */
@Composable
private fun Volume(id: String, e: EntityState?, steps: Boolean, modifier: Modifier = Modifier) {
    if (!e.has(MF.VOLUME_SET) && !e.has(MF.VOLUME_STEP) && !e.has(MF.VOLUME_MUTE)) return
    val level = e?.attrDouble("volume_level")?.toFloat() ?: 0f
    val muted = e?.attrBool("is_volume_muted") == true
    var drag by remember { mutableStateOf<Float?>(null) }
    val v = drag ?: level
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconTap(
            when { muted || v == 0f -> "mdi:volume-off"; v < 0.35f -> "mdi:volume-low"; v < 0.7f -> "mdi:volume-medium"; else -> "mdi:volume-high" },
            22.dp, if (muted) Muted else Color.White,
        ) { if (e.has(MF.VOLUME_MUTE)) svc(id, "volume_mute", buildJsonObject { put("is_volume_muted", !muted) }) }
        if (steps || !e.has(MF.VOLUME_SET)) IconTap("mdi:minus", 18.dp) {
            if (e.has(MF.VOLUME_SET)) svc(id, "volume_set", buildJsonObject { put("volume_level", (level - 0.05f).coerceAtLeast(0f).toDouble()) }) else svc(id, "volume_down")
        }
        if (e.has(MF.VOLUME_SET)) SlimSlider(
            v, Color.White, Modifier.weight(1f).padding(horizontal = 4.dp),
            onChange = { drag = it },
            onDone = { drag?.let { svc(id, "volume_set", buildJsonObject { put("volume_level", it.toDouble()) }) }; drag = null },
        )
        if (steps || !e.has(MF.VOLUME_SET)) IconTap("mdi:plus", 18.dp) {
            if (e.has(MF.VOLUME_SET)) svc(id, "volume_set", buildJsonObject { put("volume_level", (level + 0.05f).coerceAtMost(1f).toDouble()) }) else svc(id, "volume_up")
        }
        if (drag != null) Text("${(v * 100).toInt()} %", Modifier.width(48.dp), style = TextStyle(fontSize = 12.sp, color = Muted))
    }
}

// ------------------------------------------------------------------------------------------------------------------
// Grande fenêtre : lecture à gauche, bibliothèque / recherche / file d'attente à droite.
// ------------------------------------------------------------------------------------------------------------------

private enum class Tab { Library, Search, Queue }

@Composable
private fun MediaSheet(card: Card, id: String, players: List<String>, onSwitch: (String) -> Unit, onDismiss: () -> Unit) {
    val state = LocalDashState.current
    val e = entity(id)
    val off = e.isOff
    val art = e?.attrString("entity_picture")?.let { state.ha.resolveUrl(it) }.takeIf { !off }
    val tint = artColor(art) ?: Dash.colors.accent
    val tabs = listOfNotNull(Tab.Library.takeIf { e.has(MF.BROWSE) }, Tab.Search.takeIf { e.has(MF.SEARCH) }, Tab.Queue)
    var tab by remember(id) { mutableStateOf(tabs.first()) }
    Sheet(onDismiss, 0.8f) {
        // Fond opaque teinté par la pochette (le tableau de bord ne doit pas transparaître).
        Box(Modifier.matchParentSize().background(Color(0xFF222222)))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.25f), Color.Transparent))))
        Box(
            Modifier.align(Alignment.TopEnd).size(44.dp).clip(CircleShape).tapHold(onDismiss),
            contentAlignment = Alignment.Center,
        ) { HaIcon("mdi:close", size = 26.dp, tint = Color(255, 255, 255, 200)) }
        Row(Modifier.fillMaxWidth().height(640.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            // Lecture
            Column(Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (players.size > 1) PlayerChips(players, id, onSwitch)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Artwork(art, 230.dp) }
                Column { Titles(e, card.s("name"), off, 22.sp) }
                if (!off) {
                    Progress(id, e, compact = false)
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Controls(id, e, small = false) }
                    Volume(id, e, card.b("show_volume_step_buttons"))
                }
                ExtraRow(card, id, e)
            }
            // Contenu
            Column(Modifier.weight(1f).fillMaxHeight().padding(top = 4.dp, end = 48.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tabs.forEach { t ->
                        val sel = t == tab
                        val shape = RoundedCornerShape(18.dp)
                        Text(
                            stringResource(when (t) { Tab.Library -> R.string.media_library; Tab.Search -> R.string.media_search; Tab.Queue -> R.string.media_queue }),
                            Modifier.clip(shape).background(if (sel) Dash.colors.accent else Color(0x14FFFFFF), shape).tapHold({ tab = t })
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            style = TextStyle(fontSize = 14.sp, color = if (sel) Color.Black else Color.White, fontWeight = FontWeight.Medium),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                when (tab) {
                    Tab.Library -> Library(id)
                    Tab.Search -> Search(id)
                    Tab.Queue -> Queue(id, e)
                }
            }
        }
    }
}

/** Source, mode son, enceintes groupées, favori et marche / arrêt. */
@Composable
private fun ExtraRow(card: Card, id: String, e: EntityState?) {
    var menu by remember { mutableStateOf<String?>(null) }
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (e.has(MF.SELECT_SOURCE) && e?.attrList("source_list")?.isNotEmpty() == true)
            Chip("mdi:import", e.attrString("source") ?: stringResource(R.string.media_source)) { menu = "source" }
        if (e.has(MF.SOUND_MODE) && e?.attrList("sound_mode_list")?.isNotEmpty() == true)
            Chip("mdi:equalizer", e.attrString("sound_mode") ?: stringResource(R.string.media_sound_mode)) { menu = "sound" }
        if (e.has(MF.GROUPING)) {
            val n = e?.attrList("group_members")?.size ?: 0
            Chip("mdi:speaker-multiple", stringResource(R.string.media_group) + if (n > 1) " · $n" else "") { menu = "group" }
        }
        card.s("favorite_button")?.let { fav -> Chip("mdi:heart-plus-outline", stringResource(R.string.media_favorite)) {
            App.instance.ha.fireService("button", "press", target = buildJsonObject { put("entity_id", fav) })
        } }
        if (e.has(MF.TURN_OFF) && !e.isOff) Chip("mdi:power", stringResource(R.string.media_turn_off)) { svc(id, "turn_off") }
    }
    when (menu) {
        "source" -> PickList(stringResource(R.string.media_source), e?.attrList("source_list").orEmpty(), e?.attrString("source"), { menu = null }) {
            svc(id, "select_source", buildJsonObject { put("source", it) }); menu = null
        }
        "sound" -> PickList(stringResource(R.string.media_sound_mode), e?.attrList("sound_mode_list").orEmpty(), e?.attrString("sound_mode"), { menu = null }) {
            svc(id, "select_sound_mode", buildJsonObject { put("sound_mode", it) }); menu = null
        }
        "group" -> GroupList(id, e) { menu = null }
    }
}

@Composable
private fun Chip(icon: String, text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.clip(shape).background(Color(0x1AFFFFFF), shape).tapHold(onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HaIcon(icon, size = 16.dp, tint = Color.White)
        Text(text, Modifier.padding(start = 6.dp), style = TextStyle(fontSize = 13.sp, color = Color.White), maxLines = 1)
    }
}

@Composable
private fun PickList(title: String, items: List<String>, selected: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    Sheet(onDismiss, 0.32f) {
        Column {
            Text(title, Modifier.padding(bottom = 10.dp), style = TextStyle(fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.Medium))
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                items(items) { s ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).tapHold({ onPick(s) }).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(s, Modifier.weight(1f), style = TextStyle(fontSize = 15.sp, color = if (s == selected) Dash.colors.accent else Color.White))
                        if (s == selected) HaIcon("mdi:check", size = 20.dp, tint = Dash.colors.accent)
                    }
                }
            }
        }
    }
}

/** Enceintes : lecteurs qui savent se regrouper ; cocher = rejoindre ce lecteur, décocher = quitter le groupe. */
@Composable
private fun GroupList(id: String, e: EntityState?, onDismiss: () -> Unit) {
    val states by App.instance.ha.states.collectAsState()
    val members = e?.attrList("group_members").orEmpty()
    val candidates = states.values.filter { it.domain == "media_player" && it.entityId != id && it.has(MF.GROUPING) && !it.isUnavailable }.sortedBy { it.name }
    Sheet(onDismiss, 0.36f) {
        Column {
            Text(stringResource(R.string.media_group), style = TextStyle(fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.Medium))
            Text(stringResource(R.string.media_group_help), Modifier.padding(bottom = 10.dp), style = TextStyle(fontSize = 13.sp, color = Muted))
            if (candidates.isEmpty()) Text(stringResource(R.string.media_empty), style = TextStyle(fontSize = 14.sp, color = Muted))
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(candidates) { c ->
                    val joined = c.entityId in members
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).tapHold({
                            if (joined) App.instance.ha.fireService("media_player", "unjoin", target = buildJsonObject { put("entity_id", c.entityId) })
                            else svc(id, "join", buildJsonObject { put("group_members", JsonArray(listOf(JsonPrimitive(c.entityId)))) })
                        }).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HaIcon(if (joined) "mdi:checkbox-marked-circle" else "mdi:checkbox-blank-circle-outline", size = 22.dp, tint = if (joined) Dash.colors.accent else Muted)
                        Text(c.name, Modifier.padding(start = 12.dp), style = TextStyle(fontSize = 15.sp, color = Color.White))
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------------------------
// Bibliothèque (media_player/browse_media), recherche (media_player.search_media), file d'attente (Music Assistant).
// ------------------------------------------------------------------------------------------------------------------

/** Élément de bibliothèque ou de résultat de recherche (BrowseMedia de Home Assistant). */
private class Item(val o: JsonObject) {
    val title get() = o.s("title") ?: ""
    val id get() = o.s("media_content_id") ?: ""
    val type get() = o.s("media_content_type") ?: "music"
    val canPlay get() = (o["can_play"] as? JsonPrimitive)?.content == "true"
    val canExpand get() = (o["can_expand"] as? JsonPrimitive)?.content == "true"
    val thumb get() = o.s("thumbnail")
    val cls get() = o.s("media_class") ?: ""
    val children get() = (o["children"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::Item) }.orEmpty()
}

private fun play(id: String, it: Item, enqueue: String? = null) = svc(id, "play_media", buildJsonObject {
    put("media_content_id", it.id); put("media_content_type", it.type)
    enqueue?.let { e -> put("enqueue", e) }
})

@Composable
private fun Library(id: String) {
    val ha = App.instance.ha
    // Pile de navigation (null = racine).
    var stack by remember(id) { mutableStateOf(listOf<Item?>(null)) }
    val at = stack.last()
    val node by produceState<Result<Item>?>(null, id, at?.id) {
        value = null
        value = runCatching {
            val r = ha.call(buildJsonObject {
                put("type", "media_player/browse_media"); put("entity_id", id)
                at?.let { put("media_content_id", it.id); put("media_content_type", it.type) }
            }, 30_000)
            Item(r as JsonObject)
        }
    }
    Column {
        if (stack.size > 1) Row(Modifier.clip(RoundedCornerShape(10.dp)).tapHold({ stack = stack.dropLast(1) }).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            HaIcon("mdi:arrow-left", size = 20.dp, tint = Color.White)
            Text(at?.title ?: "", Modifier.padding(start = 8.dp), style = TextStyle(fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Medium), maxLines = 1)
        }
        when (val n = node) {
            null -> Text("…", style = TextStyle(fontSize = 14.sp, color = Muted))
            else -> n.fold(
                { ItemList(id, it.children) { child -> stack = stack + child } },
                { Text(stringResource(R.string.media_browse_error), style = TextStyle(fontSize = 14.sp, color = Muted)) },
            )
        }
    }
}

@Composable
private fun ItemList(id: String, items: List<Item>, onOpen: (Item) -> Unit) {
    if (items.isEmpty()) { Text(stringResource(R.string.media_empty), style = TextStyle(fontSize = 14.sp, color = Muted)); return }
    val ha = App.instance.ha
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(items) { it ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).tapHold({ if (it.canExpand) onOpen(it) else if (it.canPlay) play(id, it) })
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val shape = RoundedCornerShape(8.dp)
                Box(Modifier.size(48.dp).clip(shape).background(Color(0x14FFFFFF), shape), contentAlignment = Alignment.Center) {
                    val t = ha.resolveUrl(it.thumb)
                    if (t != null) AsyncImage(t, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else HaIcon(classIcon(it.cls), size = 24.dp, tint = Muted)
                }
                Text(it.title, Modifier.weight(1f).padding(horizontal = 12.dp), style = TextStyle(fontSize = 15.sp, color = Color.White), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (it.canPlay) {
                    IconTap("mdi:playlist-plus", 20.dp, Muted) { play(id, it, "add") }
                    IconTap("mdi:play-circle", 28.dp, Dash.colors.accent) { play(id, it) }
                }
                if (it.canExpand) HaIcon("mdi:chevron-right", size = 22.dp, tint = Muted)
            }
        }
    }
}

private fun classIcon(cls: String) = when (cls) {
    "album" -> "mdi:album"; "artist" -> "mdi:account-music"; "playlist" -> "mdi:playlist-music"; "podcast" -> "mdi:podcast"
    "channel" -> "mdi:radio"; "track", "music" -> "mdi:music-note"; "directory" -> "mdi:folder-music"; "movie", "video" -> "mdi:movie"
    else -> "mdi:music"
}

@Composable
private fun Search(id: String) {
    val ha = App.instance.ha
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var query by remember(id) { mutableStateOf("") }
    var results by remember(id) { mutableStateOf<Result<List<Item>>?>(null) }
    fun run() {
        val q = query.trim().takeIf { it.isNotEmpty() } ?: return
        focus.clearFocus()
        results = null
        scope.launch {
            results = runCatching {
                val r = ha.callService("media_player", "search_media", buildJsonObject { put("search_query", q) },
                    buildJsonObject { put("entity_id", id) }, returnResponse = true)
                val resp = ((r as? JsonObject)?.get("response") as? JsonObject)?.get(id) as? JsonObject
                (resp?.get("result") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::Item) }.orEmpty()
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text(stringResource(R.string.media_search_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run() }),
            trailingIcon = { IconTap("mdi:magnify", 22.dp) { run() } },
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Dash.colors.accent),
            shape = RoundedCornerShape(14.dp),
        )
        results?.fold(
            { list -> if (list.isEmpty()) Text(stringResource(R.string.media_no_results), style = TextStyle(fontSize = 14.sp, color = Muted)) else ItemList(id, list) {} },
            { Text(stringResource(R.string.media_browse_error), style = TextStyle(fontSize = 14.sp, color = Muted)) },
        )
    }
}

/** En cours et à suivre : file de Music Assistant si le lecteur en dépend, sinon le titre en cours seulement. */
@Composable
private fun Queue(id: String, e: EntityState?) {
    val ha = App.instance.ha
    val queue by produceState<JsonObject?>(null, id, e?.attrString("media_content_id")) {
        value = runCatching {
            val r = ha.callService("music_assistant", "get_queue", null, buildJsonObject { put("entity_id", id) }, returnResponse = true)
            ((r as? JsonObject)?.get("response") as? JsonObject)?.get(id) as? JsonObject
        }.getOrNull()
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val cur = queue?.get("current_item") as? JsonObject
        val next = queue?.get("next_item") as? JsonObject
        QueueLine(stringResource(R.string.media_now), cur?.let { (it["media_item"] as? JsonObject)?.s("name") ?: it.s("name") } ?: e?.attrString("media_title"),
            cur?.let { ((it["media_item"] as? JsonObject)?.get("artists") as? JsonArray)?.mapNotNull { a -> (a as? JsonObject)?.s("name") }?.joinToString(", ") } ?: e?.attrString("media_artist"),
            cur?.s("image")?.let { ha.resolveUrl(it) } ?: e?.attrString("entity_picture")?.let { ha.resolveUrl(it) })
        if (next != null) QueueLine(stringResource(R.string.media_up_next), (next["media_item"] as? JsonObject)?.s("name") ?: next.s("name"),
            ((next["media_item"] as? JsonObject)?.get("artists") as? JsonArray)?.mapNotNull { a -> (a as? JsonObject)?.s("name") }?.joinToString(", "),
            next.s("image")?.let { ha.resolveUrl(it) })
        else if (queue == null) Text(stringResource(R.string.media_no_queue), style = TextStyle(fontSize = 13.sp, color = Muted))
        queue?.let {
            val total = (it["items"] as? JsonPrimitive)?.content
            if (total != null) Text(stringResource(R.string.media_queue_count, total), style = TextStyle(fontSize = 13.sp, color = Muted))
        }
    }
}

@Composable
private fun QueueLine(label: String, title: String?, artist: String?, image: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Artwork(image, 56.dp)
        Column(Modifier.padding(start = 14.dp)) {
            Text(label.uppercase(), style = TextStyle(fontSize = 11.sp, color = Dash.colors.accent, fontWeight = FontWeight.SemiBold))
            Text(title ?: "—", style = TextStyle(fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            artist?.let { Text(it, style = TextStyle(fontSize = 13.sp, color = Muted), maxLines = 1) }
        }
    }
}
