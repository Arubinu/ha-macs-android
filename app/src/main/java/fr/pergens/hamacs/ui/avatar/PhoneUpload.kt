package fr.pergens.hamacs.ui.avatar

import android.graphics.Bitmap
import fr.pergens.hamacs.net.LocalHttpServer
import fr.pergens.hamacs.net.LocalHttpServer.Companion.esc
import fr.pergens.hamacs.net.LocalHttpServer.Companion.js
import fr.pergens.hamacs.net.LocalHttpServer.Response
import kotlinx.coroutines.CoroutineScope

/**
 * Envoi d'une photo depuis un téléphone, sans appli : la tablette ouvre un petit serveur HTTP sur le réseau local
 * (le temps de la fenêtre), affiche son adresse en QR code avec un code aléatoire, et le téléphone y envoie la photo
 * depuis son navigateur (appareil photo ou galerie). La page réduit la photo avant l'envoi (orientation corrigée par
 * le navigateur). Un seul envoi est accepté, puis le serveur s'arrête.
 */
class PhoneUpload(private val texts: Texts, private val onPhoto: (ByteArray) -> Unit) {
    /** Textes de la page, dans la langue de l'app. */
    data class Texts(val title: String, val take: String, val choose: String, val sending: String, val done: String, val error: String)

    private val server: LocalHttpServer = LocalHttpServer(maxBody = 25_000_000) { req ->
        when {
            req.path != "/" -> Response.notFound
            req.method == "GET" -> Response.html(page())
            req.method == "POST" && req.body.isNotEmpty() -> {
                onPhoto(req.body)
                server.stop()
                Response.text("200 OK", "ok")
            }
            else -> Response.text("405 Method Not Allowed", "")
        }
    }

    /** Démarre le serveur ; retourne l'adresse à ouvrir sur le téléphone, ou null (pas de réseau local). */
    fun start(scope: CoroutineScope): String? = server.start(scope)

    fun stop() { server.stop() }

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

    companion object {
        fun qr(text: String, size: Int = 512): Bitmap = LocalHttpServer.qr(text, size)
    }
}
