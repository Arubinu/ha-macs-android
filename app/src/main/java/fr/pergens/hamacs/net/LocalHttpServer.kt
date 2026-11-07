package fr.pergens.hamacs.net

import android.graphics.Bitmap
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * Petit serveur HTTP sur le réseau local, ouvert le temps d'une fenêtre, pour échanger avec un téléphone sans appli
 * (envoi de photo, connexion à Home Assistant). Toutes les adresses commencent par un code aléatoire ([key]) : un
 * autre appareil du réseau ne peut pas les deviner.
 */
class LocalHttpServer(
    private val maxBody: Int = 64 * 1024,
    /** Requêtes traitées en parallèle (plusieurs clients, ex. Home Assistant pour le lecteur DLNA). */
    private val concurrent: Boolean = false,
    /** Port voulu (0 : au hasard) ; s'il est pris, un port au hasard. */
    private val port: Int = 0,
    /** Code fixe au début des adresses (sinon aléatoire). */
    fixedKey: String? = null,
    private val handler: suspend (Request) -> Response,
) {
    class Request(
        val method: String, val path: String, val query: Map<String, String>, val body: ByteArray,
        /** En-têtes, noms en minuscules. */
        val headers: Map<String, String> = emptyMap(),
        /** Adresse IP du client. */
        val remote: String? = null,
    ) {
        /** Corps `application/x-www-form-urlencoded`. */
        fun form(): Map<String, String> = parseQuery(body.toString(Charsets.UTF_8))
    }

    class Response(val code: String, val type: String, val body: ByteArray, val headers: Map<String, String> = emptyMap()) {
        companion object {
            fun html(s: String) = Response("200 OK", "text/html; charset=utf-8", s.toByteArray())
            fun text(code: String, s: String) = Response(code, "text/plain; charset=utf-8", s.toByteArray())
            fun redirect(url: String) = Response("302 Found", "text/plain", ByteArray(0), mapOf("Location" to url))
            val notFound get() = text("404 Not Found", "Not found")
        }
    }

    /** Code aléatoire placé au début de chaque adresse. */
    val key: String = fixedKey ?: ByteArray(9).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val slots = java.util.concurrent.Semaphore(16)
    private val one = kotlinx.coroutines.sync.Mutex()
    private var server: ServerSocket? = null
    private var job: Job? = null

    /** Origine `http://ip:port` (sans / final), connue après [start]. */
    var origin: String? = null
        private set

    /** Démarre le serveur ; retourne l'adresse de la page d'accueil (`origin/key`), ou null sans réseau local. */
    fun start(scope: CoroutineScope): String? {
        val ip = localIp() ?: return null
        val s = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) } }.getOrNull()
            ?: ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(0)) }
        server = s
        origin = "http://$ip:${s.localPort}"
        job = scope.launch(Dispatchers.IO) {
            while (isActive && !s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                // Connexions lues en parallèle (16 au plus, les autres refusées) : un client lent ne bloque personne.
                if (!slots.tryAcquire()) { runCatching { client.close() }; continue }
                launch {
                    try { runCatching { client.use { serve(it) } }.onFailure { Log.w(TAG, "requête", it) } }
                    finally { slots.release() }
                }
            }
        }
        return "$origin/$key"
    }

    fun stop() {
        runCatching { server?.close() }
        job?.cancel()
    }

    private suspend fun serve(socket: java.net.Socket) {
        socket.soTimeout = 30_000
        val input = BufferedInputStream(socket.getInputStream())
        // En-tête complet en 15 s et 100 lignes au plus : un client qui envoie goutte à goutte ne bloque pas le serveur
        // (qui ne traite qu'une requête à la fois pour un téléphone).
        val deadline = System.currentTimeMillis() + 15_000
        fun line(): String? = if (System.currentTimeMillis() > deadline) null else readLine(input, deadline)
        val requestLine = line() ?: return
        val headers = HashMap<String, String>()
        while (true) {
            if (headers.size > 100) return
            val line = line() ?: return
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0) ?: return
        val target = parts.getOrNull(1) ?: return
        val out = socket.getOutputStream()
        fun respond(r: Response) {
            val extra = r.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
            out.write("HTTP/1.1 ${r.code}\r\nContent-Type: ${r.type}\r\nContent-Length: ${r.body.size}\r\n${extra}Cache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(r.body); out.flush()
        }
        val path = target.substringBefore('?')
        if (path != "/$key" && !path.startsWith("/$key/")) { respond(Response.notFound); return }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > maxBody) { respond(Response.text("413 Payload Too Large", "Too large")); return }
        val body = ByteArray(length.coerceAtLeast(0))
        var read = 0
        while (read < body.size) { val n = input.read(body, read, body.size - read); if (n < 0) break; read += n }
        val sub = path.removePrefix("/$key").ifEmpty { "/" }
        val request = Request(method, sub, parseQuery(target.substringAfter('?', "")), body, headers, socket.inetAddress?.hostAddress)
        // Un téléphone : traitements un par un (échange de jeton, import…) ; sinon en parallèle.
        respond(if (concurrent) handler(request) else one.withLock { handler(request) })
    }

    private fun readLine(input: InputStream, deadline: Long): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            if (System.currentTimeMillis() > deadline) return null
            val c = input.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8")
            if (c == '\n'.code) return b.toString("UTF-8").trimEnd('\r')
            b.write(c)
            if (b.size() > 8192) return null
        }
    }

    companion object {
        private const val TAG = "LocalHttpServer"

        fun parseQuery(q: String): Map<String, String> = q.split('&').filter { it.isNotEmpty() }.associate {
            val k = it.substringBefore('=')
            val v = it.substringAfter('=', "")
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
        fun js(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c") + "\""

        /** Adresse IPv4 de la tablette sur le réseau local (Wi-Fi ou Ethernet). */
        fun localIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()

        /** QR code (modules noirs sur fond blanc). */
        fun qr(text: String, size: Int = 512): Bitmap {
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
            val px = IntArray(size * size) { if (m[it % size, it / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        }
    }
}
