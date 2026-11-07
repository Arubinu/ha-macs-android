package fr.pergens.hamacs.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.asImageBitmap
import fr.pergens.hamacs.BuildConfig
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import fr.pergens.hamacs.App
import fr.pergens.hamacs.CrashGuard
import fr.pergens.hamacs.R
import fr.pergens.hamacs.kiosk.Kiosk
import fr.pergens.hamacs.ui.components.HaIcon
import fr.pergens.hamacs.ui.components.tapHold
import fr.pergens.hamacs.update.scrollFade

/**
 * Tablette prête pour le mur : les réglages d'Android qui comptent pour un tableau de bord toujours affiché, réunis sur
 * un écran (kiosque, verrouillage par écran d'accueil ou propriétaire de l'appareil, relance après plantage, batterie,
 * animations). Tout reste facultatif ; chaque point ouvre l'écran d'Android qui le règle.
 */
object Readiness {
    enum class Check { Kiosk, Lock, Relaunch, Battery, Animations }

    fun isHome(ctx: Context): Boolean =
        ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName == ctx.packageName

    fun batteryFree(ctx: Context): Boolean =
        runCatching { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }.getOrDefault(true)

    /**
     * Points affichés. Verrouillage : prêt avec l'un des deux niveaux, écran d'accueil (simple, réglé sur la tablette)
     * ou propriétaire de l'appareil (complet, qui impose aussi l'écran d'accueil).
     */
    fun checks(ctx: Context): List<Pair<Check, Boolean>> = listOf(
        Check.Kiosk to App.instance.config.settings.value.kioskEnabled,
        Check.Lock to (Kiosk.isDeviceOwner(ctx) || isHome(ctx)),
        Check.Relaunch to CrashGuard.canRelaunch(ctx),
        Check.Battery to batteryFree(ctx),
        Check.Animations to !SystemAnimations.off(ctx),
    )

    fun missing(ctx: Context) = checks(ctx).count { !it.second }

    /** Écran d'Android à ouvrir ; en kiosque, le verrouillage est suspendu le temps du réglage. */
    fun open(activity: Activity, intent: Intent) {
        if (Kiosk.isLocked(activity)) Kiosk.suspend(activity, intent)
        else runCatching { activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    @android.annotation.SuppressLint("BatteryLife")
    fun batteryIntent(ctx: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))

    fun overlayIntent(ctx: Context) =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
}

/** Fenêtre « Préparer la tablette » : un point par ligne, coche verte ou action. Relue au retour et toutes les 2 s (adb). */
@Composable
fun ReadinessSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val activity = SystemAnimations.activity(ctx)
    val app = App.instance
    val settings by app.config.settings.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(2000); tick++ } }
    val checks = remember(tick, settings.kioskEnabled) { Readiness.checks(ctx) }
    var anim by remember { mutableStateOf(false) }
    // Verrouillage complet : 0 = replié, 1 = avec un ordinateur (adb), 2 = sans ordinateur (QR code).
    var how by remember { mutableIntStateOf(0) }
    val isOwner = remember(tick) { Kiosk.isDeviceOwner(ctx) }
    val isHome = remember(tick) { Readiness.isHome(ctx) }
    val muted = Color(0xFFA3A3AD)
    if (anim) AnimationsSheet { anim = false; tick++ }
    Sheet(onDismiss, 0.55f) {
        Box(
            Modifier.align(Alignment.TopEnd).size(44.dp).clip(CircleShape).tapHold(onDismiss),
            contentAlignment = Alignment.Center,
        ) { HaIcon("mdi:close", size = 26.dp, tint = Color(255, 255, 255, 200)) }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.ready_title), Modifier.padding(end = 52.dp), style = TextStyle(fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Medium))
            val ok = checks.count { it.second }
            Text(
                stringResource(R.string.ready_help) + " " +
                    (if (ok == checks.size) stringResource(R.string.ready_all) else stringResource(R.string.ready_summary, ok, checks.size)),
                style = TextStyle(fontSize = 13.sp, color = muted, lineHeight = 19.sp),
            )
            val scroll = rememberScrollState()
            Column(
                Modifier.fillMaxWidth().heightIn(max = 620.dp).scrollFade(scroll).verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
            checks.forEach { (check, done) ->
                when (check) {
                    Readiness.Check.Kiosk -> CheckRow("mdi:lock-outline", stringResource(R.string.kiosk_title), stringResource(R.string.ready_kiosk_desc), done) {
                        StepAction("mdi:toggle-switch-outline", stringResource(R.string.ready_enable)) { app.config.update { it.copy(kioskEnabled = true) } }
                    }
                    Readiness.Check.Lock -> CheckRow(
                        "mdi:shield-lock-outline", stringResource(R.string.ready_lock_title), stringResource(R.string.ready_lock_desc), done,
                        doneDesc = stringResource(if (isOwner) R.string.ready_lock_owner else R.string.ready_lock_home),
                        // Complet déjà obtenu : rien à proposer ; écran d'accueil seul : le complet reste proposé.
                        extra = if (isOwner) null else ({
                            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                LockOption(stringResource(R.string.ready_home_title), stringResource(R.string.ready_home_desc), isHome) {
                                    StepAction("mdi:home-switch-outline", stringResource(R.string.kiosk_home_action)) { activity?.let { Readiness.open(it, Intent(Settings.ACTION_HOME_SETTINGS)) } }
                                }
                                LockOption(stringResource(R.string.ready_full_title), stringResource(R.string.kiosk_level_full_help), false) {
                                    StepAction("mdi:laptop", stringResource(R.string.ready_full_pc)) { how = if (how == 1) 0 else 1 }
                                    StepAction("mdi:qrcode", stringResource(R.string.ready_full_reset)) { how = if (how == 2) 0 else 2 }
                                }
                                AnimatedVisibility(how == 1) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(stringResource(R.string.kiosk_owner_help), style = TextStyle(fontSize = 13.sp, color = muted, lineHeight = 19.sp))
                                        CommandBox(Kiosk.adbCommand(ctx))
                                    }
                                }
                                AnimatedVisibility(how == 2) { ProvisioningQr() }
                            }
                        }),
                    ) {}
                    Readiness.Check.Relaunch -> CheckRow("mdi:restart", stringResource(R.string.relaunch_title), stringResource(R.string.relaunch_help), done) {
                        StepAction("mdi:layers-outline", stringResource(R.string.relaunch_allow)) { activity?.let { Readiness.open(it, Readiness.overlayIntent(ctx)) } }
                    }
                    Readiness.Check.Battery -> CheckRow("mdi:battery-heart-outline", stringResource(R.string.ready_battery_title), stringResource(R.string.ready_battery_desc), done) {
                        StepAction("mdi:battery-check-outline", stringResource(R.string.relaunch_allow)) { activity?.let { Readiness.open(it, Readiness.batteryIntent(ctx)) } }
                    }
                    Readiness.Check.Animations -> CheckRow("mdi:animation-play-outline", stringResource(R.string.ready_anim_title), animationsHelp(), done) {
                        StepAction("mdi:auto-fix", stringResource(R.string.anim_how_title)) { anim = true }
                    }
                }
            }
            }
        }
    }
}

/** Un des deux niveaux de verrouillage : titre, explication, coche s'il est obtenu, sinon ses actions dessous. */
@Composable
private fun LockOption(title: String, desc: String, done: Boolean, actions: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Color(0xFF131317)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = TextStyle(fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Medium))
                Text(desc, style = TextStyle(fontSize = 13.sp, color = Color(0xFFA3A3AD), lineHeight = 18.sp))
            }
            if (done) HaIcon("mdi:check-circle", Modifier.padding(start = 12.dp), size = 22.dp, tint = StepDone)
        }
        if (!done) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

/**
 * QR code de provisionnement (propriétaire sans ordinateur), à photographier avant de réinitialiser la tablette, avec
 * les étapes. Version de test : la Release est signée avec une autre clé, la tablette refuserait le code.
 */
@Composable
internal fun ProvisioningQr() {
    val ctx = LocalContext.current
    var payload by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        payload = runCatching { fr.pergens.hamacs.kiosk.Provisioning.payload(ctx) }.getOrNull()
        failed = payload == null
    }
    val muted = Color(0xFFA3A3AD)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(220.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(10.dp), contentAlignment = Alignment.Center) {
            val p = payload
            when {
                p != null -> {
                    val bmp = remember(p) { fr.pergens.hamacs.net.LocalHttpServer.qr(p, 600).asImageBitmap() }
                    Image(bmp, null, Modifier.fillMaxSize())
                }
                failed -> Text(stringResource(R.string.ready_qr_failed), style = TextStyle(fontSize = 13.sp, color = Color(0xFF333333), lineHeight = 18.sp))
                else -> CircularProgressIndicator(color = Color(0xFF333333))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (BuildConfig.DEBUG) Text(stringResource(R.string.ready_qr_debug), style = TextStyle(fontSize = 13.sp, color = Color(0xFFFFB74D), lineHeight = 18.sp))
            Text(stringResource(R.string.ready_qr_steps), style = TextStyle(fontSize = 13.sp, color = muted, lineHeight = 20.sp))
        }
    }
}

/**
 * Un point : pastille (verte si prêt, orange sinon), titre et, s'il manque, explication et action à droite ;
 * prêt : [doneDesc] s'il est donné. [extra] : détail sous la ligne (gardé une fois prêt avec [doneDesc]).
 */
@Composable
private fun CheckRow(
    icon: String, title: String, desc: String, done: Boolean,
    doneDesc: String? = null, extra: (@Composable () -> Unit)? = null, action: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val tint = if (done) StepDone else Color(0xFFFFB74D)
    Column(Modifier.fillMaxWidth().clip(shape).background(Color(0xFF1A1A1F)).border(1.dp, Color(0xFF2C2C33), shape).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                HaIcon(icon, size = 20.dp, tint = tint)
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = TextStyle(fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Medium))
                (if (done) doneDesc else desc)?.let { Text(it, style = TextStyle(fontSize = 13.sp, color = Color(0xFFA3A3AD), lineHeight = 18.sp)) }
            }
            if (done) HaIcon("mdi:check-circle", size = 22.dp, tint = StepDone) else action()
        }
        if (!done || doneDesc != null) extra?.invoke()
    }
}
