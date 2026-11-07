package fr.pergens.hamacs.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.pergens.hamacs.App
import fr.pergens.hamacs.R
import fr.pergens.hamacs.ha.HaLogin
import fr.pergens.hamacs.ha.HaServer
import fr.pergens.hamacs.net.LocalHttpServer
import fr.pergens.hamacs.net.LocalHttpServer.Companion.esc
import fr.pergens.hamacs.net.LocalHttpServer.Response
import fr.pergens.hamacs.ui.components.HaIcon
import fr.pergens.hamacs.ui.theme.Dash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/**
 * Connexion à Home Assistant sans recopier de jeton (assistant et réglages) :
 * - **téléphone** : QR code vers un mini serveur de la tablette ; le téléphone se connecte à HA (son gestionnaire de
 *   mots de passe peut remplir les champs) ou y colle un jeton ;
 * - **tablette** : page de connexion de HA dans l'app.
 * Dans les deux cas l'app crée son propre jeton longue durée ([HaLogin.obtainToken]).
 */
class HaLoginState {
    var phone by mutableStateOf(false)
    var tablet by mutableStateOf(false)
}

private val PanelColor = Color(0xFF1C1C21)
private val Muted = Color(0xFFA3A3AD)
private val ErrorColor = Color(0xFFFF6B6B)

/** Boîtes de dialogue de connexion ; [onToken] reçoit le jeton longue durée (ou collé depuis le téléphone). */
@Composable
fun HaLoginDialogs(state: HaLoginState, url: String, onToken: (String) -> Unit) {
    val base = url.trim().trimEnd('/')
    if (state.phone) PhoneLoginDialog(base, { state.phone = false }) { state.phone = false; onToken(it) }
    if (state.tablet) TabletLoginDialog(base, { state.tablet = false }) { state.tablet = false; onToken(it) }
}

/** Pastilles des serveurs trouvés sur le réseau ; un appui remplit l'adresse. */
@Composable
fun HaServerChips(servers: List<HaServer>, current: String, onPick: (String) -> Unit) {
    if (servers.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.ha_discovered), style = TextStyle(fontSize = 13.sp, color = Muted))
        servers.forEach { s ->
            val selected = s.url == current.trim().trimEnd('/')
            val shape = RoundedCornerShape(50)
            Row(
                Modifier.clip(shape)
                    .background(if (selected) Dash.colors.accent.copy(alpha = 0.15f) else Color(0x14FFFFFF), shape)
                    .border(1.dp, if (selected) Dash.colors.accent else Color(0x33FFFFFF), shape)
                    .clickable { onPick(s.url) }.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HaIcon("mdi:home-assistant", size = 18.dp, tint = Dash.colors.accent)
                Text("${s.name} · ${s.url.removePrefix("http://").removePrefix("https://")}", Modifier.padding(start = 8.dp), style = TextStyle(fontSize = 14.sp, color = Color.White))
            }
        }
    }
}

enum class HaLoginMethod { Phone, Tablet, Token }

/**
 * Les trois façons de se connecter, présentées comme les choix de l'assistant (assistant et réglages).
 * Choisir téléphone ou tablette ouvre directement la connexion si l'adresse est remplie.
 */
@Composable
fun HaLoginChoices(method: HaLoginMethod, url: String, state: HaLoginState, onSelect: (HaLoginMethod) -> Unit) {
    LoginChoice("mdi:cellphone-key", stringResource(R.string.ha_login_phone), stringResource(R.string.ha_login_phone_desc), method == HaLoginMethod.Phone) {
        onSelect(HaLoginMethod.Phone); if (url.isNotBlank()) state.phone = true
    }
    LoginChoice("mdi:tablet-dashboard", stringResource(R.string.ha_login_tablet), stringResource(R.string.ha_login_tablet_desc), method == HaLoginMethod.Tablet) {
        onSelect(HaLoginMethod.Tablet); if (url.isNotBlank()) state.tablet = true
    }
    LoginChoice("mdi:key-variant", stringResource(R.string.ha_login_token_title), stringResource(R.string.ha_login_token_desc), method == HaLoginMethod.Token) {
        onSelect(HaLoginMethod.Token)
    }
}

/** Ligne de choix (icône, titre, description, bouton radio) : même rendu que `Choice` de l'assistant. */
@Composable
fun LoginChoice(icon: String, title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val surface = Color(0xFF19191D)
    val outline = Color(0xFF2C2C33)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (selected) Dash.colors.accent.copy(alpha = 0.08f) else surface, shape)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Dash.colors.accent else outline, shape)
            .clickable(onClick = onClick).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(Dash.colors.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            HaIcon(icon, size = 28.dp, tint = Dash.colors.accent)
        }
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(title, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Color(0xFFF2F2F5)))
            Text(desc, Modifier.padding(top = 4.dp), style = TextStyle(fontSize = 14.sp, color = Muted, lineHeight = 20.sp))
        }
        Box(
            Modifier.size(26.dp).clip(CircleShape).border(2.dp, if (selected) Dash.colors.accent else Muted, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected) Box(Modifier.size(14.dp).clip(CircleShape).background(Dash.colors.accent)) }
    }
}

private enum class Step { Waiting, Creating, Error }

private fun randomState() = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

@Composable
private fun PhoneLoginDialog(base: String, onClose: () -> Unit, onToken: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(Step.Waiting) }
    var error by remember { mutableStateOf<String?>(null) }
    var address by remember { mutableStateOf<String?>(null) }
    var started by remember { mutableStateOf(false) }
    val t = PhoneTexts(
        stringResource(R.string.ha_phone_page_title), stringResource(R.string.ha_phone_page_login),
        stringResource(R.string.ha_phone_page_token), stringResource(R.string.ha_phone_page_send),
        stringResource(R.string.ha_phone_page_done), stringResource(R.string.ha_phone_page_error),
    )
    val server = remember {
        lateinit var self: LocalHttpServer
        self = LocalHttpServer { req ->
            val origin = self.origin.orEmpty()
            val clientId = "$origin/"
            val redirect = "$origin/${self.key}/cb"
            suspend fun finish(token: String): Response {
                withContext(Dispatchers.Main) { onToken(token) }
                self.stop()
                return Response.html(phonePage(t, self.key, t.done, form = false))
            }
            when {
                req.method == "GET" && req.path == "/" -> Response.html(phonePage(t, self.key, null, form = true))
                req.method == "GET" && req.path == "/login" -> Response.redirect(HaLogin.authorizeUrl(base, clientId, redirect, self.key))
                req.method == "GET" && req.path == "/cb" -> {
                    val code = req.query["code"]
                    if (code == null || req.query["state"] != self.key) Response.html(phonePage(t, self.key, t.error, form = true))
                    else {
                        withContext(Dispatchers.Main) { step = Step.Creating }
                        runCatching { HaLogin.obtainToken(App.instance.http, base, clientId, code) }.fold(
                            onSuccess = { finish(it) },
                            onFailure = {
                                withContext(Dispatchers.Main) { step = Step.Error; error = it.message ?: it.javaClass.simpleName }
                                Response.html(phonePage(t, self.key, t.error, form = true))
                            },
                        )
                    }
                }
                req.method == "POST" && req.path == "/token" -> {
                    val token = req.form()["token"]?.trim().orEmpty()
                    if (token.length < 20) Response.html(phonePage(t, self.key, t.error, form = true)) else finish(token)
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
            Modifier.clip(RoundedCornerShape(20.dp)).background(PanelColor).padding(28.dp).width(460.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.ha_login_phone_title), style = TextStyle(fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Medium))
            if (qr != null) {
                Text(stringResource(R.string.ha_login_phone_help), style = TextStyle(fontSize = 14.sp, color = Muted, textAlign = TextAlign.Center, lineHeight = 20.sp))
                Image(qr, null, Modifier.size(260.dp).clip(RoundedCornerShape(12.dp)))
                Text(address.orEmpty(), style = TextStyle(fontSize = 12.sp, color = Muted))
                StatusRow(step, error, R.string.ha_login_waiting_phone)
            } else if (started) {
                Text(stringResource(R.string.avatar_phone_no_network), style = TextStyle(fontSize = 14.sp, color = Color(0xFFFFB74D)))
            }
            CloseButton(onClose)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TabletLoginDialog(base: String, onClose: () -> Unit, onToken: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val state = remember { randomState() }
    var step by remember { mutableStateOf(Step.Waiting) }
    var error by remember { mutableStateOf<String?>(null) }
    val authorize = remember(base) { HaLogin.authorizeUrl(base, HaLogin.APP_CLIENT_ID, HaLogin.APP_REDIRECT, state) }

    fun handleRedirect(url: String): Boolean {
        if (!url.startsWith(HaLogin.APP_REDIRECT)) return false
        val uri = Uri.parse(url)
        val code = uri.getQueryParameter("code")
        if (code == null || uri.getQueryParameter("state") != state) { step = Step.Error; error = "state"; return true }
        step = Step.Creating
        scope.launch {
            runCatching { HaLogin.obtainToken(App.instance.http, base, HaLogin.APP_CLIENT_ID, code) }
                .onSuccess { onToken(it) }
                .onFailure { step = Step.Error; error = it.message ?: it.javaClass.simpleName }
        }
        return true
    }

    DisposableEffect(Unit) {
        onDispose {
            // Aucune session HA ne reste dans le navigateur intégré.
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().padding(40.dp).clip(RoundedCornerShape(20.dp)).background(PanelColor)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                HaIcon("mdi:home-assistant", size = 24.dp, tint = Dash.colors.accent)
                Text(stringResource(R.string.ha_login_tablet_title), Modifier.padding(start = 12.dp).weight(1f), style = TextStyle(fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.Medium))
                if (step != Step.Waiting) StatusRow(step, error, R.string.ha_login_waiting_phone)
                Box(Modifier.padding(start = 12.dp).size(48.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    HaIcon("mdi:close", size = 26.dp, tint = Color.White)
                }
            }
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))) {
                AndroidView(
                    factory = {
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                    handleRedirect(request.url.toString())
                            }
                            loadUrl(authorize, fr.pergens.hamacs.App.instance.proxyHeadersFor(authorize))
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { it.stopLoading(); it.destroy() },
                )
                if (step == Step.Creating) {
                    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Dash.colors.accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(step: Step, error: String?, waiting: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (step) {
            Step.Error -> Text(stringResource(R.string.ha_login_error, error.orEmpty()), style = TextStyle(fontSize = 14.sp, color = ErrorColor))
            else -> {
                CircularProgressIndicator(Modifier.size(18.dp), color = Dash.colors.accent, strokeWidth = 2.dp)
                Text(
                    stringResource(if (step == Step.Creating) R.string.ha_login_creating else waiting),
                    Modifier.padding(start = 10.dp), style = TextStyle(fontSize = 14.sp, color = Color.White),
                )
            }
        }
    }
}

@Composable
private fun CloseButton(onClose: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Text(
        stringResource(R.string.cancel),
        Modifier.heightIn(min = 48.dp).clip(shape).border(1.dp, Color(0x33FFFFFF), shape).clickable(onClick = onClose)
            .padding(horizontal = 24.dp, vertical = 13.dp),
        style = TextStyle(fontSize = 15.sp, color = Color.White),
    )
}

private class PhoneTexts(val title: String, val login: String, val token: String, val send: String, val done: String, val error: String)

/** Page du téléphone : bouton de connexion à HA, ou jeton à coller. */
private fun phonePage(t: PhoneTexts, key: String, message: String?, form: Boolean): String = """
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(t.title)}</title>
<style>
body{margin:0;min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:18px;
background:#111;color:#fff;font-family:system-ui,sans-serif;padding:24px;box-sizing:border-box;text-align:center}
h1{font-size:22px;font-weight:600;margin:0 0 8px}
a,button{display:block;width:100%;max-width:340px;box-sizing:border-box;padding:18px;border-radius:16px;border:0;
background:#8cf5ff;color:#000;font-size:18px;font-weight:600;text-decoration:none;font-family:inherit}
button{background:#2a2a2a;color:#fff}
form{display:flex;flex-direction:column;align-items:center;gap:12px;width:100%;max-width:340px;margin-top:18px}
p{color:#aaa;margin:0}
input{width:100%;box-sizing:border-box;padding:14px;border-radius:12px;border:1px solid #444;background:#1c1c21;color:#fff;font-size:15px}
.m{font-size:17px;color:#fff;max-width:340px}
</style></head><body>
<h1>${esc(t.title)}</h1>
${if (message != null) "<div class=\"m\">${esc(message)}</div>" else ""}
${if (form) """
<a href="/$key/login">🏠 ${esc(t.login)}</a>
<form method="post" action="/$key/token">
<p>${esc(t.token)}</p>
<input name="token" autocomplete="off" autocapitalize="off" spellcheck="false">
<button type="submit">${esc(t.send)}</button>
</form>""" else ""}
</body></html>
""".trimIndent()
