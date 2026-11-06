package fr.pergens.hamacs.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
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
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import fr.pergens.hamacs.App
import fr.pergens.hamacs.R
import fr.pergens.hamacs.kiosk.Kiosk
import fr.pergens.hamacs.ui.components.HaIcon
import fr.pergens.hamacs.ui.theme.Dash

/**
 * Animations du système : l'option « Supprimer les animations » d'Android (Accessibilité) ou une échelle à 0 dans les
 * options pour les développeurs rend toutes les animations de l'app instantanées. L'app ne les force pas : elle le
 * signale et ouvre l'écran d'Android où les réactiver.
 */
object SystemAnimations {
    fun off(ctx: Context): Boolean =
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    fun developerOptions(ctx: Context): Boolean =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    /** Ouvre l'accessibilité (ou les options pour les développeurs), en sortant du kiosque le temps du réglage. */
    fun open(activity: Activity, developer: Boolean) {
        val intent = Intent(if (developer) Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS else Settings.ACTION_ACCESSIBILITY_SETTINGS)
        if (Kiosk.isLocked(activity)) Kiosk.suspend(activity, intent)
        else runCatching { activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
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

/** État relu à chaque retour dans l'app (l'utilisateur revient des réglages d'Android). */
@Composable
fun rememberAnimationsOff(): Boolean {
    val ctx = LocalContext.current
    var off by remember { mutableStateOf(SystemAnimations.off(ctx)) }
    LifecycleResumeEffect(Unit) {
        off = SystemAnimations.off(ctx)
        onPauseOrDispose { }
    }
    return off
}

/** Boutons d'ouverture : accessibilité, et options pour les développeurs si elles sont activées. */
@Composable
fun AnimationsButtons(beforeOpen: ((() -> Unit) -> Unit)? = null, extra: (@Composable () -> Unit)? = null) {
    val ctx = LocalContext.current
    val activity = SystemAnimations.activity(ctx)
    fun open(developer: Boolean) {
        val run = { activity?.let { SystemAnimations.open(it, developer) }; Unit }
        beforeOpen?.invoke(run) ?: run()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        NoticeButton(stringResource(R.string.anim_off_open), primary = true) { open(false) }
        if (SystemAnimations.developerOptions(ctx)) NoticeButton(stringResource(R.string.anim_off_dev), primary = false) { open(true) }
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
 * Au démarrage, animations coupées par Android : bandeau en bas avec le conseil et l'ouverture du réglage. En kiosque,
 * le code maître (sinon le code d'accès) est demandé avant de quitter l'app.
 */
@Composable
fun AnimationsNotice(state: DashState) {
    val off = rememberAnimationsOff()
    var later by remember { mutableStateOf(false) }
    if (!off || later) return
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
                Text(stringResource(R.string.anim_off_help), style = TextStyle(fontSize = 14.sp, color = Color.White, lineHeight = 20.sp))
                AnimationsButtons(
                    beforeOpen = { run ->
                        val app = App.instance
                        val code = app.config.masterCode().ifEmpty { app.config.accessCode() }
                        if (app.config.settings.value.kioskEnabled && code.isNotEmpty()) {
                            state.pin = PinRequest(app.getString(R.string.kiosk_pin_title), code) { run() }
                        } else run()
                    },
                    extra = { NoticeButton(stringResource(R.string.anim_off_later), primary = false) { later = true } },
                )
            }
        }
    }
}
