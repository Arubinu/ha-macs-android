package fr.pergens.hamacs.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import fr.pergens.hamacs.App
import fr.pergens.hamacs.R
import fr.pergens.hamacs.kiosk.Kiosk
import fr.pergens.hamacs.ui.components.HaIcon
import fr.pergens.hamacs.ui.components.tapHold
import fr.pergens.hamacs.ui.theme.Dash

/**
 * Animations du système coupées : toutes les animations de l'app deviennent instantanées. Deux causes :
 * - [Cause.Accessibility] : option « Supprimer les animations » (Samsung : `remove_animations` dans les réglages système) ;
 * - [Cause.Developer] : échelles d'animation à 0 dans les options pour les développeurs.
 *
 * Deux façons d'y remédier, au choix de l'utilisateur ([AnimationsSheet]) :
 * - automatiquement : l'autorisation WRITE_SECURE_SETTINGS ne s'accorde que depuis un ordinateur (adb, une fois ;
 *   aucune page d'Android ne la propose) ; ensuite l'app remet les échelles à 1 d'elle-même ;
 * - à la main : étapes guidées et ouverture des bons écrans d'Android, sans aucune autorisation.
 */
object SystemAnimations {
    enum class Cause { Accessibility, Developer }

    private val SCALES = listOf(
        Settings.Global.ANIMATOR_DURATION_SCALE, Settings.Global.TRANSITION_ANIMATION_SCALE, Settings.Global.WINDOW_ANIMATION_SCALE,
    )

    fun off(ctx: Context): Boolean =
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    fun cause(ctx: Context): Cause =
        if (runCatching { Settings.System.getInt(ctx.contentResolver, "remove_animations", 0) }.getOrDefault(0) == 1) Cause.Accessibility
        else Cause.Developer

    fun developerOptionsShown(ctx: Context): Boolean =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    /** Autorisation d'écrire les réglages globaux (donnée par adb) : réactivation par l'app. */
    fun canFix(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun grantCommand(ctx: Context) = "adb shell pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS"

    /** Remet les trois échelles d'animation à 1 ; false si refusé. */
    fun fix(ctx: Context): Boolean = runCatching {
        SCALES.forEach { Settings.Global.putFloat(ctx.contentResolver, it, 1f) }
        true
    }.getOrDefault(false)

    enum class Screen { Accessibility, Developer, About }

    /** Ouvre un écran des Paramètres (tâche neuve), en sortant du kiosque le temps du réglage. */
    fun open(activity: Activity, screen: Screen) {
        val intent = when (screen) {
            // Ligne de l'échelle mise en avant quand les réglages d'Android le permettent.
            Screen.Developer -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).putExtra(":settings:fragment_args_key", "animator_duration_scale")
            Screen.Accessibility -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            Screen.About -> Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
        }
        if (Kiosk.isLocked(activity)) Kiosk.suspend(activity, intent)
        // Tâche neuve : sinon les Paramètres déjà ouverts reprennent leur dernier écran (Samsung).
        else runCatching { activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) }
    }

    fun activity(ctx: Context): Activity? {
        var c: Context? = ctx
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}

/**
 * État relu à chaque retour dans l'app (l'utilisateur revient des réglages d'Android) ; [tick] force une relecture.
 * Avec l'autorisation, les animations sont réactivées sur-le-champ (rien n'est alors signalé).
 */
@Composable
fun rememberAnimationsOff(tick: Int = 0): Boolean {
    val ctx = LocalContext.current
    var off by remember { mutableStateOf(SystemAnimations.off(ctx)) }
    LifecycleResumeEffect(tick) {
        if (SystemAnimations.off(ctx) && SystemAnimations.canFix(ctx)) SystemAnimations.fix(ctx)
        off = SystemAnimations.off(ctx)
        onPauseOrDispose { }
    }
    return off
}

/** Texte d'explication selon la cause. */
@Composable
fun animationsHelp(): String {
    val ctx = LocalContext.current
    return stringResource(if (SystemAnimations.cause(ctx) == SystemAnimations.Cause.Developer) R.string.anim_off_help_dev else R.string.anim_off_help)
}

@Composable
fun NoticeButton(text: String, primary: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val c = Dash.colors.accent
    Text(
        text,
        Modifier.heightIn(min = 44.dp).clip(shape)
            .background(if (primary) c else Color.Transparent, shape)
            .border(1.dp, if (primary) Color.Transparent else c, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (primary) Color.Black else c),
    )
}

/**
 * Fenêtre « Réactiver les animations » : réactivation automatique (autorisation donnée une fois depuis un ordinateur)
 * ou réglages à changer soi-même, guidés pas à pas. [beforeOpen] : étape avant de quitter l'app (code en kiosque).
 */
@Composable
fun AnimationsSheet(beforeOpen: ((() -> Unit) -> Unit)? = null, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val activity = SystemAnimations.activity(ctx)
    var tick by remember { mutableIntStateOf(0) }
    val off = rememberAnimationsOff(tick)
    var granted by remember { mutableStateOf(SystemAnimations.canFix(ctx)) }
    LifecycleResumeEffect(Unit) { granted = SystemAnimations.canFix(ctx); onPauseOrDispose { } }
    // Animations revenues (à la main ou par l'app) : la fenêtre se ferme d'elle-même.
    LaunchedEffect(off) { if (!off) onDismiss() }
    var manual by remember { mutableStateOf(!granted) }
    val cause = SystemAnimations.cause(ctx)
    fun open(screen: SystemAnimations.Screen) {
        val run = { activity?.let { SystemAnimations.open(it, screen) }; Unit }
        beforeOpen?.invoke(run) ?: run()
    }
    val muted = Color(0xFFA3A3AD)
    val body = TextStyle(fontSize = 14.sp, color = Color.White, lineHeight = 20.sp)
    Sheet(onDismiss, 0.55f) {
        Box(
            Modifier.align(Alignment.TopEnd).size(44.dp).clip(CircleShape).tapHold(onDismiss),
            contentAlignment = Alignment.Center,
        ) { HaIcon("mdi:close", size = 26.dp, tint = Color(255, 255, 255, 200)) }
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.anim_how_title), Modifier.padding(end = 52.dp), style = TextStyle(fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Medium))
            Text(animationsHelp(), style = TextStyle(fontSize = 13.sp, color = muted, lineHeight = 19.sp))
            LoginChoice("mdi:auto-fix", stringResource(R.string.anim_choice_auto), stringResource(R.string.anim_choice_auto_desc), !manual) { manual = false }
            LoginChoice("mdi:gesture-tap", stringResource(R.string.anim_choice_manual), stringResource(R.string.anim_choice_manual_desc), manual) { manual = true }
            if (!manual) {
                if (granted) {
                    Text(stringResource(R.string.anim_granted), style = body)
                    NoticeButton(stringResource(R.string.anim_off_fix), primary = true) { SystemAnimations.fix(ctx); tick++ }
                } else {
                    Text(stringResource(R.string.anim_auto_step1), style = body)
                    Text(stringResource(R.string.anim_auto_step2), style = body)
                    val cmd = SystemAnimations.grantCommand(ctx)
                    var copied by remember { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF0E0E12)).padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SelectionContainer(Modifier.weight(1f)) {
                            Text(cmd, style = TextStyle(fontSize = 13.sp, color = Dash.colors.accent, fontFamily = FontFamily.Monospace))
                        }
                        NoticeButton(stringResource(if (copied) R.string.anim_copied else R.string.anim_copy), primary = false) {
                            val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                            cm?.setPrimaryClip(android.content.ClipData.newPlainText("adb", cmd))
                            copied = true
                        }
                    }
                    Text(stringResource(R.string.anim_auto_step3), style = body)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFFB74D)))
                        Text(stringResource(R.string.anim_not_granted), Modifier.padding(start = 8.dp), style = TextStyle(fontSize = 13.sp, color = muted))
                    }
                }
            } else {
                if (cause == SystemAnimations.Cause.Accessibility) {
                    Text(stringResource(R.string.anim_manual_acc), style = body)
                    NoticeButton(stringResource(R.string.anim_off_open), primary = true) { open(SystemAnimations.Screen.Accessibility) }
                } else {
                    // Options pour les développeurs cachées : comment les afficher d'abord (étape 1).
                    val hidden = !SystemAnimations.developerOptionsShown(ctx)
                    if (hidden) {
                        Text(stringResource(R.string.anim_manual_dev_show), style = body)
                        NoticeButton(stringResource(R.string.anim_open_about), primary = false) { open(SystemAnimations.Screen.About) }
                    }
                    Text((if (hidden) "2. " else "") + stringResource(R.string.anim_manual_dev_scales), style = body)
                    NoticeButton(stringResource(R.string.anim_off_dev), primary = true) { open(SystemAnimations.Screen.Developer) }
                }
                Text(stringResource(R.string.anim_names_vary), style = TextStyle(fontSize = 12.sp, color = muted))
            }
        }
    }
}

/**
 * Animations coupées : bandeau en bas du tableau de bord (ni sur l'assistant ni sur les réglages), qui ouvre la fenêtre
 * guidée. En kiosque, le code maître (sinon d'accès) est demandé avant de quitter l'app.
 */
@Composable
fun AnimationsNotice(state: DashState) {
    val off = rememberAnimationsOff()
    var later by remember { mutableStateOf(false) }
    var how by remember { mutableStateOf(false) }
    val setup by App.instance.config.setupPending.collectAsState()
    if (!off || later || setup || state.showSettings) return
    if (how) AnimationsSheet(beforeOpen = { run ->
        val app = App.instance
        val code = app.config.masterCode().ifEmpty { app.config.accessCode() }
        if (app.config.settings.value.kioskEnabled && code.isNotEmpty()) {
            state.pin = PinRequest(app.getString(R.string.kiosk_pin_title), code) { run() }
        } else run()
    }) { how = false }
    Box(Modifier.fillMaxSize().padding(bottom = 28.dp), contentAlignment = Alignment.BottomCenter) {
        val shape = RoundedCornerShape(18.dp)
        Row(
            Modifier.widthIn(max = 900.dp).clip(shape).background(Color(0xF21C1C21), shape)
                .border(1.dp, Dash.colors.accent.copy(alpha = 0.6f), shape)
                .padding(horizontal = 22.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HaIcon("mdi:animation-play-outline", size = 30.dp, tint = Dash.colors.accent)
            Column(Modifier.padding(start = 16.dp).weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.anim_off_title), style = TextStyle(fontSize = 16.sp, color = Dash.colors.accent, fontWeight = FontWeight.SemiBold))
                Text(animationsHelp(), style = TextStyle(fontSize = 14.sp, color = Color.White, lineHeight = 20.sp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NoticeButton(stringResource(R.string.anim_how_title), primary = true) { how = true }
                    NoticeButton(stringResource(R.string.anim_off_later), primary = false) { later = true }
                }
            }
        }
    }
}
