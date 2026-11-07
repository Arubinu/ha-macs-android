package fr.pergens.hamacs.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import fr.pergens.hamacs.R
import fr.pergens.hamacs.config.b
import fr.pergens.hamacs.config.i
import fr.pergens.hamacs.config.d
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import fr.pergens.hamacs.config.o
import fr.pergens.hamacs.config.objs
import fr.pergens.hamacs.config.s
import fr.pergens.hamacs.ha.cameraStreamUrl
import fr.pergens.hamacs.ui.cards.RenderCard
import fr.pergens.hamacs.ui.components.HaIcon
import fr.pergens.hamacs.ui.components.tapHold
import fr.pergens.hamacs.ui.theme.Dash
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.math.roundToInt

@Composable
fun Overlays(dashboard: JsonObject) {
    val state = LocalDashState.current
    state.popup?.let { id -> dashboard.o("popups")?.o(id)?.let { PopupDialog(it) { state.popup = null } } }
    state.moreInfo?.let { MoreInfoDialog(it) { state.moreInfo = null } }
    // Mur avant le lecteur : une caméra agrandie depuis le mur s'ouvre par-dessus.
    state.wall?.let { CameraWallDialog(it) { state.wall = null } }
    state.player?.let { PlayerDialog(it, state.playerFallback) { state.player = null; state.playerFallback = null } }
    state.pin?.let { req -> PinDialog(req) { state.pin = null } }
    if (state.kioskMenu) KioskMenu { state.kioskMenu = false }
    state.editTarget?.let { t ->
        if (t is EditTarget.Card) fr.pergens.hamacs.ui.cards.alerts.AlertTickerEditor(t.key) { state.editTarget = null }
        else BlockEditSheet(t) {
            // Carte ouverte depuis un carrousel : on y revient, un instant après (le toucher qui ferme ne doit
            // pas atteindre la fenêtre qui réapparaît).
            val back = state.editStack.removeLastOrNull()
            state.editTarget = null
            if (back != null) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ state.editTarget = back }, 200)
        }
    }
}

/** Bandeau du mode édition, en haut au centre. */
@Composable
fun EditBanner(onDone: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier
            .clip(shape)
            .background(Color(0xF0202024), shape)
            .border(1.dp, Dash.colors.accent.copy(alpha = 0.6f), shape)
            .padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HaIcon("mdi:pencil", size = 18.dp, tint = Dash.colors.accent)
        Text(
            stringResource(R.string.edit_banner), Modifier.padding(start = 10.dp, end = 16.dp),
            style = TextStyle(fontSize = 14.sp, color = Color.White),
        )
        Box(
            Modifier.clip(RoundedCornerShape(18.dp)).background(Dash.colors.accent).tapHold(onDone)
                .heightIn(min = 36.dp).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.edit_done), style = TextStyle(fontSize = 14.sp, color = Color.Black, fontWeight = FontWeight.Medium)) }
    }
}

/** Réglages propres à un bloc (mode édition). Les réglages globaux restent dans la page Réglages. */
@Composable
private fun BlockEditSheet(target: EditTarget, onDismiss: () -> Unit) {
    val app = fr.pergens.hamacs.App.instance
    val raw by app.config.rawBlocks.collectAsState()
    val source by app.config.sourceDashboard.collectAsState()
    // Clés d'enregistrement fixées à l'ouverture : l'entité d'origine du bloc, même si elle a été remplacée.
    val objKey = remember(target) { app.config.resolveKey((target as? EditTarget.Graph)?.entity ?: target.key) }
    val optionKey = remember(target) { app.config.resolveKey((target as? EditTarget.Member)?.selector ?: target.key) }
    val graphType = (target as? EditTarget.Graph)?.key?.split(':')?.getOrNull(1)
    // Entité affichée en titre : celle actuellement utilisée (personne pour un membre).
    val set = raw[objKey]?.get("set") as? JsonObject
    val titleField = if (target is EditTarget.Member) "person" else "entity"
    val original = remember(source, objKey, graphType) { fr.pergens.hamacs.config.BlockOverrides.find(source, objKey, graphType) }
    // Graphique multi-courbes : la carte (et son nom) contient l'entité dans sa liste entities.
    // Graphique multi-courbes : carte propre (clé mgc:…), titre = nom affiché (éventuellement modifié).
    val multiGraph = original != null && original["entities"] != null
    val graphTitle = if (multiGraph) set?.s("name") ?: original?.s("name") else null
    val entityId = set?.s(titleField) ?: original?.s(titleField) ?: objKey
    val e = entity(entityId)
    val options = raw[optionKey]
    // Valeurs par défaut lues dans la config, pour afficher l'état réel avant toute surcharge.
    val configProtected = remember(source, optionKey) { configHasPin(source, optionKey) }
    val configAlwaysLive = remember(source, optionKey) { configFlag(source, optionKey, "always_live") }

    Sheet(onDismiss, 0.42f) {
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-11).dp).size(48.dp).clip(CircleShape).tapHold(onDismiss),
            contentAlignment = Alignment.Center,
        ) { HaIcon("mdi:close", size = 26.dp, tint = Color(255, 255, 255, 200)) }
        // En-tête fixe (titre, croix) ; seul le contenu défile, sans passer sous la croix.
        Column {
            // Bloc Windy : titre « Windy », sans adresse ; page web générique : son domaine en sous-titre.
            val windy = target is EditTarget.Web && original?.s("type") == "windy"
            val carousel = target is EditTarget.Swipe
            // Onglet de caméras : titre = son nom.
            val tabLabel = (target as? EditTarget.Tab)?.let { t ->
                ((original?.get("pages") as? kotlinx.serialization.json.JsonArray)?.getOrNull(t.index) as? JsonObject)?.s("label")
            }
            // Pièce d'une grille : titre = son nom.
            val roomName = (target as? EditTarget.Room)?.let { r ->
                ((original?.get("rooms") as? kotlinx.serialization.json.JsonArray)?.getOrNull(r.index) as? JsonObject)?.s("name") ?: ""
            }
            val webHost = (target as? EditTarget.Web)?.let { android.net.Uri.parse(original?.s("url") ?: "").host }
            Text(
                when { target is EditTarget.Pages -> stringResource(R.string.pages_title); target is EditTarget.Macs -> "M.A.C.S."; target is EditTarget.Clock -> stringResource(R.string.clock_title); target is EditTarget.Wall -> stringResource(R.string.wall_title); target is EditTarget.Detections -> stringResource(R.string.det_title); target is EditTarget.DetectionCamera -> detectionCameraTitle(target.key, target.index); target is EditTarget.Device -> deviceTitle(target.key, target.room, target.index) ?: ""; roomName != null -> roomName; target is EditTarget.Tabs -> stringResource(R.string.tabs_title); target is EditTarget.Tab -> tabLabel ?: ""; carousel -> stringResource(R.string.swipe_title); windy -> stringResource(R.string.windy_title); target is EditTarget.Web -> stringResource(R.string.web_title); else -> graphTitle ?: e?.name ?: entityId },
                Modifier.padding(end = 48.dp), style = TextStyle(fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Medium),
            )
            if (!windy && !multiGraph && !carousel && roomName == null && target !is EditTarget.Pages && target !is EditTarget.Macs && target !is EditTarget.Clock && target !is EditTarget.Wall && target !is EditTarget.Detections && target !is EditTarget.DetectionCamera && target !is EditTarget.Tabs && target !is EditTarget.Tab && target !is EditTarget.Device) Text(webHost ?: entityId, style = TextStyle(fontSize = 13.sp, color = Color(255, 255, 255, 140), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic))
            androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (target is EditTarget.Member) {
                // Avatar créé sur la tablette (photo détourée, filtre, cadrage).
                val memberId = target.key.removePrefix("member:")
                var editAvatar by remember { mutableStateOf(false) }
                val picture = e?.attrString("entity_picture")?.let { app.ha.resolveUrl(it) }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).tapHold({ editAvatar = true }).heightIn(min = 64.dp).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    fr.pergens.hamacs.ui.avatar.MemberAvatar(memberId, true, picture, null, Modifier.size(64.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        Text(stringResource(R.string.avatar_edit), style = TextStyle(fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Medium))
                        Text(stringResource(R.string.avatar_section_help), style = TextStyle(fontSize = 13.sp, color = Color(255, 255, 255, 150)))
                    }
                    HaIcon("mdi:chevron-right", size = 24.dp, tint = Color(255, 255, 255, 150))
                }
                if (editAvatar) fr.pergens.hamacs.ui.avatar.AvatarEditor(memberId, e?.name ?: memberId, picture) { editAvatar = false }
            }
            if (target is EditTarget.Entity || target is EditTarget.Camera) {
                EditSwitch(
                    stringResource(R.string.edit_protect), stringResource(R.string.edit_protect_help),
                    options?.b("protected") ?: configProtected,
                ) { app.config.setBlockOption(optionKey, "protected", it.takeIf { v -> v != configProtected }) }
            }
            if (target is EditTarget.Member) {
                // Membre favori : un seul, enregistré sur la carte des membres.
                val memberId = target.key.removePrefix("member:")
                EditSwitch(stringResource(R.string.member_favorite), stringResource(R.string.member_favorite_help), options?.s("favorite") == memberId) {
                    app.config.setBlockValue(optionKey, "favorite", if (it) JsonPrimitive(memberId) else null)
                }
                // Code global (changer de membre, quel qu'il soit) ; sinon, code propre à ce membre.
                val all = options?.b("protected") ?: configProtected
                EditSwitch(stringResource(R.string.member_protect_all), stringResource(R.string.member_protect_all_help), all) {
                    app.config.setBlockOption(optionKey, "protected", it.takeIf { v -> v != configProtected })
                }
                if (!all) {
                    val memberKey = remember(target) { app.config.resolveKey(target.key) }
                    val one = raw[memberKey]?.b("protected") == true
                    EditSwitch(
                        stringResource(R.string.member_protect_one), stringResource(R.string.member_protect_one_help),
                        one,
                    ) { app.config.setBlockOption(memberKey, "protected", it.takeIf { v -> v }) }
                    // Code propre au membre (sinon le code d'accès).
                    if (one) MemberCodeEditor(memberId)
                }
            }
            if (target is EditTarget.Graph) {
                val current = (options?.get("height") as? JsonPrimitive)?.content?.toDoubleOrNull()?.toFloat() ?: target.defaultHeight.toFloat()
                var value by remember(target.key, current) { mutableFloatStateOf(current) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.graph_height), style = TextStyle(fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Medium))
                        Text(stringResource(R.string.graph_height_help), style = TextStyle(fontSize = 13.sp, color = Color(255, 255, 255, 150)))
                    }
                    Text("${value.toInt()} dp", style = TextStyle(fontSize = 15.sp, color = Dash.colors.accent, fontWeight = FontWeight.SemiBold))
                }
                Slider(
                    value = value, onValueChange = { value = (it / 5f).roundToInt() * 5f }, valueRange = 100f..600f,
                    onValueChangeFinished = {
                        // Valeur égale à celle de dashboard.json : la surcharge est retirée.
                        app.config.setBlockValue(optionKey, "height", value.toInt().takeIf { it != target.defaultHeight }?.let { JsonPrimitive(it) })
                    },
                    colors = SliderDefaults.colors(thumbColor = Dash.colors.accent, activeTrackColor = Dash.colors.accent),
                )
            }
            if (target is EditTarget.Camera) {
                EditSwitch(
                    stringResource(R.string.edit_always_live), stringResource(R.string.edit_always_live_help),
                    options?.b("always_live") ?: configAlwaysLive,
                ) { app.config.setBlockOption(optionKey, "always_live", it.takeIf { v -> v != configAlwaysLive }) }
                // Nom affiché (mur de caméras) et alimentation (direct permanent sur secteur, capture sinon).
                CameraNameField(optionKey, options, original)
                CameraPowerFields(optionKey, options, original)
                // Sonnette, détecteur : ouverture en plein écran quand une entité se déclenche.
                CameraEventFields(optionKey, options)
            }
            when { target is EditTarget.Pages -> PagesFields(objKey); target is EditTarget.Macs -> MacsFields(); target is EditTarget.Clock -> ClockFields(); target is EditTarget.Wall -> WallFields(); target is EditTarget.Detections -> DetectionFields(objKey); target is EditTarget.DetectionCamera -> DetectionCameraFields(objKey, target.index, onDismiss); target is EditTarget.Device -> DeviceFields(objKey, target.room, target.index, onDismiss); target is EditTarget.Tabs -> TabsFields(objKey); target is EditTarget.Tab -> TabFields(objKey, target.index); target is EditTarget.Room -> RoomFields(objKey, target.index, onDismiss); carousel -> CarouselFields(objKey); target is EditTarget.Web -> WebFields(objKey); multiGraph -> MultiGraphFields(objKey); else -> { EntityFields(objKey, graphType); if (target is EditTarget.Entity && original?.s("type") == "media_player") MediaFields(objKey, JsonObject(original + (set ?: JsonObject(emptyMap())))) } }
            }
        }
    }
}

@Composable
internal fun EditSwitch(title: String, help: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).tapHold({ onChange(!checked) }).heightIn(min = 64.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = TextStyle(fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Medium))
            Text(help, style = TextStyle(fontSize = 13.sp, color = Color(255, 255, 255, 150)))
        }
        Switch(checked, onChange, colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Dash.colors.accent, checkedThumbColor = Color.Black))
    }
}

/** La config protège-t-elle déjà ce bloc (`"pin"` sur un bouton ou son action) ? */
private fun configHasPin(root: kotlinx.serialization.json.JsonElement, entity: String): Boolean = when (root) {
    is JsonObject -> (root.s("entity") == entity && (root["pin"] != null || root.o("tap_action")?.get("pin") != null)) ||
        root.values.any { configHasPin(it, entity) }
    is kotlinx.serialization.json.JsonArray -> root.any { configHasPin(it, entity) }
    else -> false
}

private fun configFlag(root: kotlinx.serialization.json.JsonElement, entity: String, flag: String): Boolean = when (root) {
    is JsonObject -> (root.s("entity") == entity && root.b(flag)) || root.values.any { configFlag(it, entity, flag) }
    is kotlinx.serialization.json.JsonArray -> root.any { configFlag(it, entity, flag) }
    else -> false
}

@Composable
internal fun Sheet(
    onDismiss: () -> Unit, widthFraction: Float = 0.6f, modifier: Modifier = Modifier,
    /** Fond par-dessus le fond de base, sur toute la fenêtre (ex. teinte d'une pochette). */
    overlay: androidx.compose.ui.graphics.Brush? = null,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(20.dp)
        Box(
            modifier.fillMaxWidth(widthFraction).clip(shape).background(Color(if (overlay != null) 0xFF222222 else 0xF2222222), shape)
                .then(if (overlay != null) Modifier.background(overlay, shape) else Modifier).padding(20.dp)
        ) { content() }
    }
}

/**
 * Menu du kiosque, après le code : réglages, sortie temporaire, paramètres Android, redémarrage et
 * extinction (ces deux derniers seulement si l'app est propriétaire de l'appareil).
 */
@Composable
private fun KioskMenu(onDismiss: () -> Unit) {
    val state = LocalDashState.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val activity = ctx as? android.app.Activity ?: return
    val kiosk = fr.pergens.hamacs.kiosk.Kiosk
    val owner = remember { kiosk.isDeviceOwner(ctx) }
    var info by remember { mutableStateOf<String?>(null) }
    val powerHelp = stringResource(R.string.kiosk_menu_power_help)
    Sheet(onDismiss, 0.4f) {
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-11).dp).size(48.dp).clip(CircleShape).tapHold(onDismiss),
            contentAlignment = Alignment.Center,
        ) { HaIcon("mdi:close", size = 26.dp, tint = Color(255, 255, 255, 200)) }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.kiosk_title), Modifier.padding(bottom = 8.dp), style = TextStyle(fontSize = 20.sp, color = Color.White))
            KioskItem("mdi:cog-outline", stringResource(R.string.kiosk_menu_settings), null) {
                onDismiss(); state.showSettings = true
            }
            KioskItem("mdi:lock-open-variant-outline", stringResource(R.string.kiosk_menu_exit), stringResource(R.string.kiosk_menu_exit_help)) {
                onDismiss(); kiosk.suspend(activity)
            }
            KioskItem("mdi:android", stringResource(R.string.kiosk_menu_android), null) {
                onDismiss(); kiosk.suspend(activity, android.content.Intent(android.provider.Settings.ACTION_SETTINGS))
            }
            if (owner) {
                KioskItem("mdi:restart", stringResource(R.string.kiosk_menu_reboot), null) { kiosk.reboot(ctx) }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    KioskItem("mdi:power", stringResource(R.string.kiosk_menu_power), info) {
                        if (kiosk.allowPowerMenu(ctx)) info = powerHelp
                    }
                }
            }
        }
    }
}

@Composable
private fun KioskItem(icon: String, title: String, help: String?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Color(255, 255, 255, 13), shape).tapHold(onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HaIcon(icon, size = 24.dp, tint = Dash.colors.accent)
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, style = TextStyle(fontSize = 16.sp, color = Color.White))
            help?.let { Text(it, Modifier.padding(top = 2.dp), style = TextStyle(fontSize = 13.sp, color = Color(255, 255, 255, 150))) }
        }
    }
}

/** Popup (bubble-card) : liste de cartes, fermeture automatique configurable. */
@Composable
private fun PopupDialog(popup: JsonObject, onDismiss: () -> Unit) {
    val autoClose = popup.i("auto_close_ms", 60_000)
    val state = LocalDashState.current
    LaunchedEffect(state.lastInteraction) {
        delay(autoClose.toLong())
        onDismiss()
    }
    // Taille adaptée à l'écran : toute la hauteur moins une marge, largeur déduite des proportions du
    // contenu (`content_aspect` = largeur / hauteur, 0,8 pour la télécommande).
    val conf = androidx.compose.ui.platform.LocalConfiguration.current
    val margin = popup.d("margin", 24.0).dp
    val padding = 20.dp
    val header = 42.dp
    val contentHeight = conf.screenHeightDp.dp - margin * 2 - padding * 2 - header
    val width = minOf(contentHeight * popup.d("content_aspect", 0.8).toFloat() + padding * 2, conf.screenWidthDp.dp * 0.9f)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier.width(width).clip(shape).background(Color(0xF2222222), shape).padding(padding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.height(header - 16.dp), verticalAlignment = Alignment.CenterVertically) {
                HaIcon(popup.s("icon"), size = 26.dp)
                Text(popup.s("name") ?: "", Modifier.padding(start = 12.dp).weight(1f), style = TextStyle(fontSize = 20.sp, color = Color.White))
                HaIcon("mdi:close", Modifier.tapHold(onDismiss), 26.dp)
            }
            popup.objs("cards").forEach { RenderCard(it) }
        }
    }
}

/** Détail minimal : état, bascule, luminosité pour les lumières. */
@Composable
private fun MoreInfoDialog(entityId: String, onDismiss: () -> Unit) {
    val state = LocalDashState.current
    val e = entity(entityId)
    val switchable = e != null && e.domain in setOf("light", "switch", "fan", "input_boolean", "media_player", "cover")
    Sheet(onDismiss, 0.4f) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // L'interrupteur suffit à indiquer l'état : pas de « on/off » en double.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e?.name ?: entityId, Modifier.weight(1f), style = TextStyle(fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Medium))
                if (switchable) {
                    Switch(
                        checked = e!!.isActive, onCheckedChange = { state.ha.toggle(entityId) },
                        colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Dash.colors.accent, checkedThumbColor = Color.Black),
                    )
                }
                Box(
                    Modifier.padding(start = 8.dp).size(48.dp).clip(CircleShape).tapHold(onDismiss),
                    contentAlignment = Alignment.Center,
                ) { HaIcon("mdi:close", size = 26.dp) }
            }
            if (!switchable) Text(e?.state ?: "—", style = TextStyle(fontSize = 15.sp, color = Color(255, 255, 255, 170)))
            if (e?.domain == "light" && e.isActive && e.attrList("supported_color_modes").any { it != "onoff" }) {
                var value by remember(entityId) { mutableFloatStateOf(((e.attrDouble("brightness") ?: 255.0) / 255.0 * 100).toFloat()) }
                // Même présentation que les curseurs des Réglages : libellé à gauche, valeur à droite.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.moreinfo_brightness_label), Modifier.weight(1f),
                        style = TextStyle(fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Medium),
                    )
                    Text("${value.toInt()} %", style = TextStyle(fontSize = 15.sp, color = Dash.colors.accent, fontWeight = FontWeight.SemiBold))
                }
                Slider(
                    value = value, onValueChange = { value = it }, valueRange = 1f..100f,
                    onValueChangeFinished = {
                        // Pas de nouveau code ici : l'ouverture du détail (perform) l'a déjà demandé si besoin.
                        state.ha.fireService("light", "turn_on",
                            data = buildJsonObject { put("brightness_pct", JsonPrimitive(value.toInt())) },
                            target = buildJsonObject { put("entity_id", JsonPrimitive(entityId)) })
                    },
                    colors = SliderDefaults.colors(thumbColor = Dash.colors.accent, activeTrackColor = Dash.colors.accent),
                )
            }
        }
    }
}

/** Capteurs de mouvement (`motion`) des cartes caméra dont l'entité ou un flux fait partie de [ids]. */
private fun cameraMotion(root: kotlinx.serialization.json.JsonElement, ids: Set<String>): List<String> {
    val out = mutableListOf<String>()
    fun walk(e: kotlinx.serialization.json.JsonElement) {
        when (e) {
            is JsonObject -> {
                if (e.s("type") == "camera" && listOf("entity", "fullscreen_entity", "fullscreen_url", "live_url").any { e.s(it) in ids }) {
                    when (val m = e["motion"]) {
                        is kotlinx.serialization.json.JsonArray -> m.forEach { (it as? JsonPrimitive)?.content?.let(out::add) }
                        is JsonPrimitive -> out += m.content
                        else -> {}
                    }
                }
                e.values.forEach(::walk)
            }
            is kotlinx.serialization.json.JsonArray -> e.forEach(::walk)
            else -> {}
        }
    }
    walk(root)
    return out.distinct()
}

/**
 * Lecteur plein écran avec le son : enregistrement mp4, flux RTSP/HLS direct (`live_url`, go2rtc…)
 * ou flux HLS d'une caméra Home Assistant ("camera.xxx").
 */
@OptIn(UnstableApi::class)
@Composable
private fun PlayerDialog(requested: String, fallback: String?, onDismiss: () -> Unit) {
    val state = LocalDashState.current
    val ctx = LocalContext.current
    // Flux lu : celui demandé, ou celui de la carte si la tablette ne sait pas décoder le premier (4K H.265…).
    // Flux déjà connu comme illisible par cette tablette : directement celui de la carte (gain de temps).
    val known = fallback != null && VideoCompat.isUnplayable(requested)
    var target by remember(requested) { mutableStateOf(if (known) fallback!! else requested) }
    var fellBack by remember(requested) { mutableStateOf(known) }
    // Nouveaux essais (caméra qui se réveille, pont qui se reconnecte) : 2 de plus, à 3 s d'intervalle, après une
    // erreur de lecture autre que le format, ou si aucune image n'est arrivée au bout de 25 s.
    var attempt by remember(target) { mutableIntStateOf(0) }
    var url by remember(target, attempt) { mutableStateOf<String?>(if (target.startsWith("camera.")) null else target) }
    var error by remember(target) { mutableStateOf<String?>(null) }
    var retryPending by remember(target) { mutableStateOf(false) }
    LaunchedEffect(target, attempt) {
        if (target.startsWith("camera.")) {
            runCatching { url = state.ha.cameraStreamUrl(target) }.onFailure {
                error = it.message
                fr.pergens.hamacs.CrashGuard.note(ctx, "fullscreen:$target:stream", "Vidéo plein écran", "flux de $target refusé par Home Assistant", it)
            }
        }
    }
    // Autre décodeur (logiciel) essayé si celui de la tablette refuse le format, avant le flux de repli.
    val player = remember { ExoPlayer.Builder(ctx, androidx.media3.exoplayer.DefaultRenderersFactory(ctx).setEnableDecoderFallback(true)).setMediaSourceFactory(fr.pergens.hamacs.App.instance.mediaSources(ctx)).build() }
    val settings = fr.pergens.hamacs.App.instance.config.settings.collectAsState().value
    val fill = settings.fullscreenFill
    // Fermeture automatique (Réglages › Affichage) : retour au tableau de bord si personne ne l'a fermé. Jamais
    // pendant une détection de mouvement de cette caméra (capteurs `motion` de sa carte) : le délai repart après.
    val dash by fr.pergens.hamacs.App.instance.config.dashboard.collectAsState()
    val blocks by fr.pergens.hamacs.App.instance.config.blocks.collectAsState()
    val motionIds = remember(dash, blocks, requested, fallback) {
        val ids = setOfNotNull(requested, fallback)
        // Plus l'entité de « caméra sur événement » (sonnette, détecteur) réglée sur cette caméra.
        cameraMotion(dash, ids) + ids.mapNotNull { blocks[it]?.s("event_entity") }
    }
    val motionOn = motionIds.any { entity(it)?.state == "on" }
    LaunchedEffect(settings.fullscreenSeconds, motionOn) {
        if (settings.fullscreenSeconds > 0 && !motionOn) {
            delay(settings.fullscreenSeconds * 1000L)
            onDismiss()
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    // Erreurs notées dans le journal (Réglages › À propos) : le flux plein écran peut rester noir sans autre signe.
    fun note(what: String, e: Throwable? = null) = fr.pergens.hamacs.CrashGuard.note(
        ctx, "fullscreen:$target:$what", "Vidéo plein écran",
        "cible : ${fr.pergens.hamacs.CrashGuard.redact(target)}\nadresse : ${fr.pergens.hamacs.CrashGuard.redact(url)}\n$what", e,
    )
    var rendered by remember(target) { mutableStateOf(false) }
    LaunchedEffect(retryPending) {
        if (!retryPending) return@LaunchedEffect
        player.stop()
        delay(3_000)
        retryPending = false
        attempt++
    }
    DisposableEffect(player) {
        val l = object : androidx.media3.common.Player.Listener {
            override fun onRenderedFirstFrame() { rendered = true }
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                note("erreur ${e.errorCodeName} : ${e.message}", e)
                // Format refusé par la tablette : mémorisé pour les prochaines fois.
                if (VideoCompat.isDecoderError(e)) VideoCompat.mark(target)
                val decoder = VideoCompat.isDecoderError(e)
                if (!decoder && attempt < 2) retryPending = true
                else if (fallback != null && target != fallback) {
                    fellBack = true
                    target = fallback
                } else error = e.errorCodeName
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(target, attempt) {
        delay(25_000)
        if (!rendered && error == null && !retryPending) {
            note("aucune image au bout de 25 s (essai ${attempt + 1}, état ${player.playbackState}, format ${player.videoFormat?.sampleMimeType})")
            if (attempt < 2) retryPending = true else error = "TIMEOUT"
        }
    }
    // L'adresse peut rester la même d'un essai à l'autre : l'essai fait partie de la clé.
    LaunchedEffect(url, attempt) {
        url?.let {
            player.setMediaItem(MediaItem.fromUri(it))
            player.prepare()
            player.playWhenReady = true
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Le lecteur est une fenêtre à part : on y masque aussi les barres système (vrai plein écran).
        val view = androidx.compose.ui.platform.LocalView.current
        DisposableEffect(view) {
            (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { w ->
                w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                // Tout l'écran, place de la barre de navigation comprise (sinon une bande reste visible sur le côté).
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
        Box(Modifier.fillMaxSize().background(Color.Black).tapHold(onDismiss), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = {
                    (android.view.LayoutInflater.from(it).inflate(R.layout.player_texture, null) as PlayerView).apply { this.player = player }
                },
                // « Remplir » : l'image couvre tout l'écran, ce qui dépasse est rogné (aucune bande noire).
                // Sinon : l'image entière est affichée, avec des bandes si les proportions diffèrent.
                update = {
                    it.resizeMode = if (fill) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                },
                modifier = Modifier.fillMaxSize(),
            )
            // Chargement : dernière image de la caméra floutée, roue et message (au lieu d'un écran noir).
            androidx.compose.animation.AnimatedVisibility(
                !rendered && error == null, Modifier.fillMaxSize(),
                enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(400)),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val cam = listOf(target, requested, fallback).firstOrNull { it?.startsWith("camera.") == true }
                    val still = cam?.let { state.ha.resolveUrl(entity(it)?.attrString("entity_picture")) }
                    if (still != null) coil.compose.AsyncImage(
                        still, null, Modifier.fillMaxSize().blur(18.dp),
                        contentScale = if (fill) androidx.compose.ui.layout.ContentScale.Crop else androidx.compose.ui.layout.ContentScale.Fit,
                    )
                    Box(Modifier.fillMaxSize().background(Color(0x99000000)))
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        androidx.compose.material3.CircularProgressIndicator(color = Dash.colors.accent, strokeWidth = 3.dp, modifier = Modifier.size(44.dp))
                        Text(stringResource(R.string.player_loading), color = Color.White, fontSize = 16.sp)
                    }
                }
            }
            // Messages au-dessus de la vidéo (la vue du lecteur les cachait).
            error?.let { Text(stringResource(R.string.stream_unavailable, it), color = Color.White) }
            if (fellBack && error == null) Text(
                stringResource(R.string.player_hd_fallback),
                Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
                    .background(Color(0xB3000000), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
                color = Color.White, fontSize = 13.sp,
            )
            HaIcon("mdi:close", Modifier.align(Alignment.TopEnd).padding(24.dp).tapHold(onDismiss), 32.dp)
        }
    }
}

/** Clavier de code (restriction-card / alarme). */
@Composable
private fun PinDialog(req: PinRequest, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    // Blocage après plusieurs erreurs (codes vérifiés par l'app uniquement ; l'alarme est vérifiée par HA).
    val config = fr.pergens.hamacs.App.instance.config
    var lockedUntil by remember { mutableStateOf(if (req.expected != null) config.pinLockedUntil() else 0L) }
    var remaining by remember { mutableStateOf(0L) }
    LaunchedEffect(lockedUntil) {
        while (true) {
            remaining = (lockedUntil - System.currentTimeMillis()).coerceAtLeast(0L)
            if (remaining == 0L) break
            delay(250)
        }
    }
    val locked = remaining > 0L
    // Code incorrect : la fenêtre tremble dans tous les sens (façon « Wizz »), avec une vibration.
    val shakeX = remember { androidx.compose.animation.core.Animatable(0f) }
    val shakeY = remember { androidx.compose.animation.core.Animatable(0f) }
    var wizz by remember { mutableStateOf(0) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val amplitude = with(androidx.compose.ui.platform.LocalDensity.current) { 14.dp.toPx() }
    LaunchedEffect(wizz) {
        if (wizz == 0) return@LaunchedEffect
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        val step = androidx.compose.animation.core.tween<Float>(35, easing = androidx.compose.animation.core.LinearEasing)
        repeat(12) { i ->
            val k = 1f - i / 14f
            kotlinx.coroutines.coroutineScope {
                launch { shakeX.animateTo((if (i % 2 == 0) 1f else -1f) * amplitude * k * (0.6f + kotlin.random.Random.nextFloat() * 0.4f), step) }
                launch { shakeY.animateTo((kotlin.random.Random.nextFloat() * 2f - 1f) * amplitude * 0.6f * k, step) }
            }
        }
        kotlinx.coroutines.coroutineScope {
            launch { shakeX.animateTo(0f, step) }
            launch { shakeY.animateTo(0f, step) }
        }
    }
    Sheet(onDismiss, 0.32f, Modifier.graphicsLayer { translationX = shakeX.value; translationY = shakeY.value }) {
        // Croix de fermeture (48dp de zone tactile).
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-11).dp).size(48.dp).clip(CircleShape).tapHold(onDismiss),
            contentAlignment = Alignment.Center,
        ) { HaIcon("mdi:close", size = 26.dp, tint = Color(255, 255, 255, 200)) }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                req.title ?: stringResource(R.string.pin_default_title),
                Modifier.padding(top = 12.dp),
                style = TextStyle(fontSize = 18.sp, color = Color.White),
            )
            if (locked) {
                val s = ((remaining + 999) / 1000).toInt()
                Text(
                    stringResource(R.string.pin_locked, "%d:%02d".format(s / 60, s % 60)),
                    Modifier.heightIn(min = 34.dp), textAlign = TextAlign.Center,
                    style = TextStyle(fontSize = 16.sp, color = Color(0xFFFF5252), lineHeight = 20.sp),
                )
            } else Text(
                if (wrong) stringResource(R.string.pin_wrong) else "•".repeat(code.length).ifEmpty { " " },
                style = TextStyle(fontSize = 26.sp, color = if (wrong) Color(0xFFFF5252) else Color.White, letterSpacing = 6.sp),
            )
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✓")
            keys.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEach { k ->
                        Box(
                            Modifier.size(64.dp).alpha(if (locked) 0.35f else 1f).border(1.dp, Color(255, 255, 255, 60), CircleShape).clip(CircleShape)
                                .background(if (k == "✓") Dash.colors.firstButtonBackground else Color(255, 255, 255, 13))
                                .tapHold({
                                    if (locked) return@tapHold
                                    wrong = false
                                    when (k) {
                                        "⌫" -> code = code.dropLast(1)
                                        "✓" -> if (req.expected == null || req.expected == code) {
                                            if (req.expected != null) config.pinSucceeded()
                                            onDismiss(); req.onOk(code)
                                        } else {
                                            wrong = true; code = ""; wizz++
                                            config.pinFailed()
                                            lockedUntil = config.pinLockedUntil()
                                        }
                                        else -> if (code.length < 8) code += k
                                    }
                                }),
                            contentAlignment = Alignment.Center,
                        ) { Text(k, style = TextStyle(fontSize = 24.sp, color = Color.White)) }
                    }
                }
            }
        }
    }
}
