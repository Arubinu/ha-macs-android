package fr.pergens.hamacs.kiosk

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import fr.pergens.hamacs.App
import fr.pergens.hamacs.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Récepteur d'administration : permet de devenir « propriétaire de l'appareil » (kiosque complet). */
class KioskAdmin : DeviceAdminReceiver()

/** Relance le tableau de bord au démarrage de la tablette quand le mode kiosque est actif. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!App.instance.config.settings.value.kioskEnabled) return
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/**
 * Mode kiosque à la manière de Fully Kiosk : l'application reste au premier plan et ne peut être quittée,
 * ni la tablette éteinte depuis le menu d'alimentation, sans le code d'accès.
 *
 * - Propriétaire de l'appareil (activé une fois par `adb shell dpm set-device-owner …`) : verrouillage
 *   complet (« lock task ») — boutons Accueil et Applications récentes inactifs, barre d'état et
 *   notifications bloquées, menu d'alimentation désactivé ; l'app devient l'écran d'accueil imposé.
 * - Sinon : épinglage d'écran Android (sortie possible par le geste système, que l'on peut faire protéger
 *   par le code de verrouillage de la tablette dans les paramètres Android).
 *
 * Le code ouvre un menu : quitter le kiosque (il se réactive au retour dans l'app), paramètres Android,
 * redémarrer, ou autoriser le menu d'alimentation une minute pour éteindre.
 */
object Kiosk {
    private const val SUSPEND_MS = 5 * 60_000L
    private const val POWER_MENU_MS = 60_000L

    private val main = Handler(Looper.getMainLooper())

    private val _suspended = MutableStateFlow(false)
    /** Kiosque suspendu par le code (jusqu'au retour dans l'app, ou 5 minutes dans l'app). */
    val suspended: StateFlow<Boolean> = _suspended.asStateFlow()

    /** L'app a été quittée pendant la suspension : le kiosque reprend à son retour. */
    private var leftWhileSuspended = false

    fun admin(ctx: Context) = ComponentName(ctx, KioskAdmin::class.java)
    private fun dpm(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java)

    fun isDeviceOwner(ctx: Context): Boolean = runCatching { dpm(ctx).isDeviceOwnerApp(ctx.packageName) }.getOrDefault(false)

    fun adbCommand(ctx: Context) = "adb shell dpm set-device-owner ${ctx.packageName}/.kiosk.KioskAdmin"

    fun isLocked(ctx: Context): Boolean =
        ctx.getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

    /** Applique l'état voulu : à appeler au démarrage, au retour au premier plan et après un changement de réglage. */
    fun apply(activity: Activity) {
        val enabled = App.instance.config.settings.value.kioskEnabled
        if (!enabled) {
            release(activity)
            if (isDeviceOwner(activity)) runCatching { dpm(activity).setLockTaskPackages(admin(activity), emptyArray()) }
            return
        }
        if (_suspended.value) return
        val owner = isDeviceOwner(activity)
        if (owner) {
            val dpm = dpm(activity)
            val admin = admin(activity)
            runCatching { dpm.setLockTaskPackages(admin, arrayOf(activity.packageName)) }
            setPowerMenu(activity, false)
            runCatching { dpm.setStatusBarDisabled(admin, true) }
            setHome(activity, true)
        }
        if (!isLocked(activity)) runCatching { activity.startLockTask() }
    }

    /**
     * Sort du verrouillage. [keepHome] : l'app reste l'écran d'accueil imposé (suspension), sinon Android
     * relancerait aussitôt l'écran d'accueil — donc l'app — et le kiosque reprendrait immédiatement.
     */
    private fun release(activity: Activity, keepHome: Boolean = false) {
        if (isLocked(activity)) runCatching { activity.stopLockTask() }
        if (isDeviceOwner(activity)) {
            runCatching { dpm(activity).setStatusBarDisabled(admin(activity), false) }
            if (!keepHome) setHome(activity, false)
        }
    }

    /**
     * Code validé → sortie du kiosque, jusqu'au retour dans l'app (bouton Accueil compris, puisqu'elle reste
     * l'écran d'accueil) ou 5 minutes passées dans l'app. [then] : écran à ouvrir (paramètres…), sinon le
     * lanceur d'applications du système.
     */
    fun suspend(activity: Activity, then: Intent? = null) {
        _suspended.value = true
        leftWhileSuspended = false
        release(activity, keepHome = true)
        val target = then ?: otherLauncher(activity)
        if (target != null) runCatching { activity.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        main.removeCallbacksAndMessages(this)
        main.postAtTime({
            // Toujours dans l'app au bout de 5 minutes : on reverrouille.
            if (_suspended.value && !leftWhileSuspended) {
                _suspended.value = false
                apply(activity)
            }
        }, this, android.os.SystemClock.uptimeMillis() + SUSPEND_MS)
    }

    fun onStop() {
        if (_suspended.value) leftWhileSuspended = true
    }

    fun onResume(activity: Activity) {
        if (_suspended.value && leftWhileSuspended) {
            _suspended.value = false
            leftWhileSuspended = false
            main.removeCallbacksAndMessages(this)
        }
        apply(activity)
    }

    /** Lanceur d'applications du système (autre que cette app), pour « Quitter le mode kiosque ». */
    private fun otherLauncher(ctx: Context): Intent? {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val info = runCatching { ctx.packageManager.queryIntentActivities(home, 0) }.getOrNull().orEmpty()
            .firstOrNull { it.activityInfo.packageName != ctx.packageName } ?: return null
        return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .setClassName(info.activityInfo.packageName, info.activityInfo.name)
    }

    /** Écran d'accueil imposé (propriétaire de l'appareil) : le bouton Accueil ramène toujours ici. */
    private fun setHome(ctx: Context, on: Boolean) {
        val dpm = dpm(ctx)
        val admin = admin(ctx)
        runCatching {
            if (on) {
                val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
                dpm.addPersistentPreferredActivity(admin, filter, ComponentName(ctx, MainActivity::class.java))
            } else dpm.clearPackagePersistentPreferredActivities(admin, ctx.packageName)
        }
    }

    /** Menu d'alimentation (appui long sur le bouton marche) pendant le verrouillage. */
    private fun setPowerMenu(ctx: Context, on: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        runCatching {
            dpm(ctx).setLockTaskFeatures(
                admin(ctx),
                if (on) DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS else DevicePolicyManager.LOCK_TASK_FEATURE_NONE,
            )
        }
    }

    /** Autorise le menu d'alimentation une minute (pour éteindre la tablette), sans quitter le kiosque. */
    fun allowPowerMenu(ctx: Context): Boolean {
        if (!isDeviceOwner(ctx) || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        setPowerMenu(ctx, true)
        main.postDelayed({ if (App.instance.config.settings.value.kioskEnabled && !_suspended.value) setPowerMenu(ctx, false) }, POWER_MENU_MS)
        return true
    }

    fun canReboot(ctx: Context) = isDeviceOwner(ctx)

    fun reboot(ctx: Context) {
        runCatching { dpm(ctx).reboot(admin(ctx)) }
    }

    /** Retire les droits de propriétaire (indispensable avant de désinstaller l'app). */
    @Suppress("DEPRECATION")
    fun removeDeviceOwner(activity: Activity) {
        release(activity)
        runCatching { dpm(activity).setLockTaskPackages(admin(activity), emptyArray()) }
        runCatching { dpm(activity).clearDeviceOwnerApp(activity.packageName) }
    }
}
