package fr.pergens.hamacs.ui

import android.content.Context
import android.media.MediaCodecList
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import fr.pergens.hamacs.App
import fr.pergens.hamacs.R
import fr.pergens.hamacs.config.BlockOverrides
import fr.pergens.hamacs.config.i
import fr.pergens.hamacs.config.objs
import fr.pergens.hamacs.config.s
import fr.pergens.hamacs.config.strs
import fr.pergens.hamacs.ha.EntityState
import fr.pergens.hamacs.ui.components.HaIcon
import fr.pergens.hamacs.ui.components.tapHold
import fr.pergens.hamacs.ui.theme.Dash
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import fr.pergens.hamacs.ha.history
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.math.ceil
import kotlin.math.min

/**
 * Mur de caméras : toutes les caméras d'un onglet (ou de tous) en plein écran, en grille. [page] null = toutes.
 * [focus] : caméra affichée en grand, les autres en vignettes à côté (sonnette, événement) ; [only] : caméras à
 * afficher à la place de celles des onglets ; [autoSeconds] : fermeture propre à l'ouverture (événement) ; [at] :
 * heure d'ouverture (un contact après elle annule la fermeture d'un événement).
 */
data class WallRequest(
    val page: String? = null,
    val focus: String? = null,
    val only: List<String>? = null,
    val autoSeconds: Int? = null,
    val at: Long = System.currentTimeMillis(),
)

/** Alimentation d'une caméra : direct permanent sur secteur, capture sinon (direct au toucher ou sur mouvement). */
enum class CameraPower(val key: String) { Mains("mains"), Solar("solar"), Battery("battery") }

/** Onglet de caméras du mur. */
class WallPage(val id: String, val icon: String?, val label: String?, val cams: List<JsonObject>)

object CameraWall {
    /** Clé des réglages du mur (mode édition : bouton de l'accueil). */
    const val KEY = "wall"

    /** Onglets du premier bloc d'onglets de caméras de l'agencement (vide s'il n'y en a pas). */
    fun pages(dashboard: JsonElement): List<WallPage> {
        val switcher = findTabs(dashboard) ?: return emptyList()
        return switcher.objs("pages").mapNotNull { p ->
            val id = p.s("id") ?: return@mapNotNull null
            WallPage(id, p.s("icon"), p.s("label"), p.objs("cards").filter { it.s("type") == "camera" && it.s("entity") != null })
        }
    }

    private fun findTabs(e: JsonElement): JsonObject? = when (e) {
        is JsonObject -> if (BlockOverrides.cameraTabsKey(e) != null) e else e.values.firstNotNullOfOrNull { findTabs(it) }
        is JsonArray -> e.firstNotNullOfOrNull { findTabs(it) }
        else -> null
    }

    /** Toutes les cartes caméra de l'agencement (une par entité). */
    fun allCameras(dashboard: JsonElement): List<JsonObject> {
        val out = LinkedHashMap<String, JsonObject>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> {
                    if (e.s("type") == "camera") e.s("entity")?.let { out.putIfAbsent(it, e) }
                    e.values.forEach(::walk)
                }
                is JsonArray -> e.forEach(::walk)
                else -> {}
            }
        }
        walk(dashboard)
        return out.values.toList()
    }

    /**
     * Nom affiché d'une caméra : celui de son éditeur (option `name`), sinon `name` de la carte, sinon celui de la
     * zone des détections (caméra liée par son entité, ou dont le texte reconnu commence son nom dans HA), sinon le
     * nom de l'entité.
     */
    fun displayName(card: JsonObject, blocks: Map<String, JsonObject>, states: Map<String, EntityState>, dashboard: JsonElement): String {
        val id = card.s("entity") ?: return card.s("name").orEmpty()
        blocks[id]?.s("name")?.takeIf { it.isNotBlank() }?.let { return it }
        card.s("name")?.takeIf { it.isNotBlank() }?.let { return it }
        val haName = states[id]?.name ?: id
        detectionNames(dashboard).firstOrNull { (cam, _) ->
            cam.entity == id || (cam.match.isNotBlank() && haName.startsWith(cam.match.trim(), true) &&
                (haName.length == cam.match.trim().length || !haName[cam.match.trim().length].isLetterOrDigit()))
        }?.second?.let { return it }
        return haName
    }

    /** Caméras nommées des zones de détections de l'agencement. */
    private fun detectionNames(dashboard: JsonElement): List<Pair<fr.pergens.hamacs.detections.DetectionCamera, String>> {
        val out = mutableListOf<Pair<fr.pergens.hamacs.detections.DetectionCamera, String>>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> {
                    if (e.s("type") == "camera_detections") fr.pergens.hamacs.detections.DetectionConfig(e).cameras
                        .filter { it.name.isNotBlank() }.forEach { out += it to it.name }
                    e.values.forEach(::walk)
                }
                is JsonArray -> e.forEach(::walk)
                else -> {}
            }
        }
        walk(dashboard)
        return out
    }

    /**
     * Batterie affichée sur la carte ([wall] = false) ou dans le mur ([wall] = true) : jamais sur secteur, sinon selon
     * l'option `battery_card` / `battery_wall` de l'éditeur de la caméra (affichée par défaut).
     */
    fun showBattery(card: JsonObject, blocks: Map<String, JsonObject>, states: Map<String, EntityState>, wall: Boolean): Boolean {
        if (card.s("battery") == null || power(card, blocks, states) == CameraPower.Mains) return false
        val opt = card.s("entity")?.let { blocks[it] }?.get(if (wall) "battery_wall" else "battery_card")
        return (opt as? kotlinx.serialization.json.JsonPrimitive)?.content != "false"
    }

    /** Alimentation choisie dans l'éditeur de la caméra, sinon [guessPower]. */
    fun power(card: JsonObject, blocks: Map<String, JsonObject>, states: Map<String, EntityState>): CameraPower {
        val chosen = card.s("entity")?.let { blocks[it]?.s("power") }
        return CameraPower.entries.firstOrNull { it.key == chosen } ?: guessPower(card, states)
    }

    /**
     * Sans batterie déclarée : secteur. Avec batterie, d'après le capteur d'état de charge du même appareil
     * (`sensor.<préfixe>_battery_state`, `…_charging`) sur les dernières 24 h (historique de HA, relu toutes les heures) :
     * jamais en décharge → secteur ; charge et décharge alternées → solaire ; décharge seulement → batterie. En attendant
     * l'historique, l'état actuel : « charge terminée » → secteur, « en charge » → solaire, sinon batterie.
     */
    fun guessPower(card: JsonObject, states: Map<String, EntityState>): CameraPower {
        val battery = card.s("battery") ?: return CameraPower.Mains
        val prefix = battery.substringAfter('.').substringBeforeLast('_')
        val sensor = states.values.firstOrNull { s ->
            val obj = s.entityId.substringAfter('.')
            obj.startsWith(prefix + "_") && ("battery_state" in obj || "charging" in obj || "charge_state" in obj)
        } ?: return CameraPower.Battery
        val now = sensor.state.lowercase()
        val seen = chargeHistory[sensor.entityId]
        requestHistory(sensor.entityId)
        if (seen == null) return when {
            now in FULL -> CameraPower.Mains
            now in CHARGING -> CameraPower.Solar
            else -> CameraPower.Battery
        }
        val all = seen + now
        val discharges = all.any { it in DISCHARGING }
        val charges = all.any { it in CHARGING || it in FULL }
        return when {
            !discharges -> CameraPower.Mains
            charges -> CameraPower.Solar
            else -> CameraPower.Battery
        }
    }

    private val FULL = setOf("chargecomplete", "charge_complete", "charged", "full", "plugged")
    private val CHARGING = setOf("charging", "on")
    private val DISCHARGING = setOf("discharging", "not_charging", "notcharging", "off", "unplugged")

    /** États de charge vus sur 24 h, par capteur (état Compose : la carte se met à jour à l'arrivée). */
    private val chargeHistory = androidx.compose.runtime.mutableStateMapOf<String, Set<String>>()
    private val historyAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun requestHistory(id: String) {
        val t = System.currentTimeMillis()
        val last = historyAt[id]
        if (last != null && t - last < 3_600_000L) return
        historyAt[id] = t
        val app = App.instance
        app.scope.launch {
            runCatching { app.ha.history(listOf(id), 24)[id].orEmpty().map { it.state.lowercase() }.toSet() }
                .onSuccess { seen -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { chargeHistory[id] = seen } }
                .onFailure { historyAt.remove(id) }
        }
    }

    private val prefs by lazy { App.instance.getSharedPreferences("video", Context.MODE_PRIVATE) }
    private val _learned by lazy { kotlinx.coroutines.flow.MutableStateFlow(prefs.getInt("wall_learned", 0)) }
    /** Limite apprise après un échec de décodeur (0 = aucune). */
    val learned: kotlinx.coroutines.flow.StateFlow<Int> get() = _learned

    /** Un flux de plus que la tablette ne sait décoder : la limite descend à [running] - 1 (au moins 1). */
    fun learnFailure(running: Int) {
        val next = (running - 1).coerceAtLeast(1)
        if (_learned.value in 1..next) return
        prefs.edit().putInt("wall_learned", next).apply()
        _learned.value = next
    }

    fun resetLearned() {
        prefs.edit().remove("wall_learned").apply()
        _learned.value = 0
    }

    /**
     * Directs simultanés estimés d'après le décodeur H.264 matériel : images par seconde atteignables en 720p
     * divisées par 30, bornées par son nombre d'instances ; entre 1 et 9 (4 si l'appareil ne dit rien).
     */
    val estimate: Int by lazy {
        runCatching {
            val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals("video/avc", true) } }
            val hw = infos.firstOrNull { if (android.os.Build.VERSION.SDK_INT >= 29) it.isHardwareAccelerated else !it.name.startsWith("OMX.google") && !it.name.startsWith("c2.android") }
                ?: infos.firstOrNull() ?: return@runCatching 4
            val caps = hw.getCapabilitiesForType("video/avc")
            val vc = caps.videoCapabilities
            val fps = runCatching { vc.getAchievableFrameRatesFor(1280, 720)?.upper }.getOrNull()
                ?: runCatching { vc.getSupportedFrameRatesFor(1280, 720).upper }.getOrNull() ?: 120.0
            min(caps.maxSupportedInstances, (fps / 30.0).toInt()).coerceIn(1, 9)
        }.getOrDefault(4)
    }

    /** Limite effective : réglage manuel, sinon l'estimation, réduite par la limite apprise. */
    fun limit(manual: Int): Int {
        if (manual > 0) return manual
        val l = learned.value
        return if (l > 0) min(estimate, l) else estimate
    }
}

/** Colonnes de la grille qui donnent les plus grandes cases 16:9 dans [w] × [h]. */
private fun bestColumns(n: Int, w: Float, h: Float): Int {
    var best = 1
    var bestArea = 0f
    for (c in 1..n) {
        val r = ceil(n / c.toFloat())
        val tileW = min(w / c, h / r * 16f / 9f)
        val area = tileW * tileW * 9f / 16f
        if (area > bestArea + 1f) { bestArea = area; best = c }
    }
    return best
}

@Composable
fun CameraWallDialog(request: WallRequest, onDismiss: () -> Unit) {
    val app = App.instance
    val state = LocalDashState.current
    val dash by app.config.dashboard.collectAsState()
    val blocks by app.config.blocks.collectAsState()
    val settings by app.config.settings.collectAsState()
    val states by app.ha.states.collectAsState()
    val learned by CameraWall.learned.collectAsState()
    val pages = remember(dash) { CameraWall.pages(dash) }
    var page by remember(request) { mutableStateOf(request.page) }
    var focus by remember(request) { mutableStateOf(request.focus) }
    var liveAll by remember(request) { mutableLongStateOf(0L) }

    val all = remember(dash) { CameraWall.allCameras(dash) }
    val cams: List<JsonObject> = remember(dash, page, request.only, focus) {
        val base = when {
            request.only != null -> (listOfNotNull(request.focus) + request.only).distinct().mapNotNull { id -> all.firstOrNull { it.s("entity") == id } }
            page != null -> pages.firstOrNull { it.id == page }?.cams.orEmpty()
            else -> pages.flatMap { it.cams }.distinctBy { it.s("entity") }.ifEmpty { all }
        }
        base
    }

    // Mouvement : direct pendant la détection puis `motion_delay` s (10 par défaut) ; une reprise pendant ce délai
    // le prolonge (pas de coupure sur un faux arrêt).
    val motionUntil = remember { mutableStateMapOf<String, Long>() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    LaunchedEffect(states, cams) {
        val t = System.currentTimeMillis()
        cams.forEach { c ->
            val id = c.s("entity") ?: return@forEach
            val on = c.strs("motion").any { states[it]?.state == "on" } ||
                (blocks[id]?.s("event_entity")?.let { states[it]?.state == "on" } == true)
            val cur = motionUntil[id]
            if (on) motionUntil[id] = Long.MAX_VALUE
            else if (cur == Long.MAX_VALUE) motionUntil[id] = t + c.i("motion_delay", 10) * 1000L
        }
    }
    val moving = cams.mapNotNull { it.s("entity") }.filter { (motionUntil[it] ?: 0L) > now }.toSet()

    // Directs : mouvement d'abord, puis « tout en direct » (sauf batterie), puis caméras sur secteur ; dans la limite.
    // `learned` lu ici pour recomposer quand la limite apprise change.
    val limit = if (learned >= 0) CameraWall.limit(settings.wallMaxLive) else 1
    val forced = liveAll > now
    val paused = state.player != null
    // Caméras qui devraient être en direct mais restent en capture faute de place (limite de directs atteinte).
    var capped: Set<String> = emptySet()
    val live: Set<String> = if (paused) emptySet() else {
        val ranked = cams.mapNotNull { c ->
            val id = c.s("entity") ?: return@mapNotNull null
            val power = CameraWall.power(c, blocks, states)
            val prio = when {
                id in moving -> 0
                id == focus -> 1
                // Secteur avant « tout en direct » : une caméra sur secteur ne cède jamais sa place à une autre forcée.
                power == CameraPower.Mains -> 2
                forced && power != CameraPower.Battery -> 3
                else -> null
            }
            prio?.let { id to it }
        }
        val sorted = ranked.sortedBy { it.second }.map { it.first }
        capped = sorted.drop(limit).toSet()
        sorted.take(limit).toSet()
    }

    // Fermeture : délai de l'événement, sinon celui du mur (ou du plein écran) ; jamais pendant une détection
    // ni pendant qu'une caméra est agrandie, le délai repartant ensuite.
    val seconds = request.autoSeconds ?: if (settings.wallSeconds < 0) settings.fullscreenSeconds else settings.wallSeconds
    LaunchedEffect(seconds, moving.isEmpty(), paused) {
        if (seconds > 0 && moving.isEmpty() && !paused) {
            delay(seconds * 1000L)
            // Événement : seulement si personne n'a touché l'écran depuis l'ouverture.
            if (request.autoSeconds == null || state.lastInteraction <= request.at) onDismiss()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ImmersiveWindow()
        Row(Modifier.fillMaxSize().background(Color.Black)) {
            // Colonne d'icônes : onglets, « Toutes », direct pour toutes, fermeture.
            Column(
                Modifier.fillMaxHeight().width(76.dp).background(Color(0xFF101012)).padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (request.only == null) {
                    pages.forEach { p -> RailButton(p.icon ?: "mdi:cctv", p.label, page == p.id && focus == null) { page = p.id; focus = null } }
                    if (pages.size > 1) RailButton("mdi:view-grid", stringResource(R.string.wall_all), page == null && focus == null) { page = null; focus = null }
                }
                Spacer(Modifier.weight(1f))
                val anyNotMains = cams.any { CameraWall.power(it, blocks, states) == CameraPower.Solar }
                if (anyNotMains || forced) RailButton(
                    if (forced) "mdi:play-circle" else "mdi:play-circle-outline", stringResource(R.string.wall_live_all), forced,
                ) { liveAll = if (forced) 0L else System.currentTimeMillis() + 120_000L }
                RailButton("mdi:close", null, false) { onDismiss() }
            }
            val shown = cams
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().padding(8.dp)) {
                val gap = 8.dp
                if (shown.isEmpty()) {
                    Text(stringResource(R.string.wall_empty), Modifier.align(Alignment.Center), color = Color.White)
                } else if (focus != null && shown.size > 1) {
                    // Une caméra en grand, les autres en vignettes à droite.
                    val big = shown.firstOrNull { it.s("entity") == focus } ?: shown.first()
                    val others = shown.filter { it !== big }
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        Box(Modifier.weight(3f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            Box(Modifier.aspectRatio(16f / 9f)) {
                                WallTile(big, big.s("entity") in live, big.s("entity") in moving, blocks, states, live.size, big.s("entity") in capped) { open(state, big) }
                            }
                        }
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically)) {
                            others.take(4).forEach { c ->
                                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                                    WallTile(c, c.s("entity") in live, c.s("entity") in moving, blocks, states, live.size, c.s("entity") in capped) { focus = c.s("entity") }
                                }
                            }
                        }
                    }
                } else {
                    val n = shown.size
                    val cols = bestColumns(n, maxWidth.value, maxHeight.value)
                    val rows = ceil(n / cols.toFloat()).toInt()
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically)) {
                        for (r in 0 until rows) {
                            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally)) {
                                for (c in 0 until cols) {
                                    val cam = shown.getOrNull(r * cols + c)
                                    // Case aux proportions de l'image (16:9), centrée : rien n'est rogné.
                                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                        if (cam != null) Box(Modifier.aspectRatio(16f / 9f)) {
                                            WallTile(cam, cam.s("entity") in live, cam.s("entity") in moving, blocks, states, live.size, cam.s("entity") in capped) { open(state, cam) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Caméra agrandie seule, avec le son (le mur reste dessous, ses directs en pause). */
private fun open(state: DashState, card: JsonObject) {
    val id = card.s("entity") ?: return
    state.openPlayer(card.s("fullscreen_url") ?: card.s("fullscreen_entity") ?: card.s("live_url") ?: id, card.s("live_url") ?: id)
}

@Composable
private fun RailButton(icon: String, label: String?, active: Boolean, onClick: () -> Unit) {
    val c = Dash.colors
    Column(
        Modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).tapHold({ onClick() }).padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(if (active) c.menuActiveButtonBackground else Color(0x22FFFFFF)),
            contentAlignment = Alignment.Center,
        ) { HaIcon(icon, size = 24.dp, tint = if (active) c.menuActiveButton else Color.White) }
        // Nom affiché seulement pour l'onglet actif (comme la barre d'onglets).
        if (active && label != null) Text(
            label, Modifier.padding(top = 4.dp).width(68.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontSize = 11.sp, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
        )
    }
}

/**
 * Case du mur : capture rafraîchie (secteur hors direct 10 s, solaire 15 s, batterie `refresh` de la carte), direct
 * muet par-dessus quand il est accordé. Un échec de décodeur abaisse la limite de directs.
 */
@Composable
private fun WallTile(
    card: JsonObject, live: Boolean, moving: Boolean, blocks: Map<String, JsonObject>, states: Map<String, EntityState>,
    running: Int, capped: Boolean, onTap: () -> Unit,
) {
    val state = LocalDashState.current
    val ctx = LocalContext.current
    val camId = card.s("entity") ?: return
    val cam = states[camId]
    val power = CameraWall.power(card, blocks, states)
    val refresh = when (power) {
        CameraPower.Mains -> 10
        CameraPower.Solar -> 15
        CameraPower.Battery -> card.i("refresh", 60)
    }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) { while (true) { delay(refresh * 1000L); tick++ } }
    var lastKey by remember { mutableStateOf<String?>(null) }
    // Capture en échec (HA répond parfois 500 pour une caméra endormie) : nouveaux essais après 5 puis 15 s, l'écran
    // d'attente restant affiché ; « hors ligne » seulement ensuite, le rafraîchissement continuant d'essayer.
    var shown by remember { mutableStateOf(false) }
    var fails by remember { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(fails) {
        if (shown) return@LaunchedEffect
        when (fails) {
            1 -> { delay(5_000); retry++ }
            2 -> { delay(15_000); retry++ }
        }
    }
    val url = state.ha.resolveUrl("/api/camera_proxy/$camId")?.let { "$it?t=w$tick-$retry" }
    var liveShown by remember(live) { mutableStateOf(false) }
    var liveFailed by remember(live) { mutableStateOf(false) }
    // Direct en échec (caméra lente à démarrer, pont qui se reconnecte) : 2 nouveaux essais à 3 s, comme la carte ;
    // format illisible : pas de nouvel essai (la limite de directs a été abaissée).
    var liveErrors by remember(live) { mutableIntStateOf(0) }
    var liveAttempt by remember(live) { mutableIntStateOf(0) }
    var decoderFailed by remember(live) { mutableStateOf(false) }
    LaunchedEffect(liveErrors) {
        when {
            liveErrors == 0 -> {}
            decoderFailed || liveErrors > 2 -> liveFailed = true
            else -> { delay(3_000); liveShown = false; liveAttempt++ }
        }
    }
    val liveAlpha by animateFloatAsState(if (live && liveShown && !liveFailed) 1f else 0f, tween(400), label = "wall-live")
    // Liseré de mouvement : apparaît et s'efface en fondu.
    val motionAlpha by animateFloatAsState(if (moving) 1f else 0f, tween(500), label = "wall-motion")
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.fillMaxSize().clip(shape).background(Color(0xFF1A1A1E))
            .tapHold({ onTap() }),
    ) {
        if (cam?.isUnavailable == true) {
            fr.pergens.hamacs.ui.cards.CameraPlaceholder(fr.pergens.hamacs.ui.cards.CameraPlaceholderKind.Offline, Modifier.fillMaxSize())
        } else {
            if (!shown) fr.pergens.hamacs.ui.cards.CameraPlaceholder(
                if (fails >= 3) fr.pergens.hamacs.ui.cards.CameraPlaceholderKind.Offline else fr.pergens.hamacs.ui.cards.CameraPlaceholderKind.Loading,
                Modifier.fillMaxSize(),
            )
            // Dernière capture réussie dessous (mémoire de Coil) : un rafraîchissement raté ne vide pas la case.
            lastKey?.let { k ->
                AsyncImage(ImageRequest.Builder(ctx).data(k).memoryCacheKey(k).crossfade(false).build(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            AsyncImage(
                ImageRequest.Builder(ctx).data(url).memoryCacheKey(url).placeholderMemoryCacheKey(lastKey).crossfade(false)
                    .listener(
                        onSuccess = { _, _ -> lastKey = url; shown = true; fails = 0 },
                        onError = { _, r ->
                            fails++
                            fr.pergens.hamacs.CrashGuard.note(
                                ctx, "wall:$camId:${r.throwable.javaClass.simpleName}", "Capture de caméra (mur)",
                                "source : $camId\nerreur : ${r.throwable.message}", r.throwable,
                            )
                        },
                    ).build(),
                cam?.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            )
            if (live && !liveFailed) androidx.compose.runtime.key(liveAttempt) {
                fr.pergens.hamacs.ui.cards.InlineLive(
                    card.s("live_url"), camId, Modifier.fillMaxSize().graphicsLayer { alpha = liveAlpha },
                    onFirstFrame = { liveShown = true },
                    onError = { liveErrors++ },
                    onDecoderError = { decoderFailed = true; CameraWall.learnFailure(running) },
                )
            }
        }
        // Nom en bas, sur un dégradé ; badge DIRECT quand le flux est affiché.
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000))))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (moving) HaIcon("mdi:motion-sensor", size = 16.dp, tint = Dash.colors.accent)
                val dash by App.instance.config.dashboard.collectAsState()
                val name = remember(card, blocks, cam?.name, dash) { CameraWall.displayName(card, blocks, states, dash) }
                Text(
                    name, Modifier.padding(start = if (moving) 6.dp else 0.dp),
                    style = TextStyle(fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Medium), maxLines = 1,
                )
            }
        }
        // Liseré de mouvement, par-dessus la vidéo : trait sombre large dessous (ombre, lisible sur une image claire),
        // trait de la couleur d'accent dessus ; fondu à l'apparition et à la disparition.
        if (motionAlpha > 0f) {
            val accent = Dash.colors.accent
            androidx.compose.foundation.Canvas(Modifier.matchParentSize().graphicsLayer { alpha = motionAlpha }) {
                val r = 12.dp.toPx()
                val shadowW = 7.dp.toPx()
                val lineW = 2.5.dp.toPx()
                drawRoundRect(
                    Color(0x73000000), topLeft = androidx.compose.ui.geometry.Offset(shadowW / 2, shadowW / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - shadowW, size.height - shadowW),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r - shadowW / 2),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(shadowW),
                )
                drawRoundRect(
                    accent, topLeft = androidx.compose.ui.geometry.Offset(lineW / 2, lineW / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - lineW, size.height - lineW),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r - lineW / 2),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(lineW),
                )
            }
        }
        // Devrait être en direct mais la limite de directs simultanés est atteinte : capture, avec un badge discret.
        if (capped) Row(
            Modifier.align(Alignment.TopStart).padding(8.dp).background(Color(0xB3000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HaIcon("mdi:image-outline", size = 13.dp, tint = Color.White)
            Text(stringResource(R.string.wall_capped), Modifier.padding(start = 5.dp), style = TextStyle(fontSize = 11.sp, color = Color.White))
        }
        // Batterie (réglage « dans le mur » de la caméra, jamais sur secteur).
        val battery = card.s("battery")?.let { states[it] }
        if (battery != null && !battery.isUnavailable && CameraWall.showBattery(card, blocks, states, wall = true)) {
            fr.pergens.hamacs.ui.cards.BatteryBadge(
                battery.numeric ?: 0.0,
                Modifier.align(Alignment.TopEnd).padding(top = fr.pergens.hamacs.ui.cards.BATTERY_MARGIN, end = fr.pergens.hamacs.ui.cards.BATTERY_MARGIN),
            )
        }
        if (live && liveShown && !liveFailed) Row(
            Modifier.align(Alignment.TopStart).padding(8.dp).background(Color(0xCCE53935), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).background(Color.White, CircleShape))
            Text(stringResource(R.string.camera_live), Modifier.padding(start = 6.dp), style = TextStyle(fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold))
        }
    }
}

/** Fenêtre de dialogue en vrai plein écran (barres système masquées, encoche comprise). */
@Composable
internal fun ImmersiveWindow() {
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view) {
        (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { w ->
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            w.attributes = w.attributes.apply {
                if (android.os.Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            androidx.core.view.WindowInsetsControllerCompat(w, w.decorView).apply {
                hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose { }
    }
}

/** Bouton rond du mur de caméras (accueil). En mode édition, il reste visible même masqué, pour le réglage. */
@Composable
fun WallHomeButton(modifier: Modifier) {
    val app = App.instance
    val state = LocalDashState.current
    val blocks by app.config.blocks.collectAsState()
    val dash by app.config.dashboard.collectAsState()
    val hasCams = remember(dash) { CameraWall.pages(dash).any { it.cams.isNotEmpty() } }
    val shown = blocks[CameraWall.KEY]?.let { (it["home_button"] as? kotlinx.serialization.json.JsonPrimitive)?.content != "false" } ?: true
    if (!hasCams || (!shown && !state.editMode)) return
    val shape = CircleShape
    Box(
        modifier.size(56.dp).graphicsLayer { alpha = if (shown) 1f else 0.4f }.clip(shape)
            .background(Dash.colors.menuBackground, shape)
            .then(if (state.editMode) Modifier.border(1.dp, Dash.colors.accent, shape) else Modifier)
            .tapHold({ if (!state.editInstead(EditTarget.Wall())) state.wall = WallRequest() }),
        contentAlignment = Alignment.Center,
    ) { HaIcon("mdi:cctv", size = 26.dp, tint = Color.White) }
}
