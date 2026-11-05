package fr.arubinu.hadash.ui.avatar

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
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * Envoi d'une photo depuis un téléphone, sans appli : la tablette ouvre un petit serveur HTTP sur le réseau local
 * (le temps de la fenêtre), affiche son adresse en QR code avec un code aléatoire, et le téléphone y envoie la photo
 * depuis son navigateur (appareil photo ou galerie). La page réduit la photo avant l'envoi (orientation corrigée par
 * le navigateur). Un seul envoi est accepté, puis le serveur s'arrête.
 */
class PhoneUpload(private val texts: Texts, private val onPhoto: (ByteArray) -> Unit) {
    /** Textes de la page, dans la langue de l'app. */
    data class Texts(val title: String, val take: String, val choose: String, val sending: String, val done: String, val error: String)

    private val token = ByteArray(9).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private var server: ServerSocket? = null
    private var job: Job? = null

    /** Démarre le serveur ; retourne l'adresse à ouvrir sur le téléphone, ou null (pas de réseau local). */
    fun start(scope: CoroutineScope): String? {
        val ip = localIp() ?: return null
        val s = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(0)) }
        server = s
        job = scope.launch(Dispatchers.IO) {
            while (isActive && !s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                runCatching { client.use { handle(it) } }.onFailure { Log.w("PhoneUpload", "requête", it) }
            }
        }
        return "http://$ip:${s.localPort}/$token"
    }

    fun stop() {
        runCatching { server?.close() }
        job?.cancel()
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 30_000
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0) ?: return
        val path = parts.getOrNull(1)?.substringBefore('?') ?: return
        val out = socket.getOutputStream()
        fun respond(code: String, type: String, body: ByteArray) {
            out.write("HTTP/1.1 $code\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(body); out.flush()
        }
        if (path != "/$token") { respond("404 Not Found", "text/plain", "Not found".toByteArray()); return }
        when (method) {
            "GET" -> respond("200 OK", "text/html; charset=utf-8", page().toByteArray())
            "POST" -> {
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                if (length <= 0 || length > 25_000_000) { respond("413 Payload Too Large", "text/plain", "Too large".toByteArray()); return }
                val body = ByteArray(length)
                var read = 0
                while (read < length) { val n = input.read(body, read, length - read); if (n < 0) break; read += n }
                respond("200 OK", "text/plain", "ok".toByteArray())
                onPhoto(body)
                stop()
            }
            else -> respond("405 Method Not Allowed", "text/plain", "".toByteArray())
        }
    }

    private fun readLine(input: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8")
            if (c == '\n'.code) return b.toString("UTF-8").trimEnd('\r')
            b.write(c)
            if (b.size() > 8192) return null
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

    /** Page envoyée au téléphone : deux boutons (appareil photo, galerie), photo réduite à 1600 px puis envoyée. */
    private fun page(): String = """
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(texts.title)}</title>
<style>
body{margin:0;min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:18px;
background:#111;color:#fff;font-family:system-ui,sans-serif;padding:24px;box-sizing:border-box;text-align:center}
h1{font-size:22px;font-weight:600;margin:0 0 8px}
label{display:block;width:100%;max-width:340px;padding:18px;border-radius:16px;background:#8cf5ff;color:#000;font-size:18px;font-weight:600}
label.alt{background:#2a2a2a;color:#fff}
input{display:none}#s{min-height:24px;color:#aaa}
</style></head><body>
<h1>${esc(texts.title)}</h1>
<label>📷 ${esc(texts.take)}<input id="c" type="file" accept="image/*" capture="user"></label>
<label class="alt">🖼️ ${esc(texts.choose)}<input id="g" type="file" accept="image/*"></label>
<div id="s"></div>
<script>
const s=document.getElementById('s');
async function send(f){
  if(!f)return; s.textContent=${js(texts.sending)};
  try{
    const img=await createImageBitmap(f);
    const k=Math.min(1,1600/Math.max(img.width,img.height));
    const c=document.createElement('canvas'); c.width=Math.round(img.width*k); c.height=Math.round(img.height*k);
    c.getContext('2d').drawImage(img,0,0,c.width,c.height);
    const blob=await new Promise(r=>c.toBlob(r,'image/jpeg',0.92));
    const r=await fetch(location.pathname,{method:'POST',body:blob});
    s.textContent=r.ok?${js(texts.done)}:${js(texts.error)};
  }catch(e){s.textContent=${js(texts.error)};}
}
document.getElementById('c').onchange=e=>send(e.target.files[0]);
document.getElementById('g').onchange=e=>send(e.target.files[0]);
</script></body></html>
""".trimIndent()

    private fun js(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c") + "\""

    companion object {
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
