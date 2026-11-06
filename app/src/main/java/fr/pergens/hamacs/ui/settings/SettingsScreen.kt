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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
                                Section.Dashboard -> DashboardSection(onEditBlocks, transferMessage, onExport, onImport)
                                Section.Security -> SecuritySection()
                                Section.About -> AboutSection()
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
private fun Field(value: String, onChange: (String) -> Unit, label: String, hint: String?, help: String?, secret: Boolean = false, keyboard: KeyboardType = KeyboardType.Uri) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onChange,
        Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = hint?.let { { Text(it) } },
        supportingText = help?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
        trailingIcon = if (secret) {
            {
                TextButton(onClick = { visible = !visible }) {
                    Text(stringResource(if (visible) R.string.hide else R.string.show))
                }
            }
        } else null,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Dash.colors.accent, unfocusedBorderColor = Outline),
        shape = RoundedCornerShape(12.dp),
    )
}

/** Choix d'un délai : Jamais, 30 s, 1, 2, 5, 10 ou 30 min. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DelayChips(selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0, 30, 60, 120, 300, 600, 1800).forEach { sec ->
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplaySection() {
    val app = App.instance
    val ctx = LocalContext.current
    val settings by app.config.settings.collectAsState()
    val systemScale = (ctx.resources.configuration.fontScale * 100).roundToInt()
    val custom = settings.textScale > 0f

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
                    Text(stringResource(R.string.sensitivity), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(R.string.sensitivity_low, R.string.sensitivity_medium, R.string.sensitivity_high).forEachIndexed { i, res ->
                            SegmentedButton(
                                settings.presenceSensitivity == i,
                                { app.config.update { it.copy(presenceSensitivity = i) } },
                                SegmentedButtonDefaults.itemShape(i, 3),
                            ) { Text(stringResource(res)) }
                        }
                    }
                    val ago = ((now - lastMotion) / 1000).toInt()
                    when {
                        lastMotion == 0L -> StatusChip(TextSecondary, stringResource(R.string.presence_waiting))
                        ago < 3 -> StatusChip(Success, stringResource(R.string.presence_now_detected))
                        else -> StatusChip(TextSecondary, stringResource(R.string.presence_last, ago))
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceSection(requestMic: () -> Unit) {
    val app = App.instance
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
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        GroupDivider()
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
                // Résultat de l'export / import à droite des boutons.
                Box(Modifier.weight(1f)) { Feedback(transferMessage?.first, transferMessage?.second == true) }
            }
        }
    }
}

/**
 * Vérifie un code actuel saisi dans les réglages avec le même compteur d'erreurs que le pavé de code :
 * renvoie le message d'erreur (blocage ou code incorrect), ou null si le code est bon.
 */
private fun checkCurrentCode(entered: String, expected: String, wrong: String): String? {
    val config = App.instance.config
    val until = config.pinLockedUntil()
    fun lockedMessage(until: Long): String {
        val s = ((until - System.currentTimeMillis() + 999) / 1000).toInt().coerceAtLeast(1)
        return App.instance.getString(R.string.pin_locked, "%d:%02d".format(s / 60, s % 60))
    }
    if (until > 0L) return lockedMessage(until)
    if (entered == expected) { config.pinSucceeded(); return null }
    config.pinFailed()
    val next = config.pinLockedUntil()
    return if (next > 0L) lockedMessage(next) else wrong
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

    Group {
        SettingRow(stringResource(R.string.access_code), stringResource(R.string.access_code_help))
        GroupDivider()
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Field(current, { current = digits(it); message = null }, stringResource(R.string.access_code_current), null, null, secret = true)
            Field(new1, { new1 = digits(it); message = null }, stringResource(R.string.access_code_new), null, null, secret = true)
            Field(new2, { new2 = digits(it); message = null }, stringResource(R.string.access_code_confirm), null, null, secret = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        val error = checkCurrentCode(current, app.config.accessCode(), wrong)
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
                    },
                    enabled = current.isNotEmpty() && new1.isNotEmpty() && new2.isNotEmpty(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.access_code_change)) }
                Spacer(Modifier.width(16.dp))
                Feedback(message?.first, message?.second == true)
            }
        }
    }
    MasterCodeGroup()
    KioskGroup()
}

/** Code maître : protège les réglages (et le menu du kiosque). Le code actuel est demandé pour le changer. */
@Composable
private fun MasterCodeGroup() {
    val app = App.instance
    var defined by remember { mutableStateOf(app.config.masterCode().isNotEmpty()) }
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
        if (!defined) null else checkCurrentCode(current, app.config.masterCode(), wrong)?.also { current = "" }
    }

    Group {
        SettingRow(stringResource(R.string.master_code), stringResource(R.string.master_code_help))
        GroupDivider()
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!defined) Text(stringResource(R.string.master_code_none), color = TextSecondary, fontSize = 14.sp)
            if (defined) Field(current, { current = digits(it); message = null }, stringResource(R.string.access_code_current), null, null, secret = true)
            Field(new1, { new1 = digits(it); message = null }, stringResource(R.string.access_code_new), null, null, secret = true)
            Field(new2, { new2 = digits(it); message = null }, stringResource(R.string.access_code_confirm), null, null, secret = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
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
                    },
                    enabled = (!defined || current.isNotEmpty()) && new1.isNotEmpty() && new2.isNotEmpty(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.master_code_save)) }
                if (defined) {
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(
                        onClick = {
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
private fun AboutSection() {
    Group {
        SettingRow(stringResource(R.string.version)) {
            Text(BuildConfig.VERSION_NAME, style = TextStyle(fontSize = 15.sp, color = TextSecondary))
        }
        GroupDivider()
        LanguageRow()
        GroupDivider()
        SettingRow(stringResource(R.string.licenses), stringResource(R.string.licenses_text))
    }
    Spacer(Modifier.height(8.dp))
}
