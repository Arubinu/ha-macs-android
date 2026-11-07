package fr.pergens.hamacs

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import fr.pergens.hamacs.config.ConfigRepository
import fr.pergens.hamacs.ha.HaClient
import fr.pergens.hamacs.macs.MacsController
import fr.pergens.hamacs.presence.PresenceDetector
import fr.pergens.hamacs.ui.components.IconRegistry
import fr.pergens.hamacs.voice.VoiceAssistant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class App : Application(), ImageLoaderFactory {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var http: OkHttpClient
        private set
    lateinit var ha: HaClient
        private set
    lateinit var config: ConfigRepository
        private set
    lateinit var voice: VoiceAssistant
        private set
    lateinit var macs: MacsController
        private set
    lateinit var presence: PresenceDetector
        private set
    lateinit var alerts: fr.pergens.hamacs.ui.cards.alerts.AlertHub
        private set
    lateinit var updater: fr.pergens.hamacs.update.Updater
        private set
    lateinit var link: fr.pergens.hamacs.ha.MobileAppLink
        private set
    lateinit var dlna: fr.pergens.hamacs.dlna.DlnaRenderer
        private set

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(fr.pergens.hamacs.config.AppLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Plantage : noté dans le journal, puis relance de l'app (tablette au mur).
        CrashGuard.install(this)
        http = OkHttpClient.Builder()
            // Proxy inverse (Pangolin, Cloudflare Access…) : ses en-têtes sur toutes les requêtes vers Home Assistant,
            // WebSocket comprise ; jamais vers un autre serveur.
            .addInterceptor { chain ->
                val req = chain.request()
                val extra = if (::config.isInitialized) config.proxyHeaders else emptyList()
                if (extra.isEmpty() || "${req.url.host}:${req.url.port}" != haServer()) chain.proceed(req)
                else chain.proceed(req.newBuilder().apply { extra.forEach { (k, v) -> header(k, v) } }.build())
            }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
        IconRegistry.init(this)
        config = ConfigRepository(this, http)
        ha = HaClient(http, scope)
        voice = VoiceAssistant(this, http, ha, config, scope)
        macs = MacsController(this, ha, config, voice, scope).also { it.start() }
        presence = PresenceDetector(this)
        alerts = fr.pergens.hamacs.ui.cards.alerts.AlertHub(this, ha, config).also { it.start() }
        // Mises à jour depuis les Releases GitHub (vérification toutes les 12 h).
        updater = fr.pergens.hamacs.update.Updater(this, http, scope).also { it.start() }
        // La tablette vue par Home Assistant (intégration mobile_app) : capteurs et notifications.
        link = fr.pergens.hamacs.ha.MobileAppLink(this, http, ha, config, scope).also {
            it.lastMotion = { presence.lastMotion.value }
            it.start()
        }
        // La tablette en lecteur DLNA : Home Assistant la découvre et y envoie du son (TTS, radios, Music Assistant…).
        voice.wyoming.start()
        dlna = fr.pergens.hamacs.dlna.DlnaRenderer(this, http, scope) { link.deviceName }.also { it.start(); it.watchVolume() }

        scope.launch {
            config.settings.map { it.haUrl to it.haToken }.distinctUntilChanged().collect { (url, token) ->
                if (url.isNotBlank() && token.isNotBlank()) ha.connect(url, token)
            }
        }
    }

    /** « hôte:port » du serveur Home Assistant (null si l'adresse est invalide). */
    fun haServer(): String? = server(config.settings.value.haUrl)

    /** « hôte:port » d'une adresse, port par défaut du protocole s'il est absent (ws/wss comme http/https). */
    private fun server(url: String): String? = runCatching {
        val u = java.net.URI(url.trim())
        val host = u.host ?: return null
        val port = if (u.port > 0) u.port else if (u.scheme.equals("https", true) || u.scheme.equals("wss", true)) 443 else 80
        "$host:$port"
    }.getOrNull()

    /** En-têtes du proxy pour [url] s'il pointe vers Home Assistant (lecteurs qui n'utilisent pas [http]). */
    fun proxyHeadersFor(url: String): Map<String, String> =
        if (server(url)?.let { it == haServer() } == true) config.proxyHeaders.toMap() else emptyMap()

    /** Sources des lecteurs vidéo et audio : client [http] (en-têtes du proxy inverse vers Home Assistant). */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun mediaSources(ctx: android.content.Context) = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
        androidx.media3.datasource.DefaultDataSource.Factory(ctx, androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(http)),
    )

    /** Coil : ajoute le jeton HA aux images servies par Home Assistant (/local, /api/camera_proxy…). */
    override fun newImageLoader(): ImageLoader {
        val authed = http.newBuilder().addInterceptor { chain ->
            val req = chain.request()
            val s = config.settings.value
            val haHost = runCatching { java.net.URI(s.haUrl).host }.getOrNull()
            if (haHost != null && req.url.host == haHost && req.header("Authorization") == null) {
                chain.proceed(req.newBuilder().header("Authorization", "Bearer ${s.haToken}").build())
            } else chain.proceed(req)
        }.build()
        return ImageLoader.Builder(this)
            .okHttpClient(authed)
            .components {
                if (android.os.Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
                add(SvgDecoder.Factory())
            }
            .crossfade(250)
            .build()
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
