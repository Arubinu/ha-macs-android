package fr.arubinu.hadash.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import fr.arubinu.hadash.App
import fr.arubinu.hadash.R
import fr.arubinu.hadash.ha.ConnState
import fr.arubinu.hadash.ui.EntityPicker
import fr.arubinu.hadash.ui.components.HaIcon
import fr.arubinu.hadash.ui.theme.Dash
import kotlinx.coroutines.launch

// Même palette que la page des réglages.
private val Background = Color(0xFF0B0B0D)
private val NavBackground = Color(0xFF111114)
private val Surface = Color(0xFF19191D)
private val Outline = Color(0xFF2C2C33)
private val TextPrimary = Color(0xFFF2F2F5)
private val TextSecondary = Color(0xFFA3A3AD)
private val Success = Color(0xFF4CD964)
private val Danger = Color(0xFFFF6B6B)

private enum class Step(val icon: String, val title: Int, val desc: Int) {
    Welcome("mdi:hand-wave-outline", R.string.setup_step_welcome, R.string.setup_step_welcome_desc),
    Layout("mdi:view-dashboard-outline", R.string.setup_step_layout, R.string.setup_step_layout_desc),
    Rooms("mdi:floor-plan", R.string.setup_step_rooms, R.string.setup_step_rooms_desc),
    Members("mdi:account-group-outline", R.string.setup_step_members, R.string.setup_step_members_desc),
    Cameras("mdi:cctv", R.string.setup_step_cameras, R.string.setup_step_cameras_desc),
    Elements("mdi:tune-variant", R.string.setup_step_elements, R.string.setup_step_elements_desc),
    Done("mdi:check-circle-outline", R.string.setup_step_done, R.string.setup_step_done_desc),
}

/**
 * Assistant de premier démarrage : connexion, puis adaptation de l'agencement à la maison (pièces tirées des
 * zones, membres tirés des personnes, caméras réparties en onglets, éléments principaux). Tout est proposé
 * automatiquement et reste modifiable ensuite en mode édition.
 */
@Composable
fun SetupWizard(onImport: () -> Unit, importError: String?, onDone: () -> Unit) {
    val app = App.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val conn by app.ha.connection.collectAsState()
    var plan by remember { mutableStateOf<SetupPlan?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf(Step.Welcome) }
    /** Incrémenté à chaque choix, pour recomposer les vues du plan (objets mutables). */
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(conn) {
        if (conn == ConnState.Connected && plan == null && !scanning) {
            scanning = true
            plan = runCatching { HomeSetup.scan() }.getOrNull()
            scanning = false
        }
    }

    val p = plan
    // Étapes visibles : agencement conservé = directement la fin (recalculé à chaque choix, via `tick`).
    val steps = tick.let { _ -> null } ?: when {
        p == null -> listOf(Step.Welcome)
        p.keepLayout -> listOf(Step.Welcome, Step.Layout, Step.Done)
        else -> Step.entries.toList()
    }
    val index = steps.indexOf(step).coerceAtLeast(0)
    fun go(delta: Int) { steps.getOrNull(index + delta)?.let { step = it } }

    Row(Modifier.fillMaxSize().background(Background)) {
        // --- Étapes
        Column(Modifier.width(320.dp).fillMaxHeight().background(NavBackground).padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Dash.colors.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    HaIcon("mdi:home-assistant", size = 26.dp, tint = Dash.colors.accent)
                }
                Column(Modifier.padding(start = 12.dp)) {
                    Text(stringResource(R.string.setup_title), style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary))
                    Text(stringResource(R.string.setup_subtitle), style = TextStyle(fontSize = 13.sp, color = TextSecondary))
                }
            }
            Spacer(Modifier.height(18.dp))
            (if (p == null) Step.entries.toList() else steps).forEach { s ->
                val i = steps.indexOf(s)
                val done = i in 0 until index
                val current = s == step
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(if (current) Dash.colors.accent.copy(alpha = 0.14f) else Color.Transparent)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(if (done) Dash.colors.accent else if (current) Dash.colors.accent.copy(alpha = 0.25f) else Surface),
                        contentAlignment = Alignment.Center,
                    ) { HaIcon(if (done) "mdi:check" else s.icon, size = 18.dp, tint = if (done) Color.Black else if (current) Dash.colors.accent else TextSecondary) }
                    Text(
                        stringResource(s.title), Modifier.padding(start = 12.dp),
                        style = TextStyle(fontSize = 15.sp, color = if (current || done) TextPrimary else TextSecondary, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            // Passer : l'agencement fourni est gardé tel quel (assistant relançable depuis les réglages).
            Text(
                stringResource(R.string.setup_skip),
                Modifier.clip(RoundedCornerShape(10.dp)).clickable { onDone() }.padding(horizontal = 12.dp, vertical = 8.dp),
                style = TextStyle(fontSize = 14.sp, color = TextSecondary),
            )
        }

        // --- Contenu
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 48.dp, vertical = 36.dp)) {
            Text(stringResource(step.title), style = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary))
            Text(stringResource(step.desc), Modifier.padding(top = 6.dp), style = TextStyle(fontSize = 15.sp, color = TextSecondary, lineHeight = 21.sp))
            Spacer(Modifier.height(24.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(step, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) }, label = "setup") { s ->
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        tick
                        when (s) {
                            Step.Welcome -> WelcomeStep(conn, scanning, p, onImport, importError)
                            Step.Layout -> if (p != null) LayoutStep(p) { tick++ }
                            Step.Rooms -> if (p != null) RoomsStep(p) { tick++ }
                            Step.Members -> if (p != null) MembersStep(p) { tick++ }
                            Step.Cameras -> if (p != null) CamerasStep(p) { tick++ }
                            Step.Elements -> if (p != null) ElementsStep(p) { tick++ }
                            Step.Done -> if (p != null) DoneStep(p)
                        }
                    }
                }
            }
            // --- Navigation
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                if (index > 0) WizardButton(stringResource(R.string.setup_back), primary = false) { go(-1) }
                Spacer(Modifier.weight(1f))
                if (step == Step.Done) {
                    WizardButton(stringResource(R.string.setup_finish), primary = true) {
                        p?.let { HomeSetup.apply(ctx, it) }
                        onDone()
                    }
                } else {
                    WizardButton(stringResource(R.string.setup_next), primary = true, enabled = p != null) { go(1) }
                }
            }
        }
    }
}

// --- Étapes ------------------------------------------------------------------------------------------------------

@Composable
private fun WelcomeStep(conn: ConnState, scanning: Boolean, plan: SetupPlan?, onImport: () -> Unit, importError: String?) {
    val app = App.instance
    val settings by app.config.settings.collectAsState()
    Panel {
        Text(stringResource(R.string.setup_welcome_text), style = TextStyle(fontSize = 15.sp, color = TextPrimary, lineHeight = 22.sp))
    }
    if (conn != ConnState.Connected) {
        // Connexion à Home Assistant (première installation).
        var url by remember { mutableStateOf(settings.haUrl) }
        var token by remember { mutableStateOf(settings.haToken) }
        Panel {
            Text(stringResource(R.string.setup_connect), style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, color = TextPrimary))
            Field(url, { url = it }, stringResource(R.string.ha_url), stringResource(R.string.ha_url_hint))
            Field(token, { token = it }, stringResource(R.string.ha_token), null, secret = true)
            Text(stringResource(R.string.ha_token_help), style = TextStyle(fontSize = 13.sp, color = TextSecondary))
            Row(verticalAlignment = Alignment.CenterVertically) {
                WizardButton(stringResource(R.string.action_save_connect), primary = true, enabled = url.isNotBlank() && token.isNotBlank()) {
                    app.config.update { it.copy(haUrl = url.trim(), haToken = token.trim()) }
                }
                Spacer(Modifier.width(16.dp))
                StatusLine(
                    when (conn) { ConnState.Connecting -> TextSecondary; ConnState.AuthFailed -> Danger; else -> TextSecondary },
                    stringResource(when (conn) { ConnState.Connecting -> R.string.status_connecting; ConnState.AuthFailed -> R.string.status_auth_failed; else -> R.string.status_disconnected }),
                )
            }
        }
    } else {
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (scanning || plan == null) CircularProgressIndicator(Modifier.size(22.dp), color = Dash.colors.accent, strokeWidth = 2.5.dp)
                else HaIcon("mdi:check-circle", size = 24.dp, tint = Success)
                Text(
                    stringResource(if (plan == null) R.string.setup_scanning else R.string.setup_scanned),
                    Modifier.padding(start = 12.dp), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary),
                )
            }
            if (plan != null) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat("mdi:floor-plan", pluralStringResource(R.plurals.setup_count_rooms, plan.areas.size, plan.areas.size))
                    Stat("mdi:account-group-outline", pluralStringResource(R.plurals.setup_count_people, plan.persons.size, plan.persons.size))
                    Stat("mdi:cctv", pluralStringResource(R.plurals.setup_count_cameras, plan.cameras.size, plan.cameras.size))
                    Stat("mdi:tune-variant", pluralStringResource(R.plurals.setup_count_elements, plan.roles.count { it.entity != null }, plan.roles.count { it.entity != null }))
                }
            }
        }
    }
    // Sauvegarde exportée (autre tablette, réinstallation) : tout est repris et l'assistant se ferme.
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HaIcon("mdi:file-restore-outline", size = 24.dp, tint = Dash.colors.accent)
            Text(stringResource(R.string.setup_restore), Modifier.padding(start = 12.dp), style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, color = TextPrimary))
        }
        Text(stringResource(R.string.setup_restore_help), style = TextStyle(fontSize = 14.sp, color = TextSecondary, lineHeight = 20.sp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            WizardButton(stringResource(R.string.setup_restore_action), primary = false) { onImport() }
            if (importError != null) {
                Spacer(Modifier.width(16.dp))
                StatusLine(Danger, importError)
            }
        }
    }
}

@Composable
private fun LayoutStep(plan: SetupPlan, changed: () -> Unit) {
    Choice(
        "mdi:content-save-check-outline", stringResource(R.string.setup_layout_keep), stringResource(R.string.setup_layout_keep_desc, (plan.coverage * 100).toInt()),
        plan.keepLayout,
    ) { plan.keepLayout = true; changed() }
    Choice(
        "mdi:auto-fix", stringResource(R.string.setup_layout_adapt), stringResource(R.string.setup_layout_adapt_desc),
        !plan.keepLayout,
    ) { plan.keepLayout = false; changed() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoomsStep(plan: SetupPlan, changed: () -> Unit) {
    if (plan.areas.isEmpty()) { Empty(stringResource(R.string.setup_rooms_none)); return }
    SelectAll(plan.areas.all { it.selected }) { all -> plan.areas.forEach { it.selected = all }; changed() }
    val states = App.instance.ha.states.collectAsState().value
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        plan.areas.forEach { a ->
            Tile(a.selected, { a.selected = !a.selected; changed() }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Dash.colors.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                        HaIcon(a.icon, size = 24.dp, tint = Dash.colors.accent)
                    }
                    Text(a.name, Modifier.weight(1f).padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, color = TextPrimary))
                }
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    a.temperature?.let { Chip("mdi:thermometer", states[it]?.let { s -> "${s.state} ${s.unit.orEmpty()}" } ?: "—") }
                    a.humidity?.let { Chip("mdi:water-percent", states[it]?.let { s -> "${s.state} ${s.unit.orEmpty()}" } ?: "—") }
                    a.main?.let { Chip("mdi:lightbulb-outline", states[it]?.name ?: it) }
                    if (a.devices.isNotEmpty()) Chip("mdi:devices", pluralStringResource(R.plurals.setup_count_devices, a.devices.size, a.devices.size))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MembersStep(plan: SetupPlan, changed: () -> Unit) {
    if (plan.persons.isEmpty()) { Empty(stringResource(R.string.setup_members_none)); return }
    val ha = App.instance.ha
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        plan.persons.forEach { p ->
            Tile(p.selected, { p.selected = !p.selected; changed() }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(Surface), contentAlignment = Alignment.Center) {
                        Text(p.name.take(1).uppercase(), style = TextStyle(fontSize = 22.sp, color = TextSecondary, fontWeight = FontWeight.Medium))
                        if (p.picture != null) AsyncImage(ha.resolveUrl(p.picture), null, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                    }
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(p.name, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, color = TextPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(p.entity, style = TextStyle(fontSize = 12.sp, color = TextSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("mdi:home-account", stringResource(R.string.setup_member_presence))
                    if (p.battery != null) Chip("mdi:cellphone", stringResource(R.string.setup_member_battery))
                    if (p.steps != null) Chip("mdi:walk", stringResource(R.string.setup_member_steps))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CamerasStep(plan: SetupPlan, changed: () -> Unit) {
    if (plan.cameras.isEmpty()) { Empty(stringResource(R.string.setup_cameras_none)); return }
    val ha = App.instance.ha
    val ctx = LocalContext.current
    var iconFor by remember { mutableStateOf<CamTab?>(null) }

    // --- Onglets : renommer, changer l'icône, supprimer, ajouter.
    SectionTitle(stringResource(R.string.setup_cameras_tabs), stringResource(R.string.setup_cameras_tabs_help))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        plan.cameraTabs.forEach { t -> androidx.compose.runtime.key(t.id) {
            val shape = RoundedCornerShape(16.dp)
            val n = plan.cameras.count { it.tab == t.id }
            Row(
                Modifier.width(280.dp).clip(shape).background(Surface, shape).border(1.dp, Outline, shape).padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(Dash.colors.accent.copy(alpha = 0.14f)).clickable { iconFor = t },
                    contentAlignment = Alignment.Center,
                ) { HaIcon(t.icon, size = 22.dp, tint = Dash.colors.accent) }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    InlineName(t.label) { t.label = it; changed() }
                    Text(pluralStringResource(R.plurals.setup_count_cameras, n, n), style = TextStyle(fontSize = 12.sp, color = TextSecondary))
                }
                if (plan.cameraTabs.size > 1) Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable {
                        plan.cameraTabs.remove(t)
                        // Caméras de l'onglet supprimé : reportées sur le premier onglet restant.
                        plan.cameras.filter { it.tab == t.id }.forEach { it.tab = plan.cameraTabs.first().id }
                        changed()
                    },
                    contentAlignment = Alignment.Center,
                ) { HaIcon("mdi:delete-outline", size = 20.dp, tint = Danger) }
            }
        } }
        val addShape = RoundedCornerShape(16.dp)
        Row(
            Modifier.height(58.dp).clip(addShape).border(1.5.dp, Dash.colors.accent, addShape).clickable {
                plan.cameraTabs += CamTab("tab-${System.currentTimeMillis()}", ctx.getString(R.string.tabs_new_name), "mdi:cctv")
                changed()
            }.padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HaIcon("mdi:plus", size = 20.dp, tint = Dash.colors.accent)
            Text(stringResource(R.string.tabs_add_tab), Modifier.padding(start = 8.dp), style = TextStyle(fontSize = 15.sp, color = Dash.colors.accent, fontWeight = FontWeight.Medium))
        }
    }

    // --- Caméras : onglet et flux.
    SectionTitle(stringResource(R.string.setup_cameras_list), stringResource(R.string.setup_cameras_list_help))
    plan.cameras.forEach { c ->
        val shape = RoundedCornerShape(16.dp)
        Row(Modifier.fillMaxWidth().clip(shape).background(Surface, shape).padding(12.dp)) {
            Box(Modifier.width(180.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Background), contentAlignment = Alignment.Center) {
                HaIcon("mdi:cctv", size = 28.dp, tint = TextSecondary)
                AsyncImage(ha.resolveUrl("/api/camera_proxy/${c.preview}"), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(c.name, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, color = TextPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (c.streams.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StreamPicker("mdi:image-outline", stringResource(R.string.setup_stream_card), c.streams, c.preview, c.name, null) {
                        c.preview = it!!
                        if (c.fullscreen == it) c.fullscreen = null
                        changed()
                    }
                    StreamPicker("mdi:fullscreen", stringResource(R.string.setup_stream_fullscreen), c.streams, c.fullscreen, c.name, stringResource(R.string.setup_stream_same)) {
                        c.fullscreen = it?.takeIf { s -> s != c.preview }
                        changed()
                    }
                } else Text(c.preview, style = TextStyle(fontSize = 12.sp, color = TextSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    plan.cameraTabs.forEach { t -> Pill(t.icon, t.label, c.tab == t.id) { c.tab = t.id; changed() } }
                    Pill("mdi:eye-off-outline", stringResource(R.string.setup_camera_hidden), c.tab == null) { c.tab = null; changed() }
                }
            }
        }
    }

    iconFor?.let { t ->
        fr.arubinu.hadash.ui.IconPicker(onDismiss = { iconFor = null }) { ic ->
            if (ic != null) t.icon = ic
            iconFor = null
            changed()
        }
    }
}

@Composable
private fun SectionTitle(title: String, help: String) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(title, style = TextStyle(fontSize = 15.sp, color = Dash.colors.accent, fontWeight = FontWeight.SemiBold))
        Text(help, Modifier.padding(top = 2.dp), style = TextStyle(fontSize = 13.sp, color = TextSecondary, lineHeight = 18.sp))
    }
}

/** Nom modifiable sur place (onglet). */
@Composable
private fun InlineName(value: String, onChange: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    androidx.compose.foundation.text.BasicTextField(
        text, { text = it; if (it.isNotBlank()) onChange(it.trim()) }, singleLine = true,
        textStyle = TextStyle(fontSize = 16.sp, color = TextPrimary, fontWeight = FontWeight.Medium),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Dash.colors.accent),
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f, fill = false)) { inner() }
                HaIcon("mdi:pencil-outline", Modifier.padding(start = 6.dp), size = 14.dp, tint = TextSecondary)
            }
        },
    )
}

/** Choix d'un onglet (ou « Masquée ») pour une caméra. */
@Composable
private fun Pill(icon: String, text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.clip(shape).background(if (selected) Dash.colors.accent else Background, shape)
            .border(1.dp, if (selected) Dash.colors.accent else Outline, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HaIcon(icon, size = 16.dp, tint = if (selected) Color.Black else TextSecondary)
        Text(text, Modifier.padding(start = 6.dp), maxLines = 1,
            style = TextStyle(fontSize = 14.sp, color = if (selected) Color.Black else TextPrimary, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal))
    }
}

/** Liste déroulante des flux d'une caméra ([none] : entrée « même flux » qui rend null). */
@Composable
private fun StreamPicker(icon: String, title: String, streams: List<String>, value: String?, device: String, none: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    fun label(id: String?) = if (id == null) none.orEmpty() else fr.arubinu.hadash.ui.CameraStreams.label(id, device)
    Box {
        Row(
            Modifier.clip(shape).background(Background, shape).border(1.dp, Outline, shape).clickable { open = true }
                .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HaIcon(icon, size = 16.dp, tint = TextSecondary)
            Text(title, Modifier.padding(start = 6.dp, end = 8.dp), style = TextStyle(fontSize = 13.sp, color = TextSecondary))
            Text(label(value), style = TextStyle(fontSize = 13.sp, color = Dash.colors.accent, fontWeight = FontWeight.Medium), maxLines = 1)
            HaIcon("mdi:menu-down", size = 18.dp, tint = TextSecondary)
        }
        androidx.compose.material3.DropdownMenu(open, { open = false }, Modifier.background(Surface)) {
            ((if (none != null) listOf<String?>(null) else emptyList()) + streams).forEach { id ->
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Column {
                            Text(label(id), style = TextStyle(fontSize = 15.sp, color = if (id == value) Dash.colors.accent else TextPrimary))
                            if (id != null) Text(id, style = TextStyle(fontSize = 11.sp, color = TextSecondary))
                        }
                    },
                    onClick = { open = false; onPick(id) },
                )
            }
        }
    }
}

@Composable
private fun ElementsStep(plan: SetupPlan, changed: () -> Unit) {
    var picking by remember { mutableStateOf<RoleChoice?>(null) }
    plan.roles.forEach { r ->
        val e = r.entity?.let { App.instance.ha.states.collectAsState().value[it] }
        val shape = RoundedCornerShape(16.dp)
        Row(
            Modifier.fillMaxWidth().clip(shape).background(Surface, shape).clickable { picking = r }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Dash.colors.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                HaIcon(r.role.icon, size = 24.dp, tint = Dash.colors.accent)
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(stringResource(r.role.title), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary))
                Text(
                    if (r.entity == null) stringResource(R.string.setup_element_none) else "${e?.name ?: r.entity} · ${r.entity}",
                    style = TextStyle(fontSize = 13.sp, color = if (r.entity == null) TextSecondary else Dash.colors.accent), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (r.entity != null) Box(Modifier.size(40.dp).clip(CircleShape).clickable { r.entity = null; changed() }, contentAlignment = Alignment.Center) {
                HaIcon("mdi:close", size = 20.dp, tint = TextSecondary)
            }
            HaIcon("mdi:chevron-right", size = 22.dp, tint = TextSecondary)
        }
    }
    Text(stringResource(R.string.setup_elements_help), style = TextStyle(fontSize = 13.sp, color = TextSecondary, lineHeight = 18.sp))
    picking?.let { r ->
        EntityPicker(r.role.domain, onDismiss = { picking = null }) { id -> r.entity = id; picking = null; changed() }
    }
}

@Composable
private fun DoneStep(plan: SetupPlan) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HaIcon("mdi:party-popper", size = 28.dp, tint = Dash.colors.accent)
            Text(stringResource(R.string.setup_done_text), Modifier.padding(start = 12.dp), style = TextStyle(fontSize = 16.sp, color = TextPrimary, lineHeight = 22.sp))
        }
        if (!plan.keepLayout) {
            Summary("mdi:floor-plan", pluralStringResource(R.plurals.setup_count_rooms, plan.areas.count { it.selected }, plan.areas.count { it.selected }))
            Summary("mdi:account-group-outline", pluralStringResource(R.plurals.setup_count_people, plan.persons.count { it.selected }, plan.persons.count { it.selected }))
            Summary("mdi:cctv", pluralStringResource(R.plurals.setup_count_cameras, plan.cameras.count { it.tab != null }, plan.cameras.count { it.tab != null }))
            Summary("mdi:tune-variant", pluralStringResource(R.plurals.setup_count_elements, plan.roles.count { it.entity != null }, plan.roles.count { it.entity != null }))
        }
        Summary("mdi:translate", stringResource(R.string.setup_done_tabs))
        Text(stringResource(R.string.setup_done_edit), style = TextStyle(fontSize = 13.sp, color = TextSecondary, lineHeight = 18.sp))
    }
}

// --- Éléments ----------------------------------------------------------------------------------------------------

@Composable
private fun Panel(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Surface, shape).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
}

@Composable
private fun Tile(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier.width(300.dp).clip(shape).background(if (selected) Dash.colors.accent.copy(alpha = 0.08f) else Surface, shape)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Dash.colors.accent else Outline, shape)
            .clickable(onClick = onClick).padding(16.dp),
    ) {
        Column(Modifier.padding(end = 28.dp)) { content() }
        Box(
            Modifier.align(Alignment.TopEnd).size(24.dp).clip(CircleShape)
                .background(if (selected) Dash.colors.accent else Color.Transparent).border(1.5.dp, if (selected) Dash.colors.accent else TextSecondary, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected) HaIcon("mdi:check", size = 16.dp, tint = Color.Black) }
    }
}

@Composable
private fun Chip(icon: String, text: String) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.06f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HaIcon(icon, size = 14.dp, tint = TextSecondary)
        Text(text, Modifier.padding(start = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontSize = 12.sp, color = TextSecondary))
    }
}

@Composable
private fun Stat(icon: String, text: String) {
    Row(
        Modifier.clip(RoundedCornerShape(14.dp)).background(Dash.colors.accent.copy(alpha = 0.1f)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HaIcon(icon, size = 20.dp, tint = Dash.colors.accent)
        Text(text, Modifier.padding(start = 8.dp), style = TextStyle(fontSize = 15.sp, color = TextPrimary, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun Summary(icon: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HaIcon(icon, size = 20.dp, tint = Dash.colors.accent)
        Text(text, Modifier.padding(start = 12.dp), style = TextStyle(fontSize = 15.sp, color = TextPrimary))
    }
}

@Composable
private fun Choice(icon: String, title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (selected) Dash.colors.accent.copy(alpha = 0.08f) else Surface, shape)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Dash.colors.accent else Outline, shape)
            .clickable(onClick = onClick).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(Dash.colors.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            HaIcon(icon, size = 28.dp, tint = Dash.colors.accent)
        }
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(title, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, color = TextPrimary))
            Text(desc, Modifier.padding(top = 4.dp), style = TextStyle(fontSize = 14.sp, color = TextSecondary, lineHeight = 20.sp))
        }
        Box(
            Modifier.size(26.dp).clip(CircleShape).border(2.dp, if (selected) Dash.colors.accent else TextSecondary, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected) Box(Modifier.size(14.dp).clip(CircleShape).background(Dash.colors.accent)) }
    }
}

@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(Modifier.clip(shape).background(Background, shape).padding(4.dp)) {
        options.forEach { (v, label) ->
            val sel = v == selected
            Text(
                label,
                Modifier.clip(RoundedCornerShape(9.dp)).background(if (sel) Dash.colors.accent else Color.Transparent)
                    .clickable { onSelect(v) }.padding(horizontal = 14.dp, vertical = 8.dp),
                style = TextStyle(fontSize = 14.sp, color = if (sel) Color.Black else TextPrimary, fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal),
            )
        }
    }
}

@Composable
private fun SelectAll(all: Boolean, onChange: (Boolean) -> Unit) {
    Text(
        stringResource(if (all) R.string.setup_select_none else R.string.setup_select_all),
        Modifier.clip(RoundedCornerShape(10.dp)).clickable { onChange(!all) }.padding(horizontal = 4.dp, vertical = 4.dp),
        style = TextStyle(fontSize = 14.sp, color = Dash.colors.accent, fontWeight = FontWeight.Medium),
    )
}

@Composable
private fun Empty(text: String) = Panel {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HaIcon("mdi:information-outline", size = 22.dp, tint = TextSecondary)
        Text(text, Modifier.padding(start = 12.dp), style = TextStyle(fontSize = 15.sp, color = TextSecondary))
    }
}

@Composable
private fun StatusLine(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, Modifier.padding(start = 8.dp), style = TextStyle(fontSize = 14.sp, color = color))
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, hint: String?, secret: Boolean = false) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        placeholder = hint?.let { { Text(it, color = TextSecondary.copy(alpha = 0.5f)) } },
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri),
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, focusedBorderColor = Dash.colors.accent),
    )
}

@Composable
private fun WizardButton(text: String, primary: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val c = Dash.colors.accent
    Text(
        text,
        Modifier.heightIn(min = 52.dp).clip(shape)
            .background(if (primary) (if (enabled) c else c.copy(alpha = 0.3f)) else Color.Transparent, shape)
            .border(1.dp, if (primary) Color.Transparent else Outline, shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 28.dp, vertical = 15.dp),
        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = if (primary) Color.Black else TextPrimary, textAlign = TextAlign.Center),
    )
}
