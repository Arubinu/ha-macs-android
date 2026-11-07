package fr.pergens.hamacs.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import fr.pergens.hamacs.App
import fr.pergens.hamacs.BuildConfig
import fr.pergens.hamacs.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Mises à jour depuis les Releases GitHub (app installée hors Play Store) : vérification toutes les 12 h et sur
 * demande, APK de l'architecture de la tablette, installation sans confirmation si l'app est propriétaire de
 * l'appareil (sinon Android demande de confirmer). Une Release doit être signée avec la même clé que l'app installée.
 */
class Updater(private val context: Context, private val http: OkHttpClient, private val scope: CoroutineScope) {
    data class Release(val version: String, val notes: String, val apkUrl: String, val apkName: String, val size: Long, val prerelease: Boolean)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val progress: Float) : State
        data class Installing(val release: Release) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Installation automatique dès qu'une version est disponible (réglage, activé par défaut). */
    var autoInstall: Boolean
        get() = prefs.getBoolean("auto_update", true)
        set(v) { prefs.edit().putBoolean("auto_update", v).apply() }

    /** Préversions proposées aussi (versions de test publiées avant les versions stables). */
    var prereleases: Boolean
        get() = prefs.getBoolean("update_prereleases", false)
        set(v) { prefs.edit().putBoolean("update_prereleases", v).apply() }

    /** Adresse de l'API (versions de débogage : `update_url` dans les prefs pour tester avec un faux serveur). */
    private val apiUrl: String
        get() = prefs.getString("update_url", null)?.takeIf { BuildConfig.DEBUG && it.isNotBlank() } ?: RELEASES

    fun start() {
        scope.launch {
            delay(60_000)
            while (true) {
                check(auto = true)
                delay(12 * 3600_000L)
            }
        }
    }

    /** Cherche une nouvelle version ; [auto] : l'installe si le réglage le permet. */
    fun check(auto: Boolean = false) {
        if (_state.value is State.Downloading || _state.value is State.Installing) return
        scope.launch {
            _state.value = State.Checking
            val release = runCatching { latest() }.getOrElse {
                Log.w(TAG, "vérification", it)
                _state.value = State.Failed(it.message ?: it.javaClass.simpleName)
                return@launch
            }
            _state.value = if (release != null && newer(release.version, BuildConfig.VERSION_NAME)) State.Available(release) else State.UpToDate
            if (auto && autoInstall && release != null && _state.value is State.Available) install(release)
        }
    }

    /** Télécharge puis installe [release]. */
    fun install(release: Release) {
        if (_state.value is State.Downloading || _state.value is State.Installing) return
        scope.launch {
            val file = runCatching { download(release) }.getOrElse {
                _state.value = State.Failed(it.message ?: it.javaClass.simpleName)
                return@launch
            }
            _state.value = State.Installing(release)
            // Notes gardées pour le « Quoi de neuf » de la nouvelle version.
            WhatsNew.remember(context, release.version, release.notes)
            runCatching { withContext(Dispatchers.Main) { commit(file) } }.onFailure {
                _state.value = State.Failed(it.message ?: it.javaClass.simpleName)
            }
        }
    }

    internal fun onResult(status: Int, message: String?) {
        if (status != PackageInstaller.STATUS_SUCCESS && status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
            _state.value = State.Failed(message ?: "status $status")
        }
    }

    /** Notes de la Release de [version] (null : pas de Release pour cette version). */
    suspend fun notesFor(version: String): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(apiUrl).header("Accept", "application/vnd.github+json").header("User-Agent", "HA-MACS-Android").build()
        http.newCall(req).execute().use { r ->
            if (r.code == 404) return@withContext null
            if (!r.isSuccessful) error("HTTP ${r.code}")
            (json.parseToJsonElement(r.body!!.string()) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .firstOrNull { it["tag_name"]?.jsonPrimitive?.contentOrNull?.removePrefix("v") == version }
                ?.get("body")?.jsonPrimitive?.contentOrNull
        }
    }

    /** Adresse de l'APK (architecture de la tablette) de la Release de [version], sinon de la dernière version stable. */
    suspend fun apkFor(version: String): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(apiUrl).header("Accept", "application/vnd.github+json").header("User-Agent", "HA-MACS-Android").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@withContext null
            val list = (json.parseToJsonElement(r.body!!.string()) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .filter { it["draft"]?.jsonPrimitive?.contentOrNull != "true" }.mapNotNull { release(it) }
            (list.firstOrNull { it.version == version }
                ?: list.filter { !it.prerelease }.reduceOrNull { a, b -> if (newer(b.version, a.version)) b else a })?.apkUrl
        }
    }

    /** Version la plus récente du canal choisi (stables seules, ou stables et préversions). */
    private suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(apiUrl).header("Accept", "application/vnd.github+json").header("User-Agent", "HA-MACS-Android").build()
        http.newCall(req).execute().use { r ->
            if (r.code == 404) return@withContext null
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val list = (json.parseToJsonElement(r.body!!.string()) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            list.filter { it["draft"]?.jsonPrimitive?.contentOrNull != "true" }
                .mapNotNull { release(it) }
                .filter { prereleases || !it.prerelease }
                .reduceOrNull { a, b -> if (newer(b.version, a.version)) b else a }
        }
    }

    private fun release(o: JsonObject): Release? {
        val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
        val assets = (o["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        fun asset(suffix: String) = assets.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(suffix) == true }
        // APK de l'architecture de la tablette (le plus léger), sinon l'universel.
        val apk = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { asset("-$it.apk") } ?: asset("-universal.apk") ?: return null
        return Release(
            version = tag.removePrefix("v"),
            notes = o["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            apkUrl = apk["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null,
            apkName = apk["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            size = apk["size"]?.jsonPrimitive?.longOrNull ?: 0L,
            prerelease = o["prerelease"]?.jsonPrimitive?.contentOrNull == "true",
        )
    }

    private suspend fun download(release: Release): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "update.apk")
        _state.value = State.Downloading(release, 0f)
        http.newCall(Request.Builder().url(release.apkUrl).header("User-Agent", "HA-MACS-Android").build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val body = r.body!!
            val total = body.contentLength().takeIf { it > 0 } ?: release.size
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = 0f
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val p = if (total > 0) done.toFloat() / total else 0f
                        if (p - last >= 0.02f) { last = p; _state.value = State.Downloading(release, p) }
                    }
                }
            }
        }
        file
    }

    private fun commit(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("app.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; session.fsync(out) }
            val intent = Intent(context, InstallReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    companion object {
        private const val TAG = "Updater"
        const val RELEASES = "https://api.github.com/repos/Arubinu/ha-macs-android/releases?per_page=20"

        /**
         * 1.2.10 > 1.2.9 (nombre par nombre) ; à numéros égaux, la version stable passe avant ses préversions
         * (1.3.0 > 1.3.0-beta.2 > 1.3.0-beta.1).
         */
        fun newer(candidate: String, current: String): Boolean {
            fun core(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = core(candidate); val b = core(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            val sa = candidate.substringAfter('-', ""); val sb = current.substringAfter('-', "")
            if (sa.isEmpty() || sb.isEmpty()) return sa.isEmpty() && sb.isNotEmpty()
            val pa = sa.split('.', '-'); val pb = sb.split('.', '-')
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrElse(i) { "" }; val y = pb.getOrElse(i) { "" }
                if (x == y) continue
                val nx = x.toIntOrNull(); val ny = y.toIntOrNull()
                return if (nx != null && ny != null) nx > ny else x > y
            }
            return false
        }
    }
}

/** Résultat de l'installation : confirmation à afficher (app non propriétaire) ou erreur. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            (intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))?.let {
                runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        App.instance.updater.onResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}

/** Après une mise à jour, l'app se rouvre (mêmes conditions que la relance après plantage). */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
