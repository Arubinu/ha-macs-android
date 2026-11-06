package fr.pergens.hamacs.ui.cards

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import fr.pergens.hamacs.R
import fr.pergens.hamacs.App
import fr.pergens.hamacs.config.Card
import fr.pergens.hamacs.config.b
import fr.pergens.hamacs.ui.EditTarget
import androidx.compose.runtime.collectAsState
import fr.pergens.hamacs.config.d
import fr.pergens.hamacs.config.i
import fr.pergens.hamacs.config.o
import fr.pergens.hamacs.config.s
import fr.pergens.hamacs.config.strs
import fr.pergens.hamacs.ha.browseMedia
import fr.pergens.hamacs.ha.cameraStreamUrl
import fr.pergens.hamacs.ui.LocalDashState
import fr.pergens.hamacs.ui.components.DashCard
import fr.pergens.hamacs.ui.components.HaIcon
import fr.pergens.hamacs.ui.components.editOutline
import fr.pergens.hamacs.ui.components.tapHold
import fr.pergens.hamacs.ui.entity
import fr.pergens.hamacs.ui.theme.Dash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun parseAspect(s: String?, default: Float = 16f / 9.15f): Float {
    if (s == null) return default
    val parts = s.split('/', ':').mapNotNull { it.trim().toFloatOrNull() }
    return if (parts.size == 2 && parts[1] > 0) parts[0] / parts[1] else default
}

/**
 * Caméra : instantané rafraîchi périodiquement ; passe en direct (muet) pendant `live_seconds` après un
 * appui, ou tant qu'un capteur `motion` est actif (+ `motion_delay`). Un appui pendant le direct ouvre
 * le plein écran avec le son. Remplace hash-timer-card + button-card + picture-entity.
 */
@Composable
fun CameraCard(card: Card, modifier: Modifier) {
    val state = LocalDashState.current
    val camId = card.s("entity")
    val cam = entity(camId)
    val battery = entity(card.s("battery"))
    val refresh = card.i("refresh", 60)
    val liveSeconds = card.i("live_seconds", 30)
    val motionDelay = card.i("motion_delay", 10)
    val motionOn = card.strs("motion").map { entity(it) }.any { it?.state == "on" }
    // Flux permanent (caméras filaires) : réglage du mode édition, sinon `always_live` de la config.
    val blocks by App.instance.config.blocks.collectAsState()
    val alwaysLive = camId?.let { blocks[it]?.b("always_live") } ?: card.b("always_live")

    var tick by remember { mutableIntStateOf(0) }
    /** Incrémenté par l'appui long : recharge l'instantané et relance le flux. */
    var reload by remember { mutableIntStateOf(0) }
    var lastKey by remember { mutableStateOf<String?>(null) }
    var snapshotShown by remember { mutableStateOf(false) }
    var snapshotFailed by remember { mutableStateOf(false) }
    var liveUntil by remember { mutableLongStateOf(0L) }
    var live by remember { mutableStateOf(false) }
    var liveShown by remember { mutableStateOf(false) }
    var liveFailed by remember { mutableStateOf(false) }

    LaunchedEffect(refresh) {
        while (true) {
            delay(refresh * 1000L)
            tick++
        }
    }
    // Direct pendant la détection, puis encore `motion_delay` secondes après la fin.
    LaunchedEffect(motionOn) {
        if (motionOn) liveUntil = Long.MAX_VALUE
        else if (liveUntil == Long.MAX_VALUE) liveUntil = System.currentTimeMillis() + motionDelay * 1000L
    }
    LaunchedEffect(liveUntil, alwaysLive) {
        if (alwaysLive) {
            live = true
            return@LaunchedEffect
        }
        live = liveUntil > System.currentTimeMillis()
        if (live && liveUntil != Long.MAX_VALUE) {
            delay(liveUntil - System.currentTimeMillis())
            live = false
        }
    }
    LaunchedEffect(live) {
        if (!live) {
            liveShown = false
            liveFailed = false
        }
    }

    val offline = cam == null || cam.isUnavailable
    // Images personnalisées facultatives ; sinon écrans d'attente / hors ligne dessinés et traduits.
    val loadingUrl = state.ha.resolveUrl(card.s("loading_image"))
    val offlineUrl = state.ha.resolveUrl(card.s("offline_image"))
    val errorUrl = state.ha.resolveUrl(card.s("error_image")) ?: offlineUrl
    val base = card.s("image_url") ?: camId?.let { "/api/camera_proxy/$it" }
    val url = state.ha.resolveUrl(base)?.let { u -> u + (if ('?' in u) "&" else "?") + "t=$tick-$reload" }
    val ctx = LocalContext.current
    val radius = card.d("radius")?.dp ?: Dash.shapes.roundedRadius

    // Chaque couche apparaît en fondu PAR-DESSUS la précédente, qui reste affichée en dessous :
    // attente → instantané → (hors ligne) → direct → (erreur de flux). Jamais de passage par le noir.
    val fade = tween<Float>(450)
    val snapshotAlpha by animateFloatAsState(if (snapshotShown) 1f else 0f, fade, label = "snap")
    val offlineAlpha by animateFloatAsState(if (offline || (snapshotFailed && !snapshotShown)) 1f else 0f, fade, label = "off")
    val liveAlpha by animateFloatAsState(if (live && liveShown) 1f else 0f, fade, label = "live")
    val errorAlpha by animateFloatAsState(if (live && liveFailed) 1f else 0f, fade, label = "err")

    DashCard(
        modifier.fillMaxWidth().aspectRatio(parseAspect(card.s("aspect_ratio")))
            .tapHold(
                onTap = {
                    if (state.editInstead(camId?.let { EditTarget.Camera(it) })) return@tapHold
                    if (camId == null) return@tapHold
                    // Caméra en erreur (hors ligne, capture ou flux en échec) : un toucher relance la vérification,
                    // comme l'appui long (nouvelle capture, et nouveau flux si le direct était affiché).
                    if (offline || (snapshotFailed && !snapshotShown) || (live && liveFailed)) {
                        snapshotShown = false
                        snapshotFailed = false
                        liveShown = false
                        liveFailed = false
                        reload++
                        return@tapHold
                    }
                    // Réglage « plein écran dès le premier appui », sinon : direct dans la carte, puis plein écran.
                    val fullscreen = App.instance.config.settings.value.tapFullscreen || (live && liveShown)
                    // Caméra protégée : le code est demandé avant d'afficher le direct.
                    state.guard(camId) {
                        if (fullscreen) state.player = card.s("fullscreen_url") ?: card.s("fullscreen_entity") ?: card.s("live_url") ?: camId
                        else liveUntil = maxOf(liveUntil, System.currentTimeMillis() + liveSeconds * 1000L)
                    }
                },
                onHold = {
                    if (card.o("hold_action") != null) state.perform(card.o("hold_action"), camId)
                    else {
                        // Rechargement : on revient à l'image d'attente puis on relance instantané et flux.
                        snapshotShown = false
                        snapshotFailed = false
                        liveShown = false
                        liveFailed = false
                        reload++
                    }
                },
            )
            .editOutline(state.editMode, RoundedCornerShape(radius)),
        radius = radius,
    ) {
        PlaceholderLayer(loadingUrl, CameraPlaceholderKind.Loading, Modifier.fillMaxSize())
        AsyncImage(
            model = ImageRequest.Builder(ctx)
                .data(url)
                .memoryCacheKey(url)
                .placeholderMemoryCacheKey(lastKey)
                .crossfade(false)
                .listener(
                    onSuccess = { _, _ ->
                        lastKey = url
                        snapshotShown = true
                        snapshotFailed = false
                    },
                    onError = { _, _ -> snapshotFailed = true },
                )
                .build(),
            contentDescription = cam?.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = snapshotAlpha },
        )
        if (offlineAlpha > 0f) {
            PlaceholderLayer(offlineUrl, CameraPlaceholderKind.Offline, Modifier.fillMaxSize().graphicsLayer { alpha = offlineAlpha })
        }
        if (live && !offline && camId != null) {
            key(reload) {
                InlineLive(
                    card.s("live_url"), camId,
                    Modifier.fillMaxSize().graphicsLayer { alpha = liveAlpha },
                    onFirstFrame = { liveShown = true },
                    onError = { liveFailed = true },
                )
            }
        }
        if (errorAlpha > 0f) {
            PlaceholderLayer(errorUrl, CameraPlaceholderKind.Offline, Modifier.fillMaxSize().graphicsLayer { alpha = errorAlpha })
        }
        if (live && !offline) {
            // Flux demandé mais pas encore affiché : le badge pulse en douceur ; fixe dès la première image.
            val waiting = !liveShown && !liveFailed
            val pulse = if (waiting) {
                androidx.compose.animation.core.rememberInfiniteTransition(label = "live").animateFloat(
                    1f, 0.25f,
                    androidx.compose.animation.core.infiniteRepeatable(
                        androidx.compose.animation.core.tween(700, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                        androidx.compose.animation.core.RepeatMode.Reverse,
                    ),
                    label = "live-pulse",
                ).value
            } else 1f
            val badgeAlpha by animateFloatAsState(pulse, tween(if (waiting) 0 else 300), label = "live-badge")
            Row(
                Modifier.align(Alignment.TopStart).padding(BADGE_MARGIN).graphicsLayer { alpha = badgeAlpha }
                    .background(Color(0xCCE53935), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).background(Color.White, CircleShape))
                Text(stringResource(R.string.camera_live), Modifier.padding(start = 6.dp),
                    style = TextStyle(fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold))
            }
        }
        if (battery != null && !battery.isUnavailable) {
            BatteryBadge(battery.numeric ?: 0.0, Modifier.align(Alignment.TopEnd).padding(top = BATTERY_MARGIN, end = BATTERY_MARGIN))
        }
    }
}

/** Image d'attente / d'erreur : celle de la config si fournie, sinon l'écran dessiné. */
@Composable
private fun PlaceholderLayer(url: String?, kind: CameraPlaceholderKind, modifier: Modifier) {
    if (url != null) AsyncImage(url, null, modifier, contentScale = ContentScale.Crop)
    else CameraPlaceholder(kind, modifier)
}

private val BADGE_MARGIN = 10.dp
/** Un pixel plus près des bords que le badge DIRECT (tablette à densité 1,5). */
private val BATTERY_MARGIN = 9.33.dp

/**
 * Batterie horizontale (borne à droite) au-dessus de son pourcentage. L'icône est recadrée sur sa
 * silhouette réelle : à 100 %, le bloc est à la même distance du bord haut que du bord droit.
 */
@Composable
private fun BatteryBadge(pct: Double, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(width = 25.dp, height = 12.dp), contentAlignment = Alignment.Center) {
            HaIcon(
                when {
                    pct >= 90 -> "mdi:battery"
                    pct >= 10 -> "mdi:battery-${(pct / 10).toInt() * 10}"
                    else -> "mdi:battery-outline"
                },
                Modifier.requiredSize(25.dp).rotate(90f),
                size = 25.dp,
                tint = when {
                    pct < 20 -> Color(0xFFE53935)
                    pct < 50 -> Color(0xFFFFA726)
                    else -> Color(0xFF4CD964)
                },
            )
        }
        // 5 px d'écart avec l'icône sur la tablette (densité 1,5).
        Text("${pct.toInt()} %", Modifier.padding(top = 3.33.dp), style = TextStyle(fontSize = 13.sp, color = Color.White, lineHeight = 15.sp))
    }
}

/**
 * Lecteur muet intégré à la carte : HLS de Home Assistant, ou `live_url` (RTSP/HLS go2rtc…).
 * Le flux n'est chargé que pendant l'affichage du direct (la caméra peut se rendormir ensuite).
 */
@OptIn(UnstableApi::class)
@Composable
private fun InlineLive(
    liveUrl: String?,
    camId: String,
    modifier: Modifier,
    onFirstFrame: () -> Unit,
    onError: () -> Unit,
    onEnded: () -> Unit = {},
) {
    val state = LocalDashState.current
    val ctx = LocalContext.current
    var url by remember(camId) { mutableStateOf(liveUrl) }
    val player = remember { ExoPlayer.Builder(ctx).build().apply { volume = 0f } }
    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(camId) {
        if (url == null) url = runCatching { state.ha.cameraStreamUrl(camId) }.getOrNull()
        if (url == null) onError()
    }
    LaunchedEffect(url) {
        url?.let {
            player.setMediaItem(MediaItem.fromUri(it))
            player.prepare()
            player.playWhenReady = true
        }
    }
    var rendered by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                rendered = true
                onFirstFrame()
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                fr.pergens.hamacs.CrashGuard.note(
                    ctx, "inline:$camId:${error.errorCodeName}", "Vidéo dans la carte",
                    "source : $camId\nadresse : ${fr.pergens.hamacs.CrashGuard.redact(url)}\nerreur ${error.errorCodeName} : ${error.message}", error,
                )
                onError()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onEnded()
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    // Pas de première image au bout de 25 s : flux considéré indisponible.
    LaunchedEffect(Unit) {
        delay(25_000)
        if (!rendered) {
            fr.pergens.hamacs.CrashGuard.note(
                ctx, "inline:$camId:timeout", "Vidéo dans la carte",
                "source : $camId\nadresse : ${fr.pergens.hamacs.CrashGuard.redact(url)}\naucune image au bout de 25 s (état ${player.playbackState}, format ${player.videoFormat?.sampleMimeType})",
            )
            onError()
        }
    }
    AndroidView(
        factory = {
            PlayerView(it).apply {
                this.player = player
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        modifier = modifier,
    )
}

/**
 * Captures des N dernières minutes, depuis le stockage choisi (médias de Home Assistant, partage SMB ou NFS,
 * dossier de la tablette, ou entités). Chaque capture est associée à sa vidéo (même nom, sinon la vidéo de la
 * même caméra qui démarre juste avant). Une capture à la fois, défilement automatique, points de position.
 * Appui : vidéo dans la carte, puis plein écran avec le son (ou plein écran direct selon le réglage).
 * En mode édition, un appui ouvre ses réglages.
 */
@Composable
fun CameraDetectionsCard(card: Card, modifier: Modifier) {
    val state = LocalDashState.current
    val cfg = remember(card) { fr.pergens.hamacs.detections.DetectionConfig(card) }
    val minutes = cfg.minutes
    var items by remember { mutableStateOf<List<fr.pergens.hamacs.detections.Detection>>(emptyList()) }
    // Mode entités : nouvelle analyse dès qu'une des entités change.
    val entityTick = if (cfg.type == "entities") cfg.cameras.mapNotNull { c -> c.entity?.let { entity(it)?.state } }.joinToString() else ""

    LaunchedEffect(cfg, entityTick) {
        while (true) {
            runCatching { items = fr.pergens.hamacs.detections.DetectionScanner.scan(cfg).detections }
            delay(60_000)
        }
    }

    val key = fr.pergens.hamacs.config.BlockOverrides.keyOf(card)
    DashCard(
        modifier.fillMaxWidth().aspectRatio(parseAspect(card.s("aspect_ratio")))
            .editOutline(state.editMode, RoundedCornerShape(card.d("radius")?.dp ?: Dash.shapes.roundedRadius)),
        background = Color(255, 255, 255, 10),
        radius = card.d("radius")?.dp ?: Dash.shapes.roundedRadius,
    ) {
        // Mode édition : la carte entière ouvre ses réglages (par-dessus les captures).
        if (state.editMode && key != null) {
            Box(Modifier.matchParentSize().zIndex(1f).tapHold({ state.editTarget = EditTarget.Detections(key) }))
        }
        if (items.isEmpty()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                HaIcon("mdi:camera-outline", size = 40.dp, tint = Color(255, 255, 255, 120))
                Text(
                    stringResource(R.string.detections_none, minutes),
                    Modifier.padding(top = 8.dp),
                    style = TextStyle(fontSize = 13.sp, color = Color(255, 255, 255, 140)),
                )
            }
            return@DashCard
        }
        val pager = rememberPagerState { items.size }
        /** Capture dont la vidéo est lue dans la carte (clé), ou null. */
        var playing by remember { mutableStateOf<String?>(null) }
        // Changer de capture arrête la vidéo en cours.
        LaunchedEffect(pager.currentPage) {
            if (playing != null && items.getOrNull(pager.currentPage)?.id != playing) playing = null
        }
        // Défilement automatique, suspendu pendant un glissement ou la lecture d'une vidéo.
        LaunchedEffect(items.size, pager.isScrollInProgress, playing) {
            if (items.size < 2 || pager.isScrollInProgress || playing != null) return@LaunchedEffect
            while (true) {
                delay(card.i("interval", 5000).toLong())
                pager.animateScrollToPage((pager.currentPage + 1) % items.size, animationSpec = tween(450))
            }
        }
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { items[it].id }) { page ->
            val d = items[page]
            val isPlaying = playing == d.id
            var videoShown by remember(d.id) { mutableStateOf(false) }
            var videoFailed by remember(d.id) { mutableStateOf(false) }
            // Capture et vidéo : adresse, `content://` ou fichier téléchargé (SMB/NFS), obtenus à la demande.
            val image by androidx.compose.runtime.produceState<String?>(null, d.id) { value = runCatching { d.image() }.getOrNull() }
            var videoUrl by remember(d.id) { mutableStateOf<String?>(null) }
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            LaunchedEffect(isPlaying) {
                if (!isPlaying) {
                    videoShown = false
                    videoFailed = false
                }
            }
            val videoAlpha by animateFloatAsState(if (isPlaying && videoShown && !videoFailed) 1f else 0f, tween(450), label = "vid")
            Box(
                Modifier.fillMaxSize().tapHold({
                    val video = d.video ?: return@tapHold
                    val fullscreen = App.instance.config.settings.value.tapFullscreen || (isPlaying && videoShown)
                    scope.launch {
                        val url = videoUrl ?: runCatching { video() }.onFailure {
                            fr.pergens.hamacs.CrashGuard.note(App.instance, "capture:${d.id}", "Vidéo d'une capture", "capture ${d.camera} ${d.time} : vidéo introuvable", it)
                        }.getOrNull()?.also { videoUrl = it } ?: return@launch
                        if (fullscreen) {
                            playing = null
                            state.player = url
                        } else playing = d.id
                    }
                })
            ) {
                AsyncImage(
                    model = image, contentDescription = d.camera,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
                // Vidéo muette par-dessus l'image, en fondu ; à la fin, retour à l'image.
                if (isPlaying && videoUrl != null) {
                    InlineLive(
                        videoUrl, d.camera,
                        Modifier.fillMaxSize().graphicsLayer { alpha = videoAlpha },
                        onFirstFrame = { videoShown = true },
                        onError = { videoFailed = true; playing = null },
                        onEnded = { playing = null },
                    )
                }
                Row(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0, 0, 0, 180))))
                        .padding(start = 12.dp, end = 12.dp, top = 18.dp, bottom = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (d.video != null) {
                        HaIcon(if (isPlaying) "mdi:play-circle" else "mdi:play-circle-outline", Modifier.padding(end = 6.dp), 18.dp)
                    }
                    Text(d.camera, Modifier.weight(1f), style = TextStyle(fontSize = 14.sp, color = Color.White))
                    Text(d.time.format(DateTimeFormatter.ofPattern("HH:mm")), style = TextStyle(fontSize = 14.sp, color = Color.White))
                }
            }
        }
        if (items.size > 1) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Fenêtre glissante de 9 points au plus, centrée sur la capture affichée.
                val window = 9
                val first = (pager.currentPage - window / 2).coerceIn(0, (items.size - window).coerceAtLeast(0))
                (first until minOf(items.size, first + window)).forEach { i ->
                    val selected = i == pager.currentPage
                    val w by animateDpAsState(if (selected) 16.dp else 6.dp, tween(250), label = "dot")
                    val col by animateColorAsState(if (selected) Color.White else Color(255, 255, 255, 110), tween(250), label = "dotc")
                    Box(Modifier.height(6.dp).width(w).clip(CircleShape).background(col))
                }
            }
        }
    }
}
