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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import fr.pergens.hamacs.App
import fr.pergens.hamacs.R
import fr.pergens.hamacs.kiosk.Kiosk
import fr.pergens.hamacs.ui.components.HaIcon
import fr.pergens.hamacs.ui.theme.Dash

/**
 * Animations du système coupées : toutes les animations de l'app deviennent instantanées. Deux causes :
 * - [Cause.Accessibility] : option « Supprimer les animations » (Samsung : `remove_animations` dans les réglages système) ;
 * - [Cause.Developer] : échelles d'animation à 0 dans les options pour les développeurs.
 * L'app ouvre le bon écran ; avec l'autorisation WRITE_SECURE_SETTINGS (donnée une fois par adb), elle remet
 * directement les échelles à 1.
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

    /** Autorisation d'écrire les réglages globaux (donnée par adb) : réactivation directe possible. */
    fun canFix(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun grantCommand(ctx: Context) = "adb shell pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS"

    /** Remet les trois échelles d'animation à 1 ; false si refusé. */
    fun fix(ctx: Context): Boolean = runCatching {
        SCALES.forEach { Settings.Global.putFloat(ctx.contentResolver, it, 1f) }
        true
    }.getOrDefault(false)

    /** Ouvre l'écran où se trouve le réglage, en sortant du kiosque le temps du réglage. */
    fun open(activity: Activity, cause: Cause) {
        val intent = if (cause == Cause.Developer) {
            // Ligne de l'échelle mise en avant quand les réglages d'Android le permettent.
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).putExtra(":settings:fragment_args_key", "animator_duration_scale")
        } else Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
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

/** État relu à chaque retour dans l'app (l'utilisateur revient des réglages d'Android) ; [tick] force une relecture. */
@Composable
fun rememberAnimationsOff(tick: Int = 0): Boolean {
    val ctx = LocalContext.current
    var off by remember { mutableStateOf(SystemAnimations.off(ctx)) }
    LifecycleResumeEffect(tick) {
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

/**
 * Boutons : « Réactiver les animations » si l'app en a le droit, sinon ouverture du bon écran d'Android.
 * [beforeOpen] : étape avant de quitter l'app (code en kiosque) ; [onFixed] : animations réactivées.
 */
@Composable
fun AnimationsButtons(beforeOpen: ((() -> Unit) -> Unit)? = null, onFixed: () -> Unit = {}, extra: (@Composable () -> Unit)? = null) {
    val ctx = LocalContext.current
    val activity = SystemAnimations.activity(ctx)
    val cause = SystemAnimations.cause(ctx)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (SystemAnimations.canFix(ctx)) {
            NoticeButton(stringResource(R.string.anim_off_fix), primary = true) { if (SystemAnimations.fix(ctx)) onFixed() }
        } else {
            NoticeButton(stringResource(if (cause == SystemAnimations.Cause.Developer) R.string.anim_off_dev else R.string.anim_off_open), primary = true) {
                val run = { activity?.let { SystemAnimations.open(it, cause) }; Unit }
                beforeOpen?.invoke(run) ?: run()
            }
        }
        extra?.invoke()
    }
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
 * Au démarrage, animations coupées : bandeau en bas avec le conseil et le bouton adapté. Seulement sur le tableau de
 * bord (ni sur l'assistant ni sur les réglages). En kiosque, le code maître (sinon d'accès) est demandé avant de quitter.
 */
@Composable
fun AnimationsNotice(state: DashState) {
    var tick by remember { mutableIntStateOf(0) }
    val off = rememberAnimationsOff(tick)
    var later by remember { mutableStateOf(false) }
    val setup by App.instance.config.setupPending.collectAsState()
    if (!off || later || setup || state.showSettings) return
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
                AnimationsButtons(
                    beforeOpen = { run ->
                        val app = App.instance
                        val code = app.config.masterCode().ifEmpty { app.config.accessCode() }
                        if (app.config.settings.value.kioskEnabled && code.isNotEmpty()) {
                            state.pin = PinRequest(app.getString(R.string.kiosk_pin_title), code) { run() }
                        } else run()
                    },
                    onFixed = { tick++ },
                    extra = { NoticeButton(stringResource(R.string.anim_off_later), primary = false) { later = true } },
                )
            }
        }
    }
}
