package fr.pergens.hamacs

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Tablette au mur : un plantage ne doit pas laisser l'écran sur le bureau d'Android. L'erreur est notée dans un
 * journal (consultable dans Réglages › À propos), puis l'app est relancée — sauf si elle plante en boucle (3 fois en
 * 2 minutes), pour ne pas rendre la tablette inutilisable. La relance est garantie quand l'app est propriétaire de
 * l'appareil ou écran d'accueil (Android relance aussi l'écran d'accueil de lui-même).
 */
object CrashGuard {
    private const val MAX_ENTRIES = 20
    private const val SEPARATOR = "\n────\n"

    private fun file(ctx: Context) = File(ctx.filesDir, "crashes.log")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(app, thread, error) }
            val restart = runCatching { shouldRestart(app) }.getOrDefault(false)
            if (restart && runCatching { scheduleRestart(app) }.isSuccess) {
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Entrées du journal, la plus récente d'abord. */
    fun entries(ctx: Context): List<String> =
        runCatching { file(ctx).readText().split(SEPARATOR).filter { it.isNotBlank() } }.getOrDefault(emptyList())

    fun clear(ctx: Context) { file(ctx).delete() }

    private fun record(ctx: Context, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
            .lines().take(40).joinToString("\n")
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        val entry = "$date · v${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · ${thread.name}\n$trace"
        val kept = (listOf(entry) + entries(ctx)).take(MAX_ENTRIES)
        file(ctx).writeText(kept.joinToString(SEPARATOR))
    }

    /** Pas de relance après 3 plantages en 2 minutes (boucle). */
    private fun shouldRestart(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences("crash", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val recent = prefs.getString("times", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
            .filter { now - it < 120_000 } + now
        prefs.edit().putString("times", recent.joinToString(",")).commit()
        return recent.size < 3
    }

    private fun scheduleRestart(ctx: Context) {
        // L'alarme relance d'abord le processus (recepteur), qui ouvre ensuite l'ecran : Android n'autorise
        // l'ouverture depuis l'arriere-plan qu'a une app en vie (proprietaire, ecran d'accueil ou superposition).
        val pending = PendingIntent.getBroadcast(
            ctx, 42, Intent(ctx, RestartReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
        )
        val alarms = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1500, pending)
    }

    /** L'app peut-elle rouvrir son ecran seule (relance apres plantage, demarrage) ? */
    fun canRelaunch(ctx: Context): Boolean {
        if (fr.pergens.hamacs.kiosk.Kiosk.isDeviceOwner(ctx)) return true
        if (android.provider.Settings.canDrawOverlays(ctx)) return true
        val home = ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        return home?.activityInfo?.packageName == ctx.packageName
    }
}

/** Relance de l'ecran apres un plantage (voir [CrashGuard]). */
class RestartReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }
    }
}
