package fr.arubinu.hadash

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import fr.arubinu.hadash.config.ConfigRepository
import fr.arubinu.hadash.ha.HaClient
import fr.arubinu.hadash.macs.MacsController
import fr.arubinu.hadash.presence.PresenceDetector
import fr.arubinu.hadash.ui.components.IconRegistry
import fr.arubinu.hadash.voice.VoiceAssistant
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
    lateinit var alerts: fr.arubinu.hadash.ui.cards.alerts.AlertHub
        private set

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(fr.arubinu.hadash.config.AppLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        http = OkHttpClient.Builder()
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
        alerts = fr.arubinu.hadash.ui.cards.alerts.AlertHub(this, ha, config).also { it.start() }

        scope.launch {
            config.settings.map { it.haUrl to it.haToken }.distinctUntilChanged().collect { (url, token) ->
                if (url.isNotBlank() && token.isNotBlank()) ha.connect(url, token)
            }
        }
    }

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
