package fr.pergens.hamacs.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import fr.pergens.hamacs.ui.theme.parseCssColor
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.material3.CircularProgressIndicator
import fr.pergens.hamacs.update.Updater.State as UpdateState
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import fr.pergens.hamacs.App
import fr.pergens.hamacs.BuildConfig
import fr.pergens.hamacs.R
import fr.pergens.hamacs.ha.ConnState
import fr.pergens.hamacs.ui.components.HaIcon
import fr.pergens.hamacs.ui.theme.Dash
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

private enum class Section(val icon: String, @StringRes val title: Int, @StringRes val desc: Int) {
    Connection("mdi:home-assistant", R.string.section_connection, R.string.section_connection_desc),
    Display("mdi:palette-outline", R.string.section_display, R.string.section_display_desc),
    Idle("mdi:motion-sensor", R.string.section_idle, R.string.section_idle_desc),
    Voice("mdi:microphone-outline", R.string.section_voice, R.string.section_voice_desc),
    Dashboard("mdi:view-dashboard-outline", R.string.section_dashboard, R.string.section_dashboard_desc),
    Security("mdi:shield-key-outline", R.string.section_security, R.string.section_security_desc),
    About("mdi:information-outline", R.string.section_about, R.string.section_about_desc),
}

// Palette des réglages : surfaces neutres sombres, accent du thème, contrastes AA.
private val Background = Color(0xFF0B0B0D)
private val NavBackground = Color(0xFF111114)
private val Surface = Color(0xFF19191D)
private val Outline = Color(0xFF2C2C33)
private val TextPrimary = Color(0xFFF2F2F5)
private val TextSecondary = Color(0xFFA3A3AD)
private val Success = Color(0xFF4CD964)
private val Warning = Color(0xFFFFB74D)
private val Danger = Color(0xFFFF6B6B)

/**
 * Page de réglages plein écran, pensée pour un public non technique : sections thématiques à gauche,
 * contenu groupé en cartes à droite, textes d'aide sous chaque option, application immédiate.
 */
@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    cameraPermissionTick: Int,
    requestCamera: () -> Unit,
    requestMic: () -> Unit,
    onEditBlocks: () -> Unit,
    transferMessage: Pair<String, Boolean>?,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onPhone: () -> Unit,
) {
    val accent = Dash.colors.accent
    var section by rememberSaveable { mutableStateOf(Section.Connection) }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = accent, onPrimary = Color.Black, secondaryContainer = accent.copy(alpha = 0.22f),
            onSecondaryContainer = TextPrimary, surface = Surface, onSurface = TextPrimary,
            onSurfaceVariant = TextSecondary, outline = Outline, background = Background,
        ),
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .background(Background)
                // Bloque les appuis vers le tableau de bord en dessous.
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
        ) {
            // --- Navigation ---
            Column(
                Modifier.width(300.dp).fillMaxHeight().background(NavBackground).padding(vertical = 24.dp, horizontal = 16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) { HaIcon("mdi:arrow-left", size = 24.dp, tint = TextPrimary) }
                    Text(
                        stringResource(R.string.settings_title), Modifier.padding(start = 8.dp),
                        style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary),
                    )
                }
                Text(
                    stringResource(R.string.settings_hint), Modifier.padding(start = 16.dp, top = 4.dp, bottom = 24.dp),
                    style = TextStyle(fontSize = 13.sp, color = TextSecondary),
                )
                Section.entries.forEach { s -> NavItem(s, s == section) { section = s } }
            }

            // --- Contenu ---
            Box(Modifier.weight(1f).fillMaxHeight()) {
                AnimatedContent(
                    targetState = section,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                    label = "settings",
                ) { s ->
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 36.dp),
                    ) {
                        Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            Column {
                                Text(stringResource(s.title), style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary))
                                Text(stringResource(s.desc), Modifier.padding(top = 4.dp), style = TextStyle(fontSize = 15.sp, color = TextSecondary))
                            }
                            when (s) {
                                Section.Connection -> ConnectionSection()
                                Section.Display -> DisplaySection()
                                Section.Idle -> IdleSection(cameraPermissionTick, requestCamera)
                                Section.Voice -> VoiceSection(requestMic)
                                Section.Dashboard -> DashboardSection(onEditBlocks, transferMessage, onExport, onImport, onPhone)
                                Section.Security -> SecuritySection()
                                Section.About -> AboutSection(onPhone)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavItem(s: Section, selected: Boolean, onClick: () -> Unit) {
    val accent = Dash.colors.accent
    val bg by animateColorAsState(if (selected) accent.copy(alpha = 0.14f) else Color.Transparent, tween(200), label = "nav")
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(shape)
            .background(bg, shape)
            .clickable(role = Role.Tab, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (selected) accent.copy(alpha = 0.22f) else Surface),
            contentAlignment = Alignment.Center,
        ) { HaIcon(s.icon, size = 22.dp, tint = if (selected) accent else TextSecondary) }
        Column(Modifier.padding(start = 14.dp)) {
            Text(stringResource(s.title), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary))
            Text(stringResource(s.desc), style = TextStyle(fontSize = 12.sp, color = TextSecondary), maxLines = 1)
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Briques communes
// ------------------------------------------------------------------------------------------------

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Surface, shape), content = content)
}

@Composable
private fun GroupDivider() = HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = Outline)

@Composable
private fun SettingRow(
    title: String,
    supporting: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 64.dp)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary))
            supporting?.let { Text(it, Modifier.padding(top = 2.dp), style = TextStyle(fontSize = 13.sp, color = TextSecondary, lineHeight = 18.sp)) }
        }
        trailing?.let { Box(Modifier.padding(start = 16.dp)) { it() } }
    }
}

@Composable
private fun SwitchRow(title: String, supporting: String?, checked: Boolean, onChange: (Boolean) -> Unit) =
    SettingRow(title, supporting, onClick = { onChange(!checked) }) {
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Dash.colors.accent, checkedThumbColor = Color.Black))
    }

/** Teintes proposées pour la couleur principale (même luminosité que l'accent d'origine). */
private val ACCENTS = listOf("#8CF5FF", "#6FB8FF", "#8C9EFF", "#B48CFF", "#FF8CD9", "#FF8C8C", "#FFAD6F", "#FFE07A", "#8CFFA8", "#FFFFFF")

/**
 * Couleur principale du tableau de bord (contours, boutons actifs, graphiques, M.A.C.S.…) : celle du thème,
 * une teinte proposée, ou une teinte libre (curseur).
 */
@Composable
private fun AccentPicker(current: String, onChange: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.accent_color), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary))
        Text(stringResource(R.string.accent_color_help), style = TextStyle(fontSize = 13.sp, color = TextSecondary))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            // « Thème » : couleur définie par dashboard.json.
            val themeSel = current.isBlank()
            Box(
                Modifier.clip(RoundedCornerShape(18.dp)).border(2.dp, if (themeSel) Dash.colors.accent else Outline, RoundedCornerShape(18.dp))
                    .clickable { onChange("") }.padding(horizontal = 14.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.accent_theme), style = TextStyle(fontSize = 14.sp, color = TextPrimary)) }
            ACCENTS.forEach { hex ->
                val c = parseCssColor(hex) ?: return@forEach
                val sel = current.equals(hex, ignoreCase = true)
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(c)
                        .border(if (sel) 3.dp else 1.dp, if (sel) Color.White else Color.White.copy(alpha = 0.2f), CircleShape)
                        .clickable { onChange(hex) },
                )
            }
        }
        // Teinte libre : même saturation et luminosité que l'accent d'origine, seule la teinte change.
        val hsv = remember(current) {
            FloatArray(3).also { android.graphics.Color.colorToHSV((parseCssColor(current.ifBlank { "#8CF5FF" }) ?: Color.Cyan).toArgb(), it) }
        }
        var hue by remember(current) { mutableStateOf(hsv[0]) }
        Text(stringResource(R.string.accent_custom), style = TextStyle(fontSize = 14.sp, color = TextSecondary))
        Box(
            Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))
                .background(Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 0.45f, 1f) })),
        )
        Slider(
            hue, { hue = it }, valueRange = 0f..359f,
            onValueChangeFinished = {
                val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.45f, 1f))
                onChange("#%06X".format(argb and 0xFFFFFF))
            },
            colors = SliderDefaults.colors(thumbColor = Color.hsv(hue, 0.45f, 1f), activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent),
        )
    }
}

@Composable
private fun SliderRow(title: String, supporting: String?, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, format: (Float) -> String, onChange: (Float) -> Unit) {
    var v by remember(value) { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary))
            Text(format(v), style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Dash.colors.accent))
        }
        supporting?.let { Text(it, Modifier.padding(top = 2.dp), style = TextStyle(fontSize = 13.sp, color = TextSecondary)) }
        Slider(
            v, { v = it }, valueRange = range, steps = steps,
            onValueChangeFinished = { onChange(v) },
            colors = SliderDefaults.colors(thumbColor = Dash.colors.accent, activeTrackColor = Dash.colors.accent),
        )
    }
}

@Composable
private fun Field(
    value: String, onChange: (String) -> Unit, label: String, hint: String?, help: String?, secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Uri, next: Boolean = false, onDone: (() -> Unit)? = null,
) {
    var visible by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value, onChange,
        Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = hint?.let { { Text(it) } },
        supportingText = help?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) (if (keyboard == KeyboardType.NumberPassword) keyboard else KeyboardType.Password) else keyboard,
            imeAction = if (next) ImeAction.Next else ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke(); focus.clearFocus() }),
        trailingIcon = if (secret) {
            {
                // Hors du parcours « Suivant » du clavier (qui doit aller au champ suivant).
                TextButton(onClick = { visible = !visible }, Modifier.focusProperties { canFocus = false }) {
                    Text(stringResource(if (visible) R.string.hide else R.string.show))
                }
            }
        } else null,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Dash.colors.accent, unfocusedBorderColor = Outline),
        shape = RoundedCornerShape(12.dp),
    )
}

/** Mur de caméras : fermeture automatique (comme le plein écran par défaut) et directs simultanés. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WallSettings(settings: fr.pergens.hamacs.config.Settings) {
    val app = fr.pergens.hamacs.App.instance
    val learned by fr.pergens.hamacs.ui.CameraWall.learned.collectAsState()
    val chip = @Composable { selected: Boolean, label: String, onClick: () -> Unit ->
        FilterChip(
            selected = selected, onClick = onClick, label = { Text(label) }, modifier = Modifier.heightIn(min = 40.dp),
            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Dash.colors.accent.copy(alpha = 0.22f)),
        )
    }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.wall_timeout), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary))
        Text(stringResource(R.string.wall_timeout_help), style = TextStyle(fontSize = 13.sp, color = TextSecondary))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            chip(settings.wallSeconds < 0, stringResource(R.string.wall_timeout_same)) { app.config.update { it.copy(wallSeconds = -1) } }
            listOf(0, 30, 60, 120, 300, 600).forEach { sec ->
                val label = when {
                    sec == 0 -> stringResource(R.string.idle_never)
                    sec < 60 -> stringResource(R.string.idle_seconds, sec)
                    else -> stringResource(R.string.idle_minutes, sec / 60)
                }
                chip(settings.wallSeconds == sec, label) { app.config.update { it.copy(wallSeconds = sec) } }
            }
        }
    }
    GroupDivider()
    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.wall_live_max), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary))
        Text(stringResource(R.string.wall_live_max_help, fr.pergens.hamacs.ui.CameraWall.estimate), style = TextStyle(fontSize = 13.sp, color = TextSecondary))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            chip(settings.wallMaxLive == 0, stringResource(R.string.wall_live_auto)) { app.config.update { it.copy(wallMaxLive = 0) } }
            (1..6).forEach { n -> chip(settings.wallMaxLive == n, n.toString()) { app.config.update { it.copy(wallMaxLive = n) } } }
        }
        if (learned > 0 && settings.wallMaxLive == 0) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.wall_live_learned, learned), Modifier.weight(1f), style = TextStyle(fontSize = 13.sp, color = TextSecondary))
            OutlinedButton(
                onClick = { fr.pergens.hamacs.ui.CameraWall.resetLearned() }, Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
            ) { Text(stringResource(R.string.wall_live_reset)) }
        }
    }
    GroupDivider()
    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.wall_solar_min), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary))
        Text(stringResource(R.string.wall_solar_min_help), style = TextStyle(fontSize = 13.sp, color = TextSecondary))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            chip(settings.wallSolarMin == 0, stringResource(R.string.wall_solar_always)) { app.config.update { it.copy(wallSolarMin = 0) } }
            listOf(20, 30, 50, 70).forEach { pc ->
                chip(settings.wallSolarMin == pc, stringResource(R.string.wall_solar_above, pc)) { app.config.update { it.copy(wallSolarMin = pc) } }
            }
        }
    }
}

/** Choix d'un délai : Jamais, 30 s, 1, 2, 5, 10 ou 30 min. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DelayChips(selected: Int, options: List<Int> = listOf(0, 30, 60, 120, 300, 600, 1800), onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { sec ->
            val label = when {
                sec == 0 -> stringResource(R.string.idle_never)
                sec < 60 -> stringResource(R.string.idle_seconds, sec)
                else -> stringResource(R.string.idle_minutes, sec / 60)
            }
            FilterChip(
                selected = selected == sec,
                onClick = { onSelect(sec) },
                label = { Text(label) },
                modifier = Modifier.heightIn(min = 40.dp),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Dash.colors.accent.copy(alpha = 0.22f)),
            )
        }
    }
}

/**
 * Mode nuit : début et fin (soleil ou heure), inactivité avant la veille de nuit, écran noir ou luminosité réduite.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NightGroup() {
    val app = App.instance
    val settings by app.config.settings.collectAsState()
    val sun = app.ha.states.collectAsState().value["sun.sun"]
    // Bornes en cours d'édition : 0 = début, 1 = fin.
    var editing by remember { mutableStateOf<Int?>(null) }
    Group {
        SwitchRow(stringResource(R.string.night_title), stringResource(R.string.night_help), settings.nightEnabled) { on ->
            app.config.update { it.copy(nightEnabled = on) }
        }
        if (settings.nightEnabled) {
            GroupDivider()
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf(0, 1).forEach { which ->
                    val spec = if (which == 0) settings.nightStart else settings.nightEnd
                    val sunIso = sun?.attrString(if (which == 0) "next_setting" else "next_rising")
                    val sunMin = fr.pergens.hamacs.ui.NightMode.minutes("sun", sunIso, if (which == 0) 21 * 60 + 30 else 7 * 60)
                    val fixed = fr.pergens.hamacs.ui.NightMode.parse(spec)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            stringResource(if (which == 0) R.string.night_from else R.string.night_to), Modifier.width(80.dp),
                            style = TextStyle(fontSize = 15.sp, color = TextPrimary, fontWeight = FontWeight.Medium),
                        )
                        FilterChip(
                            selected = spec == "sun",
                            onClick = { app.config.update { if (which == 0) it.copy(nightStart = "sun") else it.copy(nightEnd = "sun") } },
                            label = { Text(stringResource(if (which == 0) R.string.night_sunset else R.string.night_sunrise, fr.pergens.hamacs.ui.NightMode.format(sunMin))) },
                            modifier = Modifier.heightIn(min = 40.dp),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Dash.colors.accent.copy(alpha = 0.22f)),
                        )
                        FilterChip(
                            selected = fixed != null,
                            onClick = { editing = which },
                            label = { Text(fixed?.let { fr.pergens.hamacs.ui.NightMode.format(it) } ?: stringResource(R.string.night_time)) },
                            modifier = Modifier.heightIn(min = 40.dp),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Dash.colors.accent.copy(alpha = 0.22f)),
                        )
                    }
                }
                Text(stringResource(R.string.night_after), style = TextStyle(fontSize = 13.sp, color = TextSecondary))
                DelayChips(settings.nightSeconds, listOf(10, 30, 60, 120, 300)) { sec -> app.config.update { it.copy(nightSeconds = sec) } }
            }
            GroupDivider()
            SwitchRow(stringResource(R.string.night_black), stringResource(R.string.night_black_help), settings.nightBlack) { on ->
                app.config.update { it.copy(nightBlack = on) }
            }
            if (!settings.nightBlack) {
                GroupDivider()
                SliderRow(
                    stringResource(R.string.night_level), null, settings.nightLevel, 0.01f..0.3f, steps = 28,
                    format = { "${(it * 100).roundToInt()} %" },
                ) { v -> app.config.update { it.copy(nightLevel = (v * 100).roundToInt() / 100f) } }
            }
        }
    }
    editing?.let { which ->
        val current = fr.pergens.hamacs.ui.NightMode.parse(if (which == 0) settings.nightStart else settings.nightEnd) ?: if (which == 0) 22 * 60 else 7 * 60
        val picker = rememberTimePickerState(current / 60, current % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { editing = null },
            confirmButton = {
                TextButton(onClick = {
                    val v = fr.pergens.hamacs.ui.NightMode.format(picker.hour * 60 + picker.minute)
                    app.config.update { if (which == 0) it.copy(nightStart = v) else it.copy(nightEnd = v) }
                    editing = null
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } },
            text = {
                TimePicker(
                    picker,
                    colors = androidx.compose.material3.TimePickerDefaults.colors(
                        timeSelectorSelectedContainerColor = Dash.colors.accent.copy(alpha = 0.3f),
                        timeSelectorSelectedContentColor = TextPrimary,
                        selectorColor = Dash.colors.accent,
                    ),
                )
            },
        )
    }
}

@Composable
private fun StatusChip(color: Color, text: String) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, Modifier.padding(start = 8.dp), style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = color))
    }
}

@Composable
private fun Feedback(message: String?, isError: Boolean = false) {
    AnimatedVisibility(message != null, enter = fadeIn(), exit = fadeOut()) {
        Text(message ?: "", style = TextStyle(fontSize = 13.sp, color = if (isError) Danger else Success))
    }
}

// ------------------------------------------------------------------------------------------------
// Sections
// ------------------------------------------------------------------------------------------------

@Composable
private fun ConnectionSection() {
    val app = App.instance
    val settings by app.config.settings.collectAsState()
    val conn by app.ha.connection.collectAsState()
    var url by remember { mutableStateOf(settings.haUrl) }
    var token by remember { mutableStateOf(settings.haToken) }
    var saved by remember { mutableStateOf(false) }
    val changed = url.trim() != settings.haUrl || token.trim() != settings.haToken

    Group {
        SettingRow(
            stringResource(R.string.section_connection),
            settings.haUrl,
        ) {
            when (conn) {
                ConnState.Connected -> StatusChip(Success, stringResource(R.string.status_connected))
                ConnState.Connecting -> StatusChip(Warning, stringResource(R.string.status_connecting))
                ConnState.AuthFailed -> StatusChip(Danger, stringResource(R.string.status_auth_failed))
                ConnState.Disconnected -> StatusChip(Danger, stringResource(R.string.status_disconnected))
            }
        }
        GroupDivider()
        val servers = fr.pergens.hamacs.ha.rememberHaServers()
        val login = remember { fr.pergens.hamacs.ui.HaLoginState() }
        var method by remember {
            mutableStateOf(if (settings.haToken.isNotBlank()) fr.pergens.hamacs.ui.HaLoginMethod.Token else fr.pergens.hamacs.ui.HaLoginMethod.Phone)
        }
        fr.pergens.hamacs.ui.HaLoginDialogs(login, url) { t ->
            token = t
            app.config.update { it.copy(haUrl = url.trim(), haToken = t) }
            saved = true
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Field(url, { url = it; saved = false }, stringResource(R.string.ha_url), stringResource(R.string.ha_url_hint), null)
            if (insecureRemote(url)) Text(stringResource(R.string.ha_http_warning), style = TextStyle(fontSize = 13.sp, color = Danger, lineHeight = 18.sp))
            fr.pergens.hamacs.ui.HaServerChips(servers, url) { url = it; saved = false }
            // Connexion avec le compte HA (téléphone ou tablette), sinon jeton collé : mêmes choix que l'assistant.
            Text(stringResource(R.string.ha_login_with), style = TextStyle(fontSize = 14.sp, color = TextSecondary))
            fr.pergens.hamacs.ui.HaLoginChoices(method, url, login) { method = it }
            if (method == fr.pergens.hamacs.ui.HaLoginMethod.Token) {
                Field(token, { token = it; saved = false }, stringResource(R.string.ha_token), null, stringResource(R.string.ha_token_help), secret = true)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (method) {
                    fr.pergens.hamacs.ui.HaLoginMethod.Phone -> Button(onClick = { login.phone = true }, enabled = url.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.ha_login_phone_open))
                    }
                    fr.pergens.hamacs.ui.HaLoginMethod.Tablet -> Button(onClick = { login.tablet = true }, enabled = url.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.ha_login_tablet_open))
                    }
                    fr.pergens.hamacs.ui.HaLoginMethod.Token -> Button(
                        onClick = {
                            app.config.update { it.copy(haUrl = url.trim(), haToken = token.trim()) }
                            saved = true
                        },
                        enabled = changed,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.action_save_connect)) }
                }
                Spacer(Modifier.width(16.dp))
                Feedback(if (saved && !changed) stringResource(R.string.saved) else null)
            }
        }
    }
    ProxyGroup()
    HaDeviceGroup()
    DlnaGroup()
}

/**
 * Adresse en `http://` hors du réseau local (nom de domaine public, IP publique) : le jeton et toutes les données
 * circuleraient en clair sur Internet. Réseau local : IP privées, `.local`, `.lan`, `.home`, `.internal`, nom sans point.
 */
internal fun insecureRemote(url: String): Boolean {
    val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return false
    if (!uri.scheme.equals("http", true)) return false
    val host = uri.host?.lowercase() ?: return false
    if ('.' !in host || listOf(".local", ".lan", ".home", ".internal", ".home.arpa").any { host.endsWith(it) }) return false
    val ip = host.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 } ?: return true
    return !(ip[0] == 10 || ip[0] == 127 || (ip[0] == 192 && ip[1] == 168) || (ip[0] == 172 && ip[1] in 16..31) || (ip[0] == 169 && ip[1] == 254) ||
        (ip[0] == 100 && ip[1] in 64..127))
}

/**
 * Proxy inverse (accès par Internet) : en-têtes envoyés avec chaque requête vers Home Assistant. Modèles pour Pangolin
 * (jeton d'accès de la ressource) et Cloudflare Access (jeton de service).
 */
@Composable
private fun ProxyGroup() {
    val app = App.instance
    val rows = remember { androidx.compose.runtime.mutableStateListOf<Pair<String, String>>().apply { addAll(app.config.proxyHeaders) } }
    var saved by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(rows.isNotEmpty()) }
    val changed = rows.toList() != app.config.proxyHeaders
    fun preset(vararg names: String) {
        rows.removeAll { it.second.isBlank() }
        names.filter { n -> rows.none { it.first.equals(n, true) } }.forEach { rows.add(it to "") }
        saved = false
    }
    Group {
        SettingRow(stringResource(R.string.proxy_title), stringResource(R.string.proxy_help), onClick = { open = !open }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (app.config.proxyHeaders.isNotEmpty()) StatusChip(Success, stringResource(R.string.proxy_count, app.config.proxyHeaders.size))
                Spacer(Modifier.width(12.dp))
                Chevron(open)
            }
        }
        Unfold(open) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.proxy_presets), style = TextStyle(fontSize = 14.sp, color = TextSecondary))
                    OutlinedButton(onClick = { preset("P-Access-Token-Id", "P-Access-Token") }, colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)) { Text("Pangolin") }
                    OutlinedButton(onClick = { preset("CF-Access-Client-Id", "CF-Access-Client-Secret") }, colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)) { Text("Cloudflare Access") }
                }
                rows.forEachIndexed { i, (name, value) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(0.4f)) {
                            Field(name, { rows[i] = it.filter { c -> !c.isWhitespace() } to rows[i].second; saved = false }, stringResource(R.string.proxy_name), null, null, keyboard = KeyboardType.Text, next = true)
                        }
                        Box(Modifier.weight(0.6f)) {
                            Field(value, { rows[i] = rows[i].first to it.trim(); saved = false }, stringResource(R.string.proxy_value), null, null, secret = true)
                        }
                        TextButton(onClick = { rows.removeAt(i); saved = false }) { HaIcon("mdi:delete-outline", size = 22.dp, tint = TextSecondary) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { rows.add("" to ""); saved = false }, Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)) {
                        Text(stringResource(R.string.proxy_add))
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = {
                            app.config.setProxyHeaders(rows.toList())
                            rows.clear(); rows.addAll(app.config.proxyHeaders)
                            // Nouvelle connexion avec les nouveaux en-têtes.
                            val s = app.config.settings.value
                            if (s.haUrl.isNotBlank() && s.haToken.isNotBlank()) app.ha.connect(s.haUrl, s.haToken)
                            saved = true
                        },
                        enabled = changed,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.action_save_connect)) }
                    Spacer(Modifier.width(16.dp))
                    Feedback(if (saved && !changed) stringResource(R.string.saved) else null)
                }
                Text(stringResource(R.string.proxy_note), style = TextStyle(fontSize = 13.sp, color = TextSecondary, lineHeight = 18.sp))
            }
        }
    }
}

/** La tablette vue par Home Assistant (intégration mobile_app) : activation, nom, service de notification. */
@Composable
private fun HaDeviceGroup() {
    val link = App.instance.link
    val status by link.status.collectAsState()
    var on by remember { mutableStateOf(link.enabled) }
    var name by remember { mutableStateOf(link.deviceName) }
    Group {
        SwitchRow(stringResource(R.string.ha_device_title), stringResource(R.string.ha_device_help), on) { on = it; link.enabled = it }
        if (on) {
            GroupDivider()
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field(name, { name = it }, stringResource(R.string.ha_device_name), null, null, keyboard = KeyboardType.Text)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { link.deviceName = name },
                        enabled = name.isNotBlank() && name.trim() != link.deviceName,
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    ) { Text(stringResource(R.string.ha_device_rename)) }
                    Spacer(Modifier.width(16.dp))
                    when (val s = status) {
                        is fr.pergens.hamacs.ha.MobileAppLink.Status.Registered -> StatusChip(Success, stringResource(R.string.ha_device_registered))
                        is fr.pergens.hamacs.ha.MobileAppLink.Status.Failed -> StatusChip(Danger, stringResource(R.string.ha_device_failed, s.message))
                        else -> StatusChip(Warning, stringResource(R.string.ha_device_registering))
                    }
                }
                (status as? fr.pergens.hamacs.ha.MobileAppLink.Status.Registered)?.let { r ->
                    Text(
                        stringResource(R.string.ha_device_usage, r.service),
                        style = TextStyle(fontSize = 13.sp, color = TextSecondary, lineHeight = 19.sp),
                    )
                }
            }
        }
    }
}

/** Satellite Home Assistant (Wyoming) : la voix passe par le pipeline Assist choisi dans Home Assistant. */
@Composable
private fun WyomingGroup() {
    val sat = App.instance.voice.wyoming
    val wake by sat.wakeWord.collectAsState()
    val status by sat.status.collectAsState()
    Group {
        run {
            SwitchRow(stringResource(R.string.wyoming_wake), stringResource(R.string.wyoming_wake_help), wake) { sat.setWakeWord(it) }
            GroupDivider()
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (val s = status) {
                    is fr.pergens.hamacs.voice.WyomingSatellite.Status.Connected ->
                        if (s.running) StatusChip(Success, stringResource(R.string.wyoming_connected))
                        else StatusChip(Warning, stringResource(R.string.wyoming_paused))
                    fr.pergens.hamacs.voice.WyomingSatellite.Status.Waiting -> StatusChip(Warning, stringResource(R.string.wyoming_waiting))
                    else -> StatusChip(TextSecondary, stringResource(R.string.dlna_starting))
                }
                Text(stringResource(R.string.wyoming_usage), style = TextStyle(fontSize = 13.sp, color = TextSecondary, lineHeight = 19.sp))
            }
            GroupDivider()
            Box(Modifier.padding(20.dp)) { PeersBlock(sat.guard, modeChoice = false, help = stringResource(R.string.peers_wyoming_help)) }
        }
    }
}

/** Lecteur DLNA : Home Assistant découvre la tablette comme lecteur multimédia (intégration DLNA Digital Media Renderer). */
@Composable
private fun DlnaGroup() {
    val dlna = App.instance.dlna
    val on by dlna.enabled.collectAsState()
    val status by dlna.status.collectAsState()
    Group {
        SwitchRow(stringResource(R.string.dlna_title), stringResource(R.string.dlna_help), on) { dlna.setEnabled(it) }
        if (on) {
            GroupDivider()
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (val s = status) {
                    is fr.pergens.hamacs.dlna.DlnaRenderer.Status.Running -> StatusChip(Success, stringResource(R.string.dlna_running, s.name))
                    fr.pergens.hamacs.dlna.DlnaRenderer.Status.NoNetwork -> StatusChip(Warning, stringResource(R.string.dlna_no_network))
                    else -> StatusChip(TextSecondary, stringResource(R.string.dlna_starting))
                }
                Text(stringResource(R.string.dlna_usage), style = TextStyle(fontSize = 13.sp, color = TextSecondary, lineHeight = 19.sp))
            }
            GroupDivider()
            Box(Modifier.padding(20.dp)) { PeersBlock(dlna.guard, modeChoice = true) }
        }
    }
}

/**
 * Appareils qui contactent un service de la tablette : accès (DLNA : tout le réseau ou approuvés), puis la liste,
 * avec approbation, blocage et retrait.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeersBlock(guard: fr.pergens.hamacs.net.PeerGuard, modeChoice: Boolean, help: String? = null) {
    val peers by guard.peers.collectAsState()
    val mode by guard.mode.collectAsState()
    val open = mode == fr.pergens.hamacs.net.PeerGuard.Mode.Open
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (modeChoice) {
            Text(stringResource(R.string.peers_access), style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(!open, { guard.setMode(fr.pergens.hamacs.net.PeerGuard.Mode.Approved) }, SegmentedButtonDefaults.itemShape(0, 2)) {
                    Text(stringResource(R.string.peers_mode_approved))
                }
                SegmentedButton(open, { guard.setMode(fr.pergens.hamacs.net.PeerGuard.Mode.Open) }, SegmentedButtonDefaults.itemShape(1, 2)) {
                    Text(stringResource(R.string.peers_mode_open))
                }
            }
        }
        Text(
            help ?: stringResource(if (open) R.string.peers_mode_open_help else R.string.peers_mode_approved_help),
            style = TextStyle(fontSize = 13.sp, color = TextSecondary, lineHeight = 19.sp),
        )
        Text(stringResource(R.string.peers_title), Modifier.padding(top = 6.dp), style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary))
        if (peers.isEmpty()) Text(stringResource(R.string.peers_none), style = TextStyle(fontSize = 13.sp, color = TextSecondary))
        val fmt = remember { java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm") }
        peers.forEach { p ->
            val (icon, tint, state) = when {
                p.ha -> Triple("mdi:home-assistant", Success, stringResource(R.string.peers_state_ha))
                p.state == fr.pergens.hamacs.net.PeerGuard.State.Allowed -> Triple("mdi:check-circle-outline", Success, stringResource(R.string.peers_state_allowed))
                p.state == fr.pergens.hamacs.net.PeerGuard.State.Blocked -> Triple("mdi:cancel", Danger, stringResource(R.string.peers_state_blocked))
                open -> Triple("mdi:help-circle-outline", Warning, stringResource(R.string.peers_state_new))
                else -> Triple("mdi:help-circle-outline", Warning, stringResource(R.string.peers_state_pending))
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Background).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HaIcon(icon, size = 22.dp, tint = tint)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(p.label.ifEmpty { p.ip }, style = TextStyle(fontSize = 15.sp, color = TextPrimary), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    val last = fmt.format(java.time.Instant.ofEpochMilli(p.last).atZone(java.time.ZoneId.systemDefault()))
                    Text(listOfNotNull(p.ip.takeIf { p.label.isNotEmpty() }, state, last).joinToString(" · "), style = TextStyle(fontSize = 12.sp, color = TextSecondary))
                }
                if (!p.ha) {
                    if (p.state != fr.pergens.hamacs.net.PeerGuard.State.Allowed) TextButton(onClick = { guard.set(p.ip, fr.pergens.hamacs.net.PeerGuard.State.Allowed) }) {
                        Text(stringResource(R.string.peers_approve))
                    }
                    if (p.state != fr.pergens.hamacs.net.PeerGuard.State.Blocked) TextButton(onClick = { guard.set(p.ip, fr.pergens.hamacs.net.PeerGuard.State.Blocked) }) {
                        Text(stringResource(R.string.peers_block), color = Danger)
                    }
                }
                TextButton(onClick = { guard.remove(p.ip) }) { HaIcon("mdi:delete-outline", size = 20.dp, tint = TextSecondary) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplaySection() {
    val app = App.instance
    val ctx = LocalContext.current
    val settings by app.config.settings.collectAsState()
    val systemScale = (ctx.resources.configuration.fontScale * 100).roundToInt()
    val custom = settings.textScale > 0f

    // « Supprimer les animations » d'Android activé : conseil et ouverture du réglage.
    var animTick by remember { mutableIntStateOf(0) }
    if (fr.pergens.hamacs.ui.rememberAnimationsOff(animTick)) Group {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HaIcon("mdi:animation-play-outline", size = 22.dp, tint = Dash.colors.accent)
                Text(stringResource(R.string.anim_off_title), Modifier.padding(start = 10.dp), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary))
            }
            Text(fr.pergens.hamacs.ui.animationsHelp(), style = TextStyle(fontSize = 13.sp, color = TextSecondary))
            var how by remember { mutableStateOf(false) }
            fr.pergens.hamacs.ui.NoticeButton(stringResource(R.string.anim_how_title), primary = true) { how = true }
            if (how) fr.pergens.hamacs.ui.AnimationsSheet { how = false; animTick++ }
        }
    }
    Group { AccentPicker(settings.accentColor) { hex -> app.config.update { it.copy(accentColor = hex) } } }
    Group {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.text_size), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary))
            Text(stringResource(R.string.text_size_help), style = TextStyle(fontSize = 13.sp, color = TextSecondary))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(!custom, { app.config.update { it.copy(textScale = 0f) } }, SegmentedButtonDefaults.itemShape(0, 2)) {
                    Text(stringResource(R.string.text_size_system, systemScale))
                }
                SegmentedButton(custom, {
                    app.config.update { it.copy(textScale = if (custom) it.textScale else ctx.resources.configuration.fontScale) }
                }, SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.text_size_custom)) }
            }
        }
        if (custom) {
            SliderRow(
                stringResource(R.string.text_size), null, settings.textScale, 0.8f..1.5f, steps = 13,
                format = { "${(it * 100).roundToInt()} %" },
            ) { v -> app.config.update { it.copy(textScale = (v * 20).roundToInt() / 20f) } }
        }
        // Aperçu : reprend la carte « pièce » du tableau de bord.
        Box(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
            val shape = RoundedCornerShape(Dash.shapes.roundedRadius)
            Column(Modifier.fillMaxWidth().clip(shape).background(Dash.colors.menuBackground).padding(16.dp)) {
                Text(stringResource(R.string.text_preview_title), style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Color.White))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HaIcon("mdi:thermometer", size = 15.dp, tint = Color.White.copy(alpha = 0.7f))
                    Text(stringResource(R.string.text_preview_value), style = TextStyle(fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f)))
                }
            }
        }
        GroupDivider()
        SwitchRow(stringResource(R.string.keep_screen_on), stringResource(R.string.keep_screen_on_help), settings.keepScreenOn) { on ->
            app.config.update { it.copy(keepScreenOn = on) }
        }
    }
    Group {
        SwitchRow(stringResource(R.string.tap_fullscreen), stringResource(R.string.tap_fullscreen_help), settings.tapFullscreen) { on ->
            app.config.update { it.copy(tapFullscreen = on) }
        }
        GroupDivider()
        SwitchRow(stringResource(R.string.fullscreen_fill), stringResource(R.string.fullscreen_fill_help), settings.fullscreenFill) { on ->
            app.config.update { it.copy(fullscreenFill = on) }
        }
        GroupDivider()
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.fullscreen_timeout), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary))
            Text(stringResource(R.string.fullscreen_timeout_help), style = TextStyle(fontSize = 13.sp, color = TextSecondary))
            DelayChips(settings.fullscreenSeconds, listOf(0, 30, 60, 120, 300, 600)) { sec -> app.config.update { it.copy(fullscreenSeconds = sec) } }
        }
        GroupDivider()
        WallSettings(settings)
        // Flux et caméras mémorisés comme illisibles par la tablette : réinitialisable (mise à jour, réglage changé).
        val unplayable by fr.pergens.hamacs.ui.VideoCompat.unplayable.collectAsState()
        if (unplayable.isNotEmpty()) {
            GroupDivider()
            SettingRow(stringResource(R.string.video_memo_title), stringResource(R.string.video_memo_help, unplayable.size)) {
                OutlinedButton(
                    onClick = { fr.pergens.hamacs.ui.VideoCompat.reset() }, Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                ) { Text(stringResource(R.string.video_memo_reset)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun IdleSection(cameraPermissionTick: Int, requestCamera: () -> Unit) {
    val app = App.instance
    val ctx = LocalContext.current
    val settings by app.config.settings.collectAsState()
    val lastMotion by app.presence.lastMotion.collectAsState()
    val granted = remember(cameraPermissionTick) {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }

    Group {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.idle_return), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary))
            Text(stringResource(R.string.idle_return_help), style = TextStyle(fontSize = 13.sp, color = TextSecondary))
            DelayChips(settings.idleSeconds) { sec -> app.config.update { it.copy(idleSeconds = sec) } }
        }
    }
    Group {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.dim_title), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary))
            Text(stringResource(R.string.dim_help), style = TextStyle(fontSize = 13.sp, color = TextSecondary))
            DelayChips(settings.dimSeconds) { sec -> app.config.update { it.copy(dimSeconds = sec) } }
        }
        if (settings.dimSeconds > 0) {
            GroupDivider()
            SliderRow(
                stringResource(R.string.dim_level), null, settings.dimLevel, 0.02f..0.6f, steps = 28,
                format = { "${(it * 100).roundToInt()} %" },
            ) { v -> app.config.update { it.copy(dimLevel = (v * 50).roundToInt() / 50f) } }
        }
    }
    NightGroup()
    Group {
        SwitchRow(stringResource(R.string.presence), stringResource(R.string.presence_help), settings.presenceDetection) { on ->
            app.config.update { it.copy(presenceDetection = on) }
        }
        if (settings.presenceDetection) {
            GroupDivider()
            if (!granted) {
                SettingRow(stringResource(R.string.camera_permission_needed)) {
                    Button(onClick = requestCamera, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.allow)) }
                }
            } else {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Titre et état de la détection sur la même ligne (état à droite).
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.sensitivity), Modifier.weight(1f), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary))
                        val ago = ((now - lastMotion) / 1000).toInt()
                        when {
                            lastMotion == 0L -> StatusChip(TextSecondary, stringResource(R.string.presence_waiting))
                            ago < 3 -> StatusChip(Success, stringResource(R.string.presence_now_detected))
                            else -> StatusChip(TextSecondary, stringResource(R.string.presence_last, ago))
                        }
                    }
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(R.string.sensitivity_low, R.string.sensitivity_medium, R.string.sensitivity_high).forEachIndexed { i, res ->
                            SegmentedButton(
                                settings.presenceSensitivity == i,
                                { app.config.update { it.copy(presenceSensitivity = i) } },
                                SegmentedButtonDefaults.itemShape(i, 3),
                            ) { Text(stringResource(res)) }
                        }
                    }
                    CameraChoice(settings.cameraId) { id -> app.config.update { it.copy(cameraId = id) } }
                }
            }
        }
    }
}

/**
 * Caméra de la tablette (détection de présence, photo des avatars) : automatique ou une caméra précise ; les webcams
 * USB apparaissent comme caméras externes. Liste relue toutes les 3 s (webcam branchée ou débranchée).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CameraChoice(selected: String, onSelect: (String) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var cams by remember { mutableStateOf(fr.pergens.hamacs.presence.Cameras.list(ctx)) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(3_000)
            cams = fr.pergens.hamacs.presence.Cameras.list(ctx)
        }
    }
    val label = @Composable { c: fr.pergens.hamacs.presence.Cameras.Info ->
        val same = cams.filter { it.facing == c.facing }
        val base = stringResource(when (c.facing) {
            fr.pergens.hamacs.presence.Cameras.Facing.Front -> R.string.camera_facing_front
            fr.pergens.hamacs.presence.Cameras.Facing.Back -> R.string.camera_facing_back
            fr.pergens.hamacs.presence.Cameras.Facing.External -> R.string.camera_facing_usb
        })
        if (same.size > 1) "$base ${same.indexOf(c) + 1}" else base
    }
    val auto = fr.pergens.hamacs.presence.Cameras.pick(ctx, null)
    Text(stringResource(R.string.camera_choice), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary))
    if (cams.isEmpty()) {
        Text(stringResource(R.string.camera_none), style = TextStyle(fontSize = 13.sp, color = TextSecondary))
        return
    }
    // Aperçu de la caméra utilisée (même ouverture que la détection de présence, qui continue) : bouton au bout de la
    // ligne des caméras, séparé par un trait.
    var show by remember { mutableStateOf(false) }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        val chip = @Composable { on: Boolean, text: String, id: String ->
            FilterChip(
                selected = on, onClick = { onSelect(id) }, label = { Text(text) }, modifier = Modifier.heightIn(min = 40.dp),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Dash.colors.accent.copy(alpha = 0.22f)),
            )
        }
        val chosenMissing = selected.isNotBlank() && cams.none { it.id == selected }
        chip(selected.isBlank() || chosenMissing, stringResource(R.string.camera_auto, auto?.let { label(it) } ?: "—"), "")
        cams.forEach { c -> chip(selected == c.id, label(c), c.id) }
        Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(28.dp).background(Outline))
        FilterChip(
            selected = show, onClick = { show = !show }, label = { Text(stringResource(R.string.camera_preview)) },
            leadingIcon = { HaIcon(if (show) "mdi:eye-off-outline" else "mdi:eye-outline", size = 18.dp) },
            modifier = Modifier.heightIn(min = 40.dp),
            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Dash.colors.accent.copy(alpha = 0.22f)),
        )
    }
    Text(stringResource(R.string.camera_choice_help), style = TextStyle(fontSize = 13.sp, color = TextSecondary))
    if (show) {
        val view = remember {
            androidx.camera.view.PreviewView(ctx).apply { scaleType = androidx.camera.view.PreviewView.ScaleType.FIT_CENTER }
        }
        androidx.compose.runtime.DisposableEffect(view) {
            fr.pergens.hamacs.App.instance.presence.setPreview(view.surfaceProvider)
            onDispose { fr.pergens.hamacs.App.instance.presence.setPreview(null) }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            androidx.compose.ui.viewinterop.AndroidView(
                { view },
                Modifier.fillMaxWidth(0.6f).then(Modifier.aspectRatio(4f / 3f)).clip(RoundedCornerShape(12.dp)).background(Color.Black),
            )
        }
    }
}

@Composable
private fun VoiceSection(requestMic: () -> Unit) {
    val app = App.instance
    // Système vocal : passerelle (gateway/) ou Home Assistant (satellite Wyoming, pipeline Assist).
    val useHa by app.voice.wyoming.enabled.collectAsState()
    Group {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.voice_system), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary))
            fr.pergens.hamacs.ui.LoginChoice("mdi:server-network", stringResource(R.string.voice_gateway_title), stringResource(R.string.voice_gateway_desc), !useHa) {
                app.voice.wyoming.setEnabled(false)
            }
            fr.pergens.hamacs.ui.LoginChoice("mdi:home-assistant", stringResource(R.string.wyoming_title), stringResource(R.string.wyoming_help), useHa) {
                app.voice.wyoming.setEnabled(true)
            }
        }
    }
    if (useHa) WyomingGroup()
    val ctx = LocalContext.current
    val settings by app.config.settings.collectAsState()
    var url by remember { mutableStateOf(settings.voiceUrl) }
    var saved by remember { mutableStateOf(false) }
    var micTick by remember { mutableLongStateOf(0L) }
    val micGranted = remember(micTick) {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            micTick++
        }
    }

    Group {
        if (!useHa) Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Field(url, { url = it; saved = false }, stringResource(R.string.voice_url), stringResource(R.string.voice_url_hint), stringResource(R.string.voice_help))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { app.config.update { it.copy(voiceUrl = url.trim()) }; app.voice.warmUp(); saved = true },
                    enabled = url.trim() != settings.voiceUrl,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.action_save_connect)) }
                Spacer(Modifier.width(16.dp))
                Feedback(if (saved) stringResource(R.string.saved) else null)
            }
        }
        if (!useHa) GroupDivider()
        SettingRow(stringResource(R.string.mic_access), stringResource(if (micGranted) R.string.granted else R.string.not_granted)) {
            if (micGranted) StatusChip(Success, stringResource(R.string.granted))
            else Button(onClick = requestMic, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.allow)) }
        }
    }
}

@Composable
private fun DashboardSection(
    onEditBlocks: () -> Unit,
    transferMessage: Pair<String, Boolean>?,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onPhone: () -> Unit,
) {
    Group {
        SettingRow(stringResource(R.string.edit_mode), stringResource(R.string.edit_mode_help)) {
            Button(onClick = onEditBlocks, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.edit_mode)) }
        }
        GroupDivider()
        SettingRow(stringResource(R.string.setup_title), stringResource(R.string.setup_relaunch_help)) {
            OutlinedButton(
                onClick = { App.instance.config.startSetup() }, Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
            ) { Text(stringResource(R.string.setup_relaunch)) }
        }
    }
    Group {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.config_transfer), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary))
            Text(stringResource(R.string.config_transfer_help), style = TextStyle(fontSize = 13.sp, color = TextSecondary))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onExport, Modifier.heightIn(min = 48.dp)) {
                    HaIcon("mdi:export-variant", size = 18.dp, tint = Color.Black)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_export))
                }
                OutlinedButton(
                    onClick = onImport, Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                ) {
                    HaIcon("mdi:import", size = 18.dp, tint = TextPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_import))
                }
                OutlinedButton(
                    onClick = onPhone, Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                ) {
                    HaIcon("mdi:cellphone-arrow-down", size = 18.dp, tint = TextPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.transfer_phone))
                }
                // Résultat de l'export / import à droite des boutons.
                Box(Modifier.weight(1f)) { Feedback(transferMessage?.first, transferMessage?.second == true) }
            }
        }
    }
}

@Composable
private fun SecuritySection() {
    val app = App.instance
    var current by remember { mutableStateOf("") }
    var new1 by remember { mutableStateOf("") }
    var new2 by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val wrong = stringResource(R.string.access_code_wrong)
    val mismatch = stringResource(R.string.access_code_mismatch)
    val invalid = stringResource(R.string.access_code_invalid)
    val saved = stringResource(R.string.access_code_saved)
    val digits = { s: String -> s.filter { it.isDigit() }.take(8) }

    val focus = LocalFocusManager.current
    val canChange = current.isNotEmpty() && new1.isNotEmpty() && new2.isNotEmpty()
    val change = {
        focus.clearFocus()
        val error = fr.pergens.hamacs.ui.checkCurrentCode(current, app.config.accessCode(), wrong)
        message = when {
            error != null -> { current = ""; error to true }
            new1.length !in 4..8 -> invalid to true
            new1 != new2 -> mismatch to true
            else -> {
                app.config.setAccessCode(new1)
                current = ""; new1 = ""; new2 = ""
                saved to false
            }
        }
    }

    var open by remember { mutableStateOf(false) }
    ReadinessGroup()
    Group {
        // Enroulé par défaut : un appui sur l'en-tête déroule les champs de modification.
        SettingRow(stringResource(R.string.access_code), stringResource(R.string.access_code_help), onClick = { open = !open }) { Chevron(open) }
        Unfold(open) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CodeFields(current, { current = it; message = null }, new1, { new1 = it; message = null }, new2, { new2 = it; message = null }) { if (canChange) change() }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = change,
                    enabled = canChange,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.access_code_change)) }
                Spacer(Modifier.width(16.dp))
                Feedback(message?.first, message?.second == true)
            }
        }
        }
    }
    MasterCodeGroup()
    KioskGroup()
}

/** Préparer la tablette : résumé des réglages d'Android utiles au mur, et fenêtre qui les règle un par un. */
@Composable
private fun ReadinessGroup() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val checks = remember(tick, open) { fr.pergens.hamacs.ui.Readiness.checks(ctx) }
    val ok = checks.count { it.second }
    Group {
        SettingRow(
            stringResource(R.string.ready_title),
            if (ok == checks.size) stringResource(R.string.ready_all) else stringResource(R.string.ready_summary, ok, checks.size),
            onClick = { open = true },
        ) {
            if (ok == checks.size) StatusChip(Success, stringResource(R.string.relaunch_on))
            else OutlinedButton(
                onClick = { open = true }, Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
            ) { Text(stringResource(R.string.ready_open)) }
        }
    }
    if (open) fr.pergens.hamacs.ui.ReadinessSheet { open = false; tick++ }
}

/** Champs de changement de code : actuel (null = pas de code défini), nouveau et confirmation. */
@Composable
internal fun CodeFields(
    current: String?, onCurrent: (String) -> Unit, new1: String, onNew1: (String) -> Unit,
    new2: String, onNew2: (String) -> Unit, onDone: () -> Unit,
) {
    val digits = { s: String -> s.filter { it.isDigit() }.take(8) }
    val type = KeyboardType.NumberPassword
    if (current != null) Field(current, { onCurrent(digits(it)) }, stringResource(R.string.access_code_current), null, null, secret = true, keyboard = type, next = true)
    Field(new1, { onNew1(digits(it)) }, stringResource(R.string.access_code_new), null, null, secret = true, keyboard = type, next = true)
    Field(new2, { onNew2(digits(it)) }, stringResource(R.string.access_code_confirm), null, null, secret = true, keyboard = type, onDone = onDone)
}

/** Flèche d'un groupe enroulable (vers le bas fermé, vers le haut ouvert). */
@Composable
private fun Chevron(open: Boolean) {
    val angle by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    HaIcon("mdi:chevron-down", Modifier.graphicsLayer { rotationZ = angle }, size = 28.dp, tint = TextSecondary)
}

/** Contenu d'un groupe enroulable : séparateur puis contenu, déroulés en animation. */
@Composable
private fun Unfold(open: Boolean, content: @Composable () -> Unit) {
    androidx.compose.animation.AnimatedVisibility(
        open,
        enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
    ) {
        Column { GroupDivider(); content() }
    }
}

/** Code maître : protège les réglages (et le menu du kiosque). Le code actuel est demandé pour le changer. */
@Composable
private fun MasterCodeGroup() {
    val app = App.instance
    // Relu si le code change ailleurs (« Préparer la tablette »).
    var defined by remember(app.config.masterCodeVersion.intValue) { mutableStateOf(app.config.masterCode().isNotEmpty()) }
    var current by remember { mutableStateOf("") }
    var new1 by remember { mutableStateOf("") }
    var new2 by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val wrong = stringResource(R.string.access_code_wrong)
    val mismatch = stringResource(R.string.access_code_mismatch)
    val invalid = stringResource(R.string.access_code_invalid)
    val saved = stringResource(R.string.access_code_saved)
    val removed = stringResource(R.string.master_code_removed)
    val digits = { s: String -> s.filter { it.isDigit() }.take(8) }
    // Code actuel vérifié avec le compteur d'erreurs (null = bon) ; vidé en cas d'erreur.
    val currentError = {
        if (!defined) null else fr.pergens.hamacs.ui.checkCurrentCode(current, app.config.masterCode(), wrong)?.also { current = "" }
    }

    val focus = LocalFocusManager.current
    val canSave = (!defined || current.isNotEmpty()) && new1.isNotEmpty() && new2.isNotEmpty()
    val save = {
        focus.clearFocus()
        val error = currentError()
        message = when {
            error != null -> error to true
            new1.length !in 4..8 -> invalid to true
            new1 != new2 -> mismatch to true
            else -> {
                app.config.setMasterCode(new1)
                defined = true
                current = ""; new1 = ""; new2 = ""
                saved to false
            }
        }
    }

    var open by remember { mutableStateOf(false) }
    Group {
        // Enroulé par défaut ; l'état (défini ou non) reste visible sur l'en-tête.
        SettingRow(stringResource(R.string.master_code), stringResource(R.string.master_code_help), onClick = { open = !open }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (defined) StatusChip(Success, stringResource(R.string.code_defined)) else StatusChip(TextSecondary, stringResource(R.string.code_undefined))
                Spacer(Modifier.width(12.dp))
                Chevron(open)
            }
        }
        Unfold(open) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CodeFields(current.takeIf { defined }, { current = it; message = null }, new1, { new1 = it; message = null }, new2, { new2 = it; message = null }) { if (canSave) save() }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = save,
                    enabled = canSave,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.master_code_save)) }
                if (defined) {
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(
                        onClick = {
                            focus.clearFocus()
                            val error = currentError()
                            message = if (error != null) error to true else {
                                app.config.setMasterCode("")
                                defined = false
                                current = ""; new1 = ""; new2 = ""
                                removed to false
                            }
                        },
                        enabled = current.isNotEmpty(),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.master_code_remove)) }
                }
                Spacer(Modifier.width(16.dp))
                Feedback(message?.first, message?.second == true)
            }
        }
        }
    }
}

/**
 * Mode kiosque : interrupteur, niveau de verrouillage obtenu (propriétaire de l'appareil ou simple
 * épinglage), commande d'activation à copier, écran d'accueil par défaut et retrait des droits.
 */
@Composable
private fun KioskGroup() {
    val app = App.instance
    val ctx = LocalContext.current
    val settings by app.config.settings.collectAsState()
    val kiosk = fr.pergens.hamacs.kiosk.Kiosk
    // Relu à chaque retour sur l'écran (la commande adb se lance pendant que l'app tourne).
    var tick by remember { mutableIntStateOf(0) }
    val owner = remember(tick) { kiosk.isDeviceOwner(ctx) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            tick++
        }
    }
    var info by remember { mutableStateOf<String?>(null) }
    val copied = stringResource(R.string.kiosk_copied)
    Group {
        SwitchRow(stringResource(R.string.kiosk_title), stringResource(R.string.kiosk_help), settings.kioskEnabled) { on ->
            app.config.update { it.copy(kioskEnabled = on) }
        }
        GroupDivider()
        SettingRow(
            stringResource(if (owner) R.string.kiosk_level_full else R.string.kiosk_level_pinning),
            stringResource(if (owner) R.string.kiosk_level_full_help else R.string.kiosk_level_pinning_help),
        ) {
            StatusChip(if (owner) Success else Warning, stringResource(if (owner) R.string.kiosk_owner_on else R.string.kiosk_owner_off))
        }
        if (!owner) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.kiosk_owner_help), style = TextStyle(fontSize = 13.sp, color = TextSecondary, lineHeight = 18.sp))
                val cmd = kiosk.adbCommand(ctx)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Background).padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(cmd, Modifier.weight(1f).padding(vertical = 10.dp), style = TextStyle(fontSize = 13.sp, color = TextPrimary, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                    TextButton(onClick = {
                        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("adb", cmd))
                        info = copied
                    }) { Text(stringResource(R.string.kiosk_copy)) }
                }
                Feedback(info)
                // Sans ordinateur : QR code scanné sur l'écran de bienvenue après une réinitialisation.
                var qr by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.ready_full_reset_help),
                        Modifier.weight(1f), style = TextStyle(fontSize = 13.sp, color = TextSecondary, lineHeight = 18.sp),
                    )
                    TextButton(onClick = { qr = !qr }) { Text(stringResource(R.string.whats_new_show)) }
                }
                if (qr) fr.pergens.hamacs.ui.ProvisioningQr()
            }
            GroupDivider()
            SettingRow(stringResource(R.string.kiosk_home), stringResource(R.string.kiosk_home_help)) {
                OutlinedButton(
                    onClick = { runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_HOME_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                    Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                ) { Text(stringResource(R.string.kiosk_home_action)) }
            }
        } else {
            GroupDivider()
            SettingRow(stringResource(R.string.kiosk_remove_owner), stringResource(R.string.kiosk_remove_owner_help)) {
                OutlinedButton(
                    onClick = {
                        (ctx as? android.app.Activity)?.let { kiosk.removeDeviceOwner(it) }
                        app.config.update { it.copy(kioskEnabled = false) }
                        tick++
                    },
                    Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
                ) { Text(stringResource(R.string.kiosk_remove)) }
            }
        }
        // Relance après un plantage : possible si l'app est propriétaire, écran d'accueil ou autorisée à s'afficher
        // par-dessus les autres apps (sinon Android bloque l'ouverture depuis l'arrière-plan).
        val relaunch = remember(tick) { fr.pergens.hamacs.CrashGuard.canRelaunch(ctx) }
        GroupDivider()
        SettingRow(stringResource(R.string.relaunch_title), stringResource(R.string.relaunch_help)) {
            if (relaunch) StatusChip(Success, stringResource(R.string.relaunch_on))
            else OutlinedButton(
                onClick = {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${ctx.packageName}"))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
            ) { Text(stringResource(R.string.relaunch_allow)) }
        }
    }
}

/** Langue de l'app : celle du système ou une langue traduite ; l'app redémarre pour l'appliquer. */
@Composable
private fun LanguageRow() {
    val ctx = LocalContext.current
    val current = remember { fr.pergens.hamacs.config.AppLocale.chosen(ctx) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.language), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary))
        Text(stringResource(R.string.language_help), style = TextStyle(fontSize = 13.sp, color = TextSecondary, lineHeight = 18.sp))
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf("") + fr.pergens.hamacs.config.AppLocale.SUPPORTED).forEach { tag ->
                FilterChip(
                    selected = tag == current,
                    onClick = { if (tag != current) (ctx as? android.app.Activity)?.let { fr.pergens.hamacs.config.AppLocale.set(it, tag) } },
                    label = { Text(if (tag.isEmpty()) stringResource(R.string.language_system) else fr.pergens.hamacs.config.AppLocale.displayName(tag)) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Dash.colors.accent.copy(alpha = 0.22f)),
                )
            }
        }
    }
}

@Composable
private fun AboutSection(onPhone: () -> Unit) {
    Group {
        SettingRow(stringResource(R.string.version)) {
            Text(BuildConfig.VERSION_NAME, style = TextStyle(fontSize = 15.sp, color = TextSecondary))
        }
        GroupDivider()
        LanguageRow()
        GroupDivider()
        SettingRow(stringResource(R.string.licenses), stringResource(R.string.licenses_text))
    }
    UpdateGroup()
    CrashLogGroup(onPhone)
    Spacer(Modifier.height(8.dp))
}

/** Mises à jour (Releases GitHub) : état, installation, installation automatique. */
@Composable
private fun UpdateGroup() {
    val updater = App.instance.updater
    val state by updater.state.collectAsState()
    var auto by remember { mutableStateOf(updater.autoInstall) }
    var pre by remember { mutableStateOf(updater.prereleases) }
    val owner = remember { fr.pergens.hamacs.kiosk.Kiosk.isDeviceOwner(App.instance) }
    // Vérifie en ouvrant la section (si rien n'est en cours).
    LaunchedEffect(Unit) { if (state is UpdateState.Idle) updater.check() }
    Group {
        val text = when (val s = state) {
            UpdateState.Idle, UpdateState.Checking -> stringResource(R.string.update_checking)
            UpdateState.UpToDate -> stringResource(R.string.update_up_to_date, BuildConfig.VERSION_NAME)
            is UpdateState.Available -> stringResource(if (s.release.prerelease) R.string.update_available_pre else R.string.update_available, s.release.version)
            is UpdateState.Downloading -> stringResource(R.string.update_downloading, s.release.version, (s.progress * 100).toInt())
            is UpdateState.Installing -> stringResource(R.string.update_installing, s.release.version)
            is UpdateState.Failed -> stringResource(R.string.update_failed, s.message)
        }
        SettingRow(stringResource(R.string.update_title), text) {
            when (val s = state) {
                is UpdateState.Available -> Button(onClick = { updater.install(s.release) }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.update_install)) }
                is UpdateState.Downloading, is UpdateState.Installing, UpdateState.Checking -> CircularProgressIndicator(Modifier.size(24.dp), color = Dash.colors.accent, strokeWidth = 2.5.dp)
                else -> OutlinedButton(
                    onClick = { updater.check() }, Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                ) { Text(stringResource(R.string.update_check)) }
            }
        }
        (state as? UpdateState.Available)?.release?.notes?.takeIf { it.isNotBlank() }?.let { notes ->
            fr.pergens.hamacs.ui.MarkdownText(
                notes,
                Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp).heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                fontSize = 13,
            )
        }
        GroupDivider()
        // Nouveautés de la version installée (celles affichées une fois après la mise à jour).
        var notes by remember { mutableStateOf(false) }
        SettingRow(stringResource(R.string.whats_new_row), stringResource(R.string.whats_new_row_help, BuildConfig.VERSION_NAME), onClick = { notes = true }) {
            OutlinedButton(
                onClick = { notes = true }, Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
            ) { Text(stringResource(R.string.whats_new_show)) }
        }
        if (notes) fr.pergens.hamacs.update.WhatsNewSheet(BuildConfig.VERSION_NAME) { notes = false }
        GroupDivider()
        SwitchRow(
            stringResource(R.string.update_auto),
            stringResource(if (owner) R.string.update_auto_help_owner else R.string.update_auto_help),
            auto,
        ) { auto = it; updater.autoInstall = it }
        GroupDivider()
        // Canal : versions stables seules, ou aussi les préversions (nouvelle vérification au changement).
        SwitchRow(stringResource(R.string.update_prereleases), stringResource(R.string.update_prereleases_help), pre) {
            pre = it; updater.prereleases = it; updater.check()
        }
    }
}

/** Journal des plantages (l'app se relance seule) : enroulé, à copier pour le signaler, ou à effacer. */
@Composable
private fun CrashLogGroup(onPhone: () -> Unit) {
    val ctx = LocalContext.current
    var entries by remember { mutableStateOf(fr.pergens.hamacs.CrashGuard.entries(ctx)) }
    var open by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Group {
        val last = entries.firstOrNull()?.substringBefore(" · ")
        SettingRow(
            stringResource(R.string.crash_log),
            if (last == null) stringResource(R.string.crash_log_none) else pluralStringResource(R.plurals.crash_log_count, entries.size, entries.size, last),
            onClick = if (entries.isEmpty()) null else ({ open = !open }),
        ) { if (entries.isNotEmpty()) Chevron(open) }
        Unfold(open && entries.isNotEmpty()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    entries.joinToString("\n\n"),
                    Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(10.dp)).background(Background)
                        .verticalScroll(rememberScrollState()).padding(12.dp),
                    style = TextStyle(fontSize = 11.sp, color = TextSecondary, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, lineHeight = 15.sp),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(entries.joinToString("\n\n"))); copied = true }, Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.kiosk_copy))
                    }
                    OutlinedButton(
                        onClick = { fr.pergens.hamacs.CrashGuard.clear(ctx); entries = emptyList(); open = false },
                        Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    ) { Text(stringResource(R.string.crash_log_clear)) }
                    // Journal aussi téléchargeable depuis un téléphone (page de sauvegarde par QR code).
                    OutlinedButton(
                        onClick = onPhone, Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    ) {
                        HaIcon("mdi:cellphone-arrow-down", size = 18.dp, tint = TextPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.transfer_phone))
                    }
                    Feedback(if (copied) stringResource(R.string.crash_log_copied) else null)
                }
            }
        }
    }
}
