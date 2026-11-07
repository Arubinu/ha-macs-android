package fr.pergens.hamacs.dlna

import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import fr.pergens.hamacs.BuildConfig
import fr.pergens.hamacs.CrashGuard
import fr.pergens.hamacs.net.LocalHttpServer
import fr.pergens.hamacs.net.LocalHttpServer.Request
import fr.pergens.hamacs.net.LocalHttpServer.Response
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * La tablette en lecteur réseau (UPnP / DLNA MediaRenderer) : Home Assistant la découvre seul (intégration « DLNA Digital
 * Media Renderer ») et en fait un `media_player` — synthèse vocale, radios, médias locaux, Music Assistant… sans
 * Browser Mod ni Fully Kiosk. Annonce SSDP sur le réseau, description et services AVTransport, RenderingControl et
 * ConnectionManager servis par [LocalHttpServer], événements `LastChange` aux abonnés, lecture par ExoPlayer avec le
 * volume « médias » d'Android.
 */
class DlnaRenderer(
    private val ctx: Context,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    /** Nom affiché dans Home Assistant (celui de la tablette). */
    private val name: () -> String,
) {
    sealed interface Status {
        data object Off : Status
        data object NoNetwork : Status
        data class Running(val name: String) : Status
    }

    /** Média en cours, pour l'affichage (null : rien). */
    data class NowPlaying(val title: String?, val artist: String?, val playing: Boolean)

    private val prefs = ctx.getSharedPreferences("dlna", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean("enabled", false))
    val enabled: StateFlow<Boolean> = _enabled
    private val _status = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = _status
    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying

    /** Identifiant UPnP stable (Home Assistant reconnaît la même tablette après un redémarrage). */
    private val uuid: String = prefs.getString("uuid", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("uuid", it).apply() }
    private val audio = ctx.getSystemService(AudioManager::class.java)

    private var server: LocalHttpServer? = null
    private var job: Job? = null
    private var location: String? = null

    // État de lecture (lu par les requêtes, écrit sur le fil principal).
    @Volatile private var transportState = "NO_MEDIA_PRESENT"
    @Volatile private var currentUri = ""
    @Volatile private var currentMeta = ""
    @Volatile private var positionMs = 0L
    @Volatile private var durationMs = 0L
    /** Arrêt demandé (STOPPED plutôt que PAUSED_PLAYBACK). */
    @Volatile private var stopped = true
    private var player: ExoPlayer? = null

    private class Sub(val callback: String, val service: String, @Volatile var expires: Long, var seq: Long = 0)
    private val subs = ConcurrentHashMap<String, Sub>()
    /** Événements envoyés un par un, dans l'ordre des changements (numéros SEQ croissants). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val eventQueue = Dispatchers.IO.limitedParallelism(1)

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean("enabled", on).apply()
        _enabled.value = on
        restart()
    }

    fun start() = restart()

    private fun restart() {
        job?.cancel()
        server?.stop()
        server = null
        if (!_enabled.value) {
            _status.value = Status.Off
            scope.launch(Dispatchers.Main) { releasePlayer() }
            return
        }
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val ip = LocalHttpServer.localIp()
                if (ip == null) { _status.value = Status.NoNetwork; delay(15_000); continue }
                // Port et chemin fixes : Home Assistant retrouve la tablette à la même adresse après un redémarrage.
                val srv = LocalHttpServer(maxBody = 256 * 1024, concurrent = true, port = 49494, fixedKey = "dlna-" + uuid.take(8)) { handle(it) }
                val base = srv.start(this) ?: run { delay(15_000); null } ?: continue
                server = srv
                location = "$base/description.xml"
                _status.value = Status.Running(name())
                try {
                    runSsdp(base, ip)
                } finally {
                    srv.stop()
                    server = null
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // SSDP : réponses aux recherches et annonces périodiques sur 239.255.255.250:1900.
    // ------------------------------------------------------------------------------------------------------------

    private val deviceType = "urn:schemas-upnp-org:device:MediaRenderer:1"
    private val services = listOf("AVTransport", "RenderingControl", "ConnectionManager")
    private fun serviceType(s: String) = "urn:schemas-upnp-org:service:$s:1"

    /** Couples (NT/ST, USN) annoncés. */
    private fun targets(): List<Pair<String, String>> =
        listOf("upnp:rootdevice" to "uuid:$uuid::upnp:rootdevice", "uuid:$uuid" to "uuid:$uuid", deviceType to "uuid:$uuid::$deviceType") +
            services.map { serviceType(it) to "uuid:$uuid::${serviceType(it)}" }

    private val serverHeader get() = "Android/${Build.VERSION.RELEASE} UPnP/1.0 HA-MACS/${BuildConfig.VERSION_NAME}"

    private suspend fun runSsdp(base: String, ip: String) {
        val group = InetAddress.getByName("239.255.255.250")
        val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("hamacs-dlna")?.apply { setReferenceCounted(false); acquire() }
        val sock = MulticastSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(1900)) }
        try {
            @Suppress("DEPRECATION") sock.joinGroup(group)
            sock.soTimeout = 1000
            fun send(text: String, to: InetAddress, port: Int) =
                runCatching { val b = text.toByteArray(); sock.send(DatagramPacket(b, b.size, to, port)) }
            fun notify(nts: String) = targets().forEach { (nt, usn) ->
                send(
                    "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: $location\r\n" +
                        "NT: $nt\r\nNTS: $nts\r\nSERVER: $serverHeader\r\nUSN: $usn\r\n\r\n", group, 1900,
                )
            }
            var nextNotify = 0L
            var announced = 0
            val buf = ByteArray(2048)
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val now = System.currentTimeMillis()
                if (now >= nextNotify) {
                    notify("ssdp:alive")
                    // Trois annonces rapprochées au démarrage, puis toutes les 5 minutes.
                    nextNotify = now + if (++announced < 3) 3_000L else 300_000L
                    // Adresse IP changée (autre réseau) : on recommence avec la nouvelle.
                    if (LocalHttpServer.localIp() != ip) return
                }
                expireSubs()
                val p = DatagramPacket(buf, buf.size)
                val got = runCatching { sock.receive(p) }.isSuccess
                if (!got) continue
                val text = String(p.data, 0, p.length)
                if (!text.startsWith("M-SEARCH", ignoreCase = true) || !text.contains("ssdp:discover", ignoreCase = true)) continue
                val st = Regex("(?im)^ST:\\s*(.+?)\\s*$").find(text)?.groupValues?.get(1) ?: continue
                val matches = targets().filter { st == "ssdp:all" || it.first.equals(st, ignoreCase = true) }
                val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date())
                matches.forEach { (nt, usn) ->
                    send(
                        "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nDATE: $date\r\nEXT:\r\nLOCATION: $location\r\n" +
                            "SERVER: $serverHeader\r\nST: $nt\r\nUSN: $usn\r\n\r\n", p.address, p.port,
                    )
                }
            }
        } finally {
            runCatching {
                targets().forEach { (nt, usn) ->
                    val b = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: $nt\r\nNTS: ssdp:byebye\r\nUSN: $usn\r\n\r\n".toByteArray()
                    sock.send(DatagramPacket(b, b.size, group, 1900))
                }
            }
            runCatching { sock.close() }
            runCatching { lock?.release() }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // HTTP : description, services (SCPD), commandes SOAP et abonnements aux événements.
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Appareils autorisés à commander le lecteur : tout le réseau ([PeerGuard.Mode.Open]) ou Home Assistant et les
     * appareils approuvés dans les réglages. Les descriptions (GET) restent lisibles, pour que l'appareil se présente.
     */
    val guard = fr.pergens.hamacs.net.PeerGuard(ctx, "peers_dlna", scope, { fr.pergens.hamacs.App.instance.config.settings.value.haUrl })

    private suspend fun handle(r: Request): Response = when {
        r.method != "GET" && !guard.check(r.remote, r.headers["user-agent"], loopback = r.remote == "127.0.0.1" || r.remote == "::1") ->
            Response("403 Forbidden", "text/plain", ByteArray(0))
        r.method == "GET" && r.path == "/description.xml" -> xml(description())
        r.method == "GET" && r.path.endsWith(".xml") -> Scpd.of(r.path.removePrefix("/").removeSuffix(".xml"))?.let { xml(it) } ?: Response.notFound
        r.method == "POST" && r.path.startsWith("/ctl/") -> soap(r.path.removePrefix("/ctl/"), r)
        r.method == "SUBSCRIBE" && r.path.startsWith("/evt/") -> subscribe(r.path.removePrefix("/evt/"), r)
        r.method == "UNSUBSCRIBE" && r.path.startsWith("/evt/") -> {
            r.headers["sid"]?.let { subs.remove(it) }
            Response("200 OK", "text/plain", ByteArray(0))
        }
        else -> Response.notFound
    }

    private fun xml(s: String) = Response("200 OK", "text/xml; charset=\"utf-8\"", s.toByteArray())

    private fun description(): String {
        val key = location?.substringBeforeLast("/description.xml")?.substringAfter("://")?.substringAfter('/') ?: ""
        val list = services.joinToString("") { s ->
            "<service><serviceType>${serviceType(s)}</serviceType><serviceId>urn:upnp-org:serviceId:$s</serviceId>" +
                "<SCPDURL>/$key/$s.xml</SCPDURL><controlURL>/$key/ctl/$s</controlURL><eventSubURL>/$key/evt/$s</eventSubURL></service>"
        }
        return """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
<specVersion><major>1</major><minor>0</minor></specVersion>
<device>
<deviceType>$deviceType</deviceType>
<friendlyName>${esc(name())}</friendlyName>
<manufacturer>HA-MACS</manufacturer>
<manufacturerURL>https://github.com/Arubinu/ha-macs-android</manufacturerURL>
<modelDescription>HA-MACS tablet</modelDescription>
<modelName>HA-MACS</modelName>
<modelNumber>${BuildConfig.VERSION_NAME}</modelNumber>
<serialNumber>${uuid.take(8)}</serialNumber>
<UDN>uuid:$uuid</UDN>
<dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>
<serviceList>$list</serviceList>
</device>
</root>"""
    }

    private suspend fun soap(service: String, r: Request): Response {
        // Nom d'action vérifié avant d'entrer dans l'expression régulière (sinon injectable, ex. `(a+)+$`, et le calcul
        // pourrait bloquer le fil sur un corps de 256 Ko).
        val action = r.headers["soapaction"]?.trim('"')?.substringAfter('#')?.takeIf { ACTION_NAME.matches(it) }
            ?: return fault(401, "Invalid Action")
        val body = r.body.toString(Charsets.UTF_8)
        val args = Regex("<(?:\\w+:)?$action\\b[^>]*>(.*)</(?:\\w+:)?$action>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
            ?.let { inner -> Regex("<(\\w+)>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL).findAll(inner).associate { it.groupValues[1] to unesc(it.groupValues[2]) } }
            ?: emptyMap()
        val out: List<Pair<String, String>> = try {
            when (service) {
                "AVTransport" -> avTransport(action, args)
                "RenderingControl" -> renderingControl(action, args)
                "ConnectionManager" -> connectionManager(action)
                else -> null
            } ?: return fault(401, "Invalid Action")
        } catch (e: UpnpError) {
            return fault(e.code, e.message ?: "Error")
        }
        val xmlOut = out.joinToString("") { (k, v) -> "<$k>${esc(v)}</$k>" }
        return xml(
            """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:${action}Response xmlns:u="${serviceType(service)}">$xmlOut</u:${action}Response></s:Body></s:Envelope>""",
        )
    }

    private class UpnpError(val code: Int, msg: String) : Exception(msg)

    private fun fault(code: Int, desc: String) = Response(
        "500 Internal Server Error", "text/xml; charset=\"utf-8\"",
        ("""<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>$code</errorCode><errorDescription>${esc(desc)}</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>""").toByteArray(),
    )

    private suspend fun avTransport(action: String, a: Map<String, String>): List<Pair<String, String>>? = when (action) {
        "SetAVTransportURI" -> {
            val uri = a["CurrentURI"].orEmpty()
            // Flux réseau seulement : pas de fichier ni de contenu local de la tablette (file://, content://…).
            if (uri.isNotEmpty() && !uri.startsWith("http://", true) && !uri.startsWith("https://", true)) throw UpnpError(714, "Illegal MIME-type")
            val meta = a["CurrentURIMetaData"].orEmpty()
            withContext(Dispatchers.Main) { load(uri, meta) }
            emptyList()
        }
        "Play" -> { withContext(Dispatchers.Main) { play() }; emptyList() }
        "Pause" -> { withContext(Dispatchers.Main) { player?.playWhenReady = false }; emptyList() }
        "Stop" -> { withContext(Dispatchers.Main) { stop() }; emptyList() }
        "Seek" -> {
            val unit = a["Unit"].orEmpty()
            if (unit != "REL_TIME" && unit != "ABS_TIME") throw UpnpError(710, "Seek mode not supported")
            val ms = parseTime(a["Target"].orEmpty()) ?: throw UpnpError(711, "Illegal seek target")
            withContext(Dispatchers.Main) { player?.seekTo(ms) }
            emptyList()
        }
        "GetTransportInfo" -> listOf("CurrentTransportState" to transportState, "CurrentTransportStatus" to "OK", "CurrentSpeed" to "1")
        "GetPositionInfo" -> {
            withContext(Dispatchers.Main) { refreshPosition() }
            listOf(
                "Track" to if (currentUri.isEmpty()) "0" else "1", "TrackDuration" to time(durationMs), "TrackMetaData" to currentMeta,
                "TrackURI" to currentUri, "RelTime" to time(positionMs), "AbsTime" to time(positionMs),
                "RelCount" to "2147483647", "AbsCount" to "2147483647",
            )
        }
        "GetMediaInfo" -> listOf(
            "NrTracks" to if (currentUri.isEmpty()) "0" else "1", "MediaDuration" to time(durationMs), "CurrentURI" to currentUri,
            "CurrentURIMetaData" to currentMeta, "NextURI" to "", "NextURIMetaData" to "", "PlayMedium" to "NETWORK",
            "RecordMedium" to "NOT_IMPLEMENTED", "WriteStatus" to "NOT_IMPLEMENTED",
        )
        "GetDeviceCapabilities" -> listOf("PlayMedia" to "NETWORK", "RecMedia" to "NOT_IMPLEMENTED", "RecQualityModes" to "NOT_IMPLEMENTED")
        "GetTransportSettings" -> listOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED")
        "GetCurrentTransportActions" -> listOf("Actions" to actions())
        else -> null
    }

    private fun actions() = when (transportState) {
        "PLAYING", "TRANSITIONING" -> "Pause,Stop,Seek"
        "PAUSED_PLAYBACK" -> "Play,Stop,Seek"
        "STOPPED" -> if (currentUri.isEmpty()) "" else "Play,Seek"
        else -> ""
    }

    private fun maxVolume() = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    private fun volume() = audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maxVolume()
    private fun muted() = Build.VERSION.SDK_INT >= 23 && audio.isStreamMute(AudioManager.STREAM_MUSIC)

    private fun renderingControl(action: String, a: Map<String, String>): List<Pair<String, String>>? = when (action) {
        "GetVolume" -> listOf("CurrentVolume" to volume().toString())
        "SetVolume" -> {
            val v = a["DesiredVolume"]?.toIntOrNull()?.coerceIn(0, 100) ?: throw UpnpError(402, "Invalid Args")
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v * maxVolume() + 50) / 100, 0)
            changed()
            emptyList()
        }
        "GetMute" -> listOf("CurrentMute" to if (muted()) "1" else "0")
        "SetMute" -> {
            val on = a["DesiredMute"] == "1" || a["DesiredMute"].equals("true", ignoreCase = true)
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
            changed()
            emptyList()
        }
        "ListPresets" -> listOf("CurrentPresetNameList" to "FactoryDefaults")
        "SelectPreset" -> emptyList()
        else -> null
    }

    private fun connectionManager(action: String): List<Pair<String, String>>? = when (action) {
        "GetProtocolInfo" -> listOf("Source" to "", "Sink" to SINK)
        "GetCurrentConnectionIDs" -> listOf("ConnectionIDs" to "0")
        "GetCurrentConnectionInfo" -> listOf(
            "RcsID" to "0", "AVTransportID" to "0", "ProtocolInfo" to "", "PeerConnectionManager" to "",
            "PeerConnectionID" to "-1", "Direction" to "Input", "Status" to "OK",
        )
        else -> null
    }

    // ------------------------------------------------------------------------------------------------------------
    // Lecture (fil principal).
    // ------------------------------------------------------------------------------------------------------------

    private fun ensurePlayer(): ExoPlayer = player ?: ExoPlayer.Builder(ctx).setMediaSourceFactory(fr.pergens.hamacs.App.instance.mediaSources(ctx)).build().also { p ->
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        p.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = updateState()
            override fun onPlayerError(error: PlaybackException) {
                CrashGuard.note(ctx, "dlna:${error.errorCodeName}:$currentUri", "Lecteur DLNA",
                    "adresse : ${CrashGuard.redact(currentUri)}\nerreur ${error.errorCodeName} : ${error.message}", error)
                stopped = true
                updateState()
            }
        })
        player = p
    }

    private fun load(uri: String, meta: String) {
        currentUri = uri
        currentMeta = meta
        stopped = true
        if (uri.isEmpty()) { player?.stop(); player?.clearMediaItems() }
        else ensurePlayer().apply { setMediaItem(MediaItem.fromUri(uri)); prepare(); playWhenReady = false }
        updateState()
    }

    private fun play() {
        val p = player ?: return
        if (currentUri.isEmpty()) return
        stopped = false
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        p.playWhenReady = true
        updateState()
    }

    private fun stop() {
        stopped = true
        player?.let { it.playWhenReady = false; it.seekTo(0) }
        updateState()
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        currentUri = ""; currentMeta = ""
        updateState()
    }

    private fun refreshPosition() {
        val p = player ?: run { positionMs = 0; durationMs = 0; return }
        positionMs = p.currentPosition.coerceAtLeast(0)
        durationMs = p.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
    }

    private fun updateState() {
        val p = player
        refreshPosition()
        val state = when {
            p == null || currentUri.isEmpty() -> "NO_MEDIA_PRESENT"
            p.playbackState == Player.STATE_ENDED -> { stopped = true; "STOPPED" }
            p.isPlaying -> "PLAYING"
            p.playWhenReady && p.playbackState == Player.STATE_BUFFERING -> "TRANSITIONING"
            stopped || p.playbackState == Player.STATE_IDLE -> "STOPPED"
            else -> "PAUSED_PLAYBACK"
        }
        _nowPlaying.value = if (state == "PLAYING" || state == "PAUSED_PLAYBACK" || state == "TRANSITIONING") {
            NowPlaying(didl("dc:title"), didl("upnp:artist") ?: didl("dc:creator"), state != "PAUSED_PLAYBACK")
        } else null
        if (state != transportState) { transportState = state; changed() }
    }

    /** Commandes du bandeau « en lecture » de l'app. */
    fun togglePause() = scope.launch(Dispatchers.Main) {
        val p = player ?: return@launch
        if (p.playWhenReady) p.playWhenReady = false else play()
    }

    fun stopPlayback() = scope.launch(Dispatchers.Main) { stop() }

    private fun didl(tag: String): String? =
        Regex("<$tag[^>]*>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(currentMeta)?.groupValues?.get(1)?.let { unesc(it) }?.takeIf { it.isNotBlank() }

    // ------------------------------------------------------------------------------------------------------------
    // Événements (GENA) : LastChange envoyé aux abonnés à chaque changement.
    // ------------------------------------------------------------------------------------------------------------

    private fun subscribe(service: String, r: Request): Response {
        if (service !in services) return Response.notFound
        val timeout = r.headers["timeout"]?.substringAfter("Second-", "")?.toIntOrNull()?.coerceIn(60, 3600) ?: 1800
        val sid = r.headers["sid"]
        if (sid != null) {
            val s = subs[sid] ?: return Response("412 Precondition Failed", "text/plain", ByteArray(0))
            s.expires = System.currentTimeMillis() + timeout * 1000L
            return Response("200 OK", "text/plain", ByteArray(0), mapOf("SID" to sid, "TIMEOUT" to "Second-$timeout", "SERVER" to serverHeader))
        }
        // Rappel en http seulement ; abonnements plafonnés (sinon n'importe qui sur le réseau en accumule sans fin et
        // fait envoyer à la tablette un événement par abonnement à chaque changement).
        val callback = r.headers["callback"]?.let { Regex("<([^>]+)>").find(it)?.groupValues?.get(1) }
            ?.takeIf { it.startsWith("http://") && it.toHttpUrlOrNull() != null }
            ?: return Response("412 Precondition Failed", "text/plain", ByteArray(0))
        expireSubs()
        if (subs.size >= MAX_SUBS) return Response("503 Service Unavailable", "text/plain", ByteArray(0))
        val newSid = "uuid:" + UUID.randomUUID()
        val sub = Sub(callback, service, System.currentTimeMillis() + timeout * 1000L)
        subs[newSid] = sub
        // Événement initial, envoyé juste après la réponse.
        scope.launch(eventQueue) { delay(300); send(newSid, sub) }
        return Response("200 OK", "text/plain", ByteArray(0), mapOf("SID" to newSid, "TIMEOUT" to "Second-$timeout", "SERVER" to serverHeader))
    }

    private fun expireSubs() {
        val now = System.currentTimeMillis()
        subs.entries.removeIf { it.value.expires < now }
    }

    private var lastVolume = -1
    private var lastMute: Boolean? = null

    /** Un état a changé : événement aux abonnés AVTransport et RenderingControl. */
    private fun changed() {
        if (subs.isEmpty()) return
        scope.launch(eventQueue) { subs.forEach { (sid, s) -> if (s.service != "ConnectionManager") send(sid, s) } }
    }

    private fun send(sid: String, s: Sub) {
        val body = when (s.service) {
            "AVTransport" -> lastChange(
                "urn:schemas-upnp-org:metadata-1-0/AVT/",
                "<TransportState val=\"$transportState\"/><TransportStatus val=\"OK\"/><CurrentTransportActions val=\"${actions()}\"/>" +
                    "<AVTransportURI val=\"${esc(currentUri)}\"/><CurrentTrackURI val=\"${esc(currentUri)}\"/>" +
                    "<AVTransportURIMetaData val=\"${esc(currentMeta)}\"/><CurrentTrackMetaData val=\"${esc(currentMeta)}\"/>" +
                    "<CurrentTrackDuration val=\"${time(durationMs)}\"/><CurrentMediaDuration val=\"${time(durationMs)}\"/>" +
                    "<NumberOfTracks val=\"${if (currentUri.isEmpty()) 0 else 1}\"/><TransportPlaySpeed val=\"1\"/>",
            )
            "RenderingControl" -> lastChange(
                "urn:schemas-upnp-org:metadata-1-0/RCS/",
                "<Volume channel=\"Master\" val=\"${volume()}\"/><Mute channel=\"Master\" val=\"${if (muted()) 1 else 0}\"/>",
            )
            else -> "<e:property><SourceProtocolInfo></SourceProtocolInfo></e:property><e:property><SinkProtocolInfo>$SINK</SinkProtocolInfo></e:property>" +
                "<e:property><CurrentConnectionIDs>0</CurrentConnectionIDs></e:property>"
        }
        val xmlBody = "<?xml version=\"1.0\" encoding=\"utf-8\"?><e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">$body</e:propertyset>"
        val req = okhttp3.Request.Builder().url(s.callback)
            .method("NOTIFY", xmlBody.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .header("NT", "upnp:event").header("NTS", "upnp:propchange").header("SID", sid).header("SEQ", (s.seq++).toString())
            .build()
        runCatching { http.newCall(req).execute().close() }.onFailure { Log.d(TAG, "NOTIFY ${s.callback}", it) }
    }

    private fun lastChange(ns: String, vars: String) =
        "<e:property><LastChange>${esc("<Event xmlns=\"$ns\"><InstanceID val=\"0\">$vars</InstanceID></Event>")}</LastChange></e:property>"

    /** Volume changé avec les boutons de la tablette : événement aux abonnés (vérifié toutes les 2 s). */
    fun watchVolume() = scope.launch(Dispatchers.IO) {
        while (isActive) {
            delay(2000)
            if (subs.isEmpty()) continue
            val v = volume(); val m = muted()
            if (v != lastVolume || m != lastMute) { lastVolume = v; lastMute = m; changed() }
        }
    }

    companion object {
        private const val TAG = "DlnaRenderer"
        private val ACTION_NAME = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
        private const val MAX_SUBS = 32

        /** Formats acceptés (ExoPlayer) : audio courant, flux HLS et vidéo MP4 (son seul). */
        private val SINK = listOf(
            "audio/mpeg", "audio/mp3", "audio/mp4", "audio/x-m4a", "audio/aac", "audio/aacp", "audio/flac", "audio/x-flac",
            "audio/wav", "audio/x-wav", "audio/L16", "audio/ogg", "audio/opus", "audio/webm",
            "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/x-mpegurl", "video/mp4",
        ).joinToString(",") { "http-get:*:$it:*" }

        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        fun unesc(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

        /** H:MM:SS. */
        fun time(ms: Long): String {
            val t = ms / 1000
            return "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60)
        }

        /** « H:MM:SS(.fff) » → millisecondes. */
        fun parseTime(s: String): Long? {
            val parts = s.trim().split(':')
            if (parts.size != 3) return null
            val h = parts[0].toLongOrNull() ?: return null
            val m = parts[1].toLongOrNull() ?: return null
            val sec = parts[2].toDoubleOrNull() ?: return null
            return ((h * 3600 + m * 60) * 1000 + sec * 1000).toLong()
        }
    }
}
