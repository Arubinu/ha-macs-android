package fr.pergens.hamacs.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import fr.pergens.hamacs.R
import fr.pergens.hamacs.net.LocalHttpServer
import fr.pergens.hamacs.net.LocalHttpServer.Companion.esc
import fr.pergens.hamacs.net.LocalHttpServer.Companion.js
import fr.pergens.hamacs.net.LocalHttpServer.Response
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sauvegarde par le téléphone, sans appli : QR code vers une page servie par la tablette, d'où le téléphone télécharge
 * la sauvegarde de la tablette ou lui envoie un fichier de sauvegarde (appliqué comme un import).
 */
@Composable
fun PhoneTransferDialog(onClose: () -> Unit, export: () -> String, onImport: (String) -> Pair<String, Boolean>) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    var address by remember { mutableStateOf<String?>(null) }
    var started by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val t = TransferTexts(
        stringResource(R.string.transfer_page_title), stringResource(R.string.transfer_page_download),
        stringResource(R.string.transfer_page_upload), stringResource(R.string.transfer_page_sending),
        stringResource(R.string.transfer_page_note), stringResource(R.string.avatar_phone_page_error),
        stringResource(R.string.transfer_page_crashes),
    )
    val server = remember {
        lateinit var self: LocalHttpServer
        self = LocalHttpServer(maxBody = 64_000_000) { req ->
            when {
                req.method == "GET" && req.path == "/" -> Response.html(page(t, self.key, fr.pergens.hamacs.CrashGuard.entries(ctx).isNotEmpty()))
                // Fichier nommé comme l'export des réglages.
                req.method == "GET" && req.path == "/export" -> {
                    val body = withContext(Dispatchers.Main) { export() }.toByteArray()
                    Response(
                        "200 OK", "application/json; charset=utf-8", body,
                        mapOf("Content-Disposition" to "attachment; filename=\"ha-dashboard-${java.time.LocalDate.now()}.json\""),
                    )
                }
                // Journal des plantages (s'il y en a), pour le transmettre.
                req.method == "GET" && req.path == "/crashes" -> Response(
                    "200 OK", "text/plain; charset=utf-8",
                    fr.pergens.hamacs.CrashGuard.entries(ctx).joinToString("\n\n").toByteArray(),
                    mapOf("Content-Disposition" to "attachment; filename=\"ha-macs-crashes-${java.time.LocalDate.now()}.txt\""),
                )
                req.method == "POST" && req.path == "/import" -> {
                    val r = withContext(Dispatchers.Main) { onImport(req.body.toString(Charsets.UTF_8)).also { result = it } }
                    Response.text(if (r.second) "400 Bad Request" else "200 OK", r.first)
                }
                else -> Response.notFound
            }
        }
        self
    }
    DisposableEffect(Unit) {
        address = runCatching { server.start(scope) }.getOrNull()
        started = true
        onDispose { server.stop() }
    }
    val qr = remember(address) { address?.let { LocalHttpServer.qr(it).asImageBitmap() } }
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFF1C1C21)).padding(28.dp).width(460.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.transfer_phone_title), style = TextStyle(fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Medium))
            if (qr != null) {
                Text(stringResource(R.string.transfer_phone_help), style = TextStyle(fontSize = 14.sp, color = Color(0xFFA3A3AD), textAlign = TextAlign.Center, lineHeight = 20.sp))
                Image(qr, null, Modifier.size(260.dp).clip(RoundedCornerShape(12.dp)))
                Text(address.orEmpty(), style = TextStyle(fontSize = 12.sp, color = Color(0xFFA3A3AD)))
                result?.let { (text, error) ->
                    Text(text, style = TextStyle(fontSize = 14.sp, color = if (error) Color(0xFFFF6B6B) else Color(0xFF4CD964), textAlign = TextAlign.Center))
                }
            } else if (started) {
                Text(stringResource(R.string.avatar_phone_no_network), style = TextStyle(fontSize = 14.sp, color = Color(0xFFFFB74D)))
            }
            val shape = RoundedCornerShape(14.dp)
            Text(
                stringResource(R.string.close),
                Modifier.heightIn(min = 48.dp).clip(shape).border(1.dp, Color(0x33FFFFFF), shape).clickable(onClick = onClose)
                    .padding(horizontal = 24.dp, vertical = 13.dp),
                style = TextStyle(fontSize = 15.sp, color = Color.White),
            )
        }
    }
}

private class TransferTexts(val title: String, val download: String, val upload: String, val sending: String, val note: String, val error: String, val crashes: String)

/** Page du téléphone : télécharger la sauvegarde de la tablette, ou lui en envoyer une. */
private fun page(t: TransferTexts, key: String, crashes: Boolean): String = """
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(t.title)}</title>
<style>
body{margin:0;min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:18px;
background:#111;color:#fff;font-family:system-ui,sans-serif;padding:24px;box-sizing:border-box;text-align:center}
h1{font-size:22px;font-weight:600;margin:0 0 8px}
a,label{display:block;width:100%;max-width:340px;box-sizing:border-box;padding:18px;border-radius:16px;
background:#8cf5ff;color:#000;font-size:18px;font-weight:600;text-decoration:none}
label,a.alt{background:#2a2a2a;color:#fff}
input{display:none}#s{min-height:24px;color:#aaa;max-width:340px}p{color:#888;font-size:14px;max-width:340px;margin:0}
</style></head><body>
<h1>${esc(t.title)}</h1>
<a href="/$key/export" download>⬇️ ${esc(t.download)}</a>
<label>⬆️ ${esc(t.upload)}<input id="f" type="file" accept=".json,application/json,text/plain"></label>
${if (crashes) "<a class=\"alt\" href=\"/$key/crashes\" download>📄 ${esc(t.crashes)}</a>" else ""}
<div id="s"></div>
<p>🔒 ${esc(t.note)}</p>
<script>
const s=document.getElementById('s');
document.getElementById('f').onchange=async e=>{
  const f=e.target.files[0]; if(!f)return; s.textContent=${js(t.sending)};
  try{const r=await fetch('/$key/import',{method:'POST',body:await f.text()}); s.textContent=await r.text();}
  catch(x){s.textContent=${js(t.error)};}
};
</script></body></html>
""".trimIndent()
