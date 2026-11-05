package fr.arubinu.hadash.macs

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import fr.arubinu.hadash.config.ConfigRepository
import fr.arubinu.hadash.config.b
import fr.arubinu.hadash.config.d
import fr.arubinu.hadash.config.i
import fr.arubinu.hadash.config.o
import fr.arubinu.hadash.config.s
import fr.arubinu.hadash.ha.EntityState
import fr.arubinu.hadash.ha.HaClient
import fr.arubinu.hadash.voice.VoiceAssistant
import fr.arubinu.hadash.voice.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Origine locale de la copie embarquée de M.A.C.S. (assets/macs, servis par shouldInterceptRequest). */
private const val LOCAL_ORIGIN = "https://macs.local"

/**
 * Pilote le personnage M.A.C.S. (copie embarquée de la page de l'intégration HA, ou la page servie par HA
 * sur /macs/macs.html) dans une WebView unique, réutilisée entre l'accueil et le dashboard pour éviter tout
 * rechargement. Remplace le « backend » JS de macs-card : humeur, météo, batterie et bulles de dialogue.
 */
class MacsController(
    private val context: Context,
    private val ha: HaClient,
    private val config: ConfigRepository,
    private val voice: VoiceAssistant,
    private val scope: CoroutineScope,
) {
    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var ready = false
    private var loadedFor: String? = null
    private var last: Snapshot? = null
    private var lastTurns: JsonArray? = null
    private var lastAutoBrightness: String? = null

    private fun autoBrightnessKey(): String {
        val c = macsConfig()
        return listOf("auto_brightness_enabled", "auto_brightness_timeout_minutes", "auto_brightness_min", "auto_brightness_max",
            "auto_brightness_pause_animations", "assist_satellite", "max_turns").joinToString("|") { c[it]?.toString().orEmpty() }
    }

    private data class Snapshot(
        val mood: String,
        val temperature: Double?,
        val windspeed: Double?,
        val precipitation: Double?,
        val battery: Double?,
        val charging: Boolean?,
        val conditions: Map<String, Boolean>,
        val brightness: Double = 100.0,
        val animations: Boolean = true,
    )

    /** WebView qui laisse passer les appuis : les cartes situées sous M.A.C.S. restent utilisables. */
    private class PassThroughWebView(ctx: Context) : WebView(ctx) {
        @SuppressLint("ClickableViewAccessibility")
        override fun dispatchTouchEvent(event: android.view.MotionEvent?) = false
    }


    /**
     * Installe la WebView dans une couche native au-dessus de l'interface Compose. Elle peut ainsi
     * déborder de sa carte (l'écharpe passe devant l'alarme) avec un fond transparent, sans bloquer
     * les appuis destinés aux cartes en dessous.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun attach(host: android.widget.FrameLayout) {
        if (fr.arubinu.hadash.BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        val wv = webView ?: PassThroughWebView(host.context.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            visibility = android.view.View.INVISIBLE
            webViewClient = WebViewClient()
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    android.util.Log.d("MacsWeb", "${m.messageLevel()} ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                    return true
                }
            }
            addJavascriptInterface(Bridge(), "Android")
            webView = this
        }
        overlayHost = host
        (wv.parent as? ViewGroup)?.removeView(wv)
        // Taille réelle dès le départ (même masquée) : à 1×1 px, M.A.C.S. tarde à s'initialiser.
        host.addView(
            wv,
            android.widget.FrameLayout.LayoutParams(
                host.resources.displayMetrics.widthPixels, host.resources.displayMetrics.heightPixels,
            ),
        )
        ensureLoaded()
        applyClaims()
    }

    /**
     * Une carte M.A.C.S. affichée réclame la WebView unique : intégrée à l'interface (inline) ou dans
     * la couche au premier plan (front). La dernière carte apparue l'emporte ; quand elle disparaît,
     * la WebView revient à la précédente (transitions entre écrans).
     */
    private sealed interface Claim {
        class Inline(val container: android.widget.FrameLayout) : Claim
        class Front(var bounds: android.graphics.Rect? = null, var hidden: Boolean = false) : Claim
    }

    private val claims = LinkedHashMap<Any, Claim>()
    private var overlayHost: android.widget.FrameLayout? = null

    fun claimInline(token: Any, container: android.widget.FrameLayout) {
        claims.remove(token)
        claims[token] = Claim.Inline(container)
        applyClaims()
    }

    fun claimFront(token: Any) {
        if (claims.containsKey(token)) return
        claims[token] = Claim.Front()
        applyClaims()
    }

    /** Position (coordonnées fenêtre, px) d'une carte « front » ; [hidden] masque M.A.C.S. (réglages ouverts). */
    fun updateFront(token: Any, bounds: android.graphics.Rect?, hidden: Boolean) {
        val claim = claims[token] as? Claim.Front ?: return
        claim.bounds = bounds
        claim.hidden = hidden
        if (claims.keys.lastOrNull() === token) applyClaims()
    }

    fun release(token: Any) {
        if (claims.remove(token) != null) applyClaims()
    }

    private fun applyClaims() {
        val wv = webView ?: return
        when (val active = claims.values.lastOrNull()) {
            null -> wv.visibility = android.view.View.INVISIBLE
            is Claim.Inline -> {
                moveTo(active.container)
                wv.layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                )
                wv.translationX = 0f
                wv.translationY = 0f
                wv.visibility = android.view.View.VISIBLE
            }
            is Claim.Front -> {
                moveTo(overlayHost ?: return)
                val b = active.bounds
                if (b == null || active.hidden || b.width() <= 0 || b.height() <= 0) {
                    wv.visibility = android.view.View.INVISIBLE
                    return
                }
                val lp = wv.layoutParams as android.widget.FrameLayout.LayoutParams
                if (lp.width != b.width() || lp.height != b.height()) {
                    lp.width = b.width()
                    lp.height = b.height()
                    wv.layoutParams = lp
                }
                wv.translationX = b.left.toFloat()
                wv.translationY = b.top.toFloat()
                wv.visibility = android.view.View.VISIBLE
            }
        }
    }

    private fun moveTo(parent: android.widget.FrameLayout) {
        val wv = webView ?: return
        if (wv.parent === parent) return
        (wv.parent as? ViewGroup)?.removeView(wv)
        parent.addView(wv, android.widget.FrameLayout.LayoutParams(wv.width.coerceAtLeast(1), wv.height.coerceAtLeast(1)))
    }

    private fun ensureLoaded() {
        val wv = webView ?: return
        // Par défaut, copie embarquée de la page M.A.C.S. (assets/macs), servie sous une origine locale :
        // le rendu ne dépend plus de Home Assistant. `"macs": { "source": "ha" }` utilise la page de l'intégration.
        val local = macsConfig().s("source", "local") != "ha"
        val base = if (local) LOCAL_ORIGIN else config.settings.value.haUrl.trimEnd('/')
        if (loadedFor == base) return
        loadedFor = base
        ready = false
        last = null
        lastTurns = null
        val html = context.assets.open("macs_host.html").bufferedReader().use { it.readText() }
        wv.loadDataWithBaseURL("$base/", html, "text/html", "utf-8", null)
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                val u = request.url
                if (!local || u.scheme + "://" + u.host != LOCAL_ORIGIN) return null
                val path = u.path?.trimStart('/').orEmpty()
                val mime = when (path.substringAfterLast('.').lowercase()) {
                    "js" -> "text/javascript"; "css" -> "text/css"; "json" -> "application/json"; "html" -> "text/html"
                    "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "svg" -> "image/svg+xml"; "gif" -> "image/gif"
                    else -> "application/octet-stream"
                }
                return runCatching {
                    android.webkit.WebResourceResponse(mime, "utf-8", context.assets.open(path)).apply {
                        responseHeaders = mapOf("Cache-Control" to "no-cache")
                    }
                }.getOrElse {
                    android.webkit.WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), "".byteInputStream())
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                val snap = snapshot()
                val src = "$base/macs/macs.html?mood=${snap.mood}&brightness=100" +
                    (snap.temperature?.let { "&temperature=$it" } ?: "") +
                    (snap.precipitation?.let { "&precipitation=$it" } ?: "") +
                    (snap.battery?.let { "&battery_charge=$it" } ?: "")
                view.evaluateJavascript("window.macsTheme(${themeMap()})", null)
                view.evaluateJavascript("window.macsLoad(${JsonPrimitive(src)})", null)
            }
        }
    }

    /**
     * Variables CSS du thème de M.A.C.S. (frontend/styles/themes/default.css) recolorées pour la couleur
     * principale : teinte décalée de l'écart entre cette couleur et l'accent d'origine (#8CF5FF), saturation
     * proportionnelle, luminosité conservée (reflets et ombres intacts). Résultat JSON
     * `{"--variable": ["#origine", "#nouvelle"]}` ; `{}` = couleurs d'origine.
     */
    private fun themeMap(): String {
        val accent = config.settings.value.accentColor.takeIf { it.isNotBlank() }
            ?: config.dashboard.value.o("theme")?.o("colors")?.s("accent")
        val c = fr.arubinu.hadash.ui.theme.parseCssColor(accent) ?: return "{}"
        val target = FloatArray(3).also { androidx.core.graphics.ColorUtils.colorToHSL(c.toArgbInt(), it) }
        val ref = FloatArray(3).also { androidx.core.graphics.ColorUtils.colorToHSL(0xFF8CF5FF.toInt(), it) }
        if (kotlin.math.abs(target[0] - ref[0]) < 1f && kotlin.math.abs(target[1] - ref[1]) < 0.02f) return "{}"
        val shift = target[0] - ref[0]
        val satK = if (ref[1] > 0f) target[1] / ref[1] else 1f
        val vars = linkedMapOf(
            "sclera-gradient-shadow" to 0xFF001D14, "sclera-gradient-fill" to 0xFF00C7BD, "sclera-outer-glow" to 0xFF46F6D8,
            "sclera-inner-glow" to 0xFF038C7C, "sclera-charging-glow" to 0xFF5DFFE3, "iris-outer-glow" to 0xFF46F6D8,
            "iris-inner-glow" to 0xFF038C7C, "pupil-inner-glow" to 0xFF8CF5FF, "pupil-outer-glow" to 0xFF8CF5FF,
            "mouth-fill" to 0xFF6DD3D7, "mouth-glow" to 0xFF30CFC6, "brow-fill" to 0xFF6DD3D7, "brow-glow" to 0xFF00FFDD,
        )
        val body = vars.entries.joinToString(",") { (name, argb) ->
            val hsl = FloatArray(3).also { androidx.core.graphics.ColorUtils.colorToHSL(argb.toInt(), it) }
            hsl[0] = ((hsl[0] + shift) % 360f + 360f) % 360f
            hsl[1] = (hsl[1] * satK).coerceIn(0f, 1f)
            val out = androidx.core.graphics.ColorUtils.HSLToColor(hsl)
            "\"--$name\":[\"#%06x\",\"#%06x\"]".format(argb.toInt() and 0xFFFFFF, out and 0xFFFFFF)
        }
        return "{$body}"
    }

    private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int = android.graphics.Color.argb(
        (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
    )

    fun start() {
        scope.launch {
            // conflate + pause : au plus ~3 mises à jour/s, quel que soit le trafic d'états HA.
            kotlinx.coroutines.flow.merge(ha.states, voice.state, voice.turns, voice.emotion, config.dashboard, config.blocks).conflate().collect {
                withContext(Dispatchers.Main) { if (ready) pushDeltas() }
                delay(300)
            }
        }
        scope.launch {
            config.settings.distinctUntilChanged { a, b -> a.haUrl == b.haUrl }.collect {
                withContext(Dispatchers.Main) { ensureLoaded() }
            }
        }
        scope.launch {
            // Couleur principale changée (Réglages ou thème du dashboard) : recoloration immédiate.
            combine(config.settings, config.dashboard) { s, d -> s.accentColor to d.o("theme")?.o("colors")?.s("accent") }
                .distinctUntilChanged().collect {
                    withContext(Dispatchers.Main) { webView?.evaluateJavascript("window.macsTheme && window.macsTheme(${themeMap()})", null) }
                }
        }
    }

    /** Options de M.A.C.S. : `macs` de dashboard.json, puis les réglages faits dans l'app (mode édition). */
    fun macsConfig(): JsonObject {
        val base = config.dashboard.value.o("macs") ?: JsonObject(emptyMap())
        val local = config.blocks.value[MacsOptions.KEY]?.o("config") ?: return base
        return JsonObject(base + local)
    }

    /** Suivi d'un satellite Assist : écoute → réflexion → réponse = content, sinon perplexe (comme la carte). */
    private var satPrev = "idle"
    private var satRun: BooleanArray? = null
    private var satRunStart = 0L
    private var satOverride: String? = null
    private var satOverrideUntil = 0L

    private fun satelliteMood(state: String?): String? {
        val st = (state ?: return null).trim().lowercase()
        val now = System.currentTimeMillis()
        if (satRun != null && now - satRunStart > 15_000) satRun = null
        val prev = satPrev
        satPrev = st
        if (st == "listening" && prev != "listening") {
            satRun = booleanArrayOf(true, false, false); satRunStart = now
        } else satRun?.let { run ->
            if (st == "processing") run[1] = true
            if (st == "responding") run[2] = true
            if (st == "idle" && prev != "idle") {
                satOverride = if (run.all { it }) "happy" else "confused"
                satOverrideUntil = now + 1000
                satRun = null
                main.postDelayed({ if (ready) pushDeltas() }, 1100)
            }
        }
        if (satOverride != null && now <= satOverrideUntil) return satOverride
        return when (st) { "listening" -> "listening"; "thinking", "processing", "responding", "speaking" -> "thinking"; else -> "idle" }
    }

    private fun normalize(value: Double?, min: Double, max: Double): Double? {
        if (value == null) return null
        val lo = minOf(min, max)
        val hi = maxOf(min, max)
        return Math.round((value.coerceIn(lo, hi) - lo) / (hi - lo) * 10000.0) / 100.0
    }

    private fun deviceBattery(): Pair<Double?, Boolean?> {
        val i: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null to null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return (if (level >= 0) level * 100.0 / scale else null) to plugged
    }

    private fun conditionsFor(text: String?): Map<String, Boolean> {
        val keys = listOf("snowy", "cloudy", "rainy", "windy", "sunny", "stormy", "foggy", "hail", "lightning",
            "partlycloudy", "pouring", "clear_night", "exceptional")
        val f = keys.associateWith { false }.toMutableMap()
        val t = (text ?: "").lowercase().replace('-', '_')
        if (t.isBlank() || t == "unknown" || t == "unavailable") return f
        fun has(vararg tokens: String) = tokens.any { t.contains(it) }
        if (has("partlycloudy", "partly_cloudy")) { f["partlycloudy"] = true; f["cloudy"] = true }
        if (has("clear_night")) f["clear_night"] = true
        if (has("snow")) f["snowy"] = true
        if (has("rain")) f["rainy"] = true
        if (has("pouring")) { f["pouring"] = true; f["rainy"] = true }
        if (has("wind")) f["windy"] = true
        if (has("cloud", "overcast")) f["cloudy"] = true
        if (has("sun")) f["sunny"] = true
        if (has("storm")) f["stormy"] = true
        if (has("fog")) f["foggy"] = true
        if (has("hail")) f["hail"] = true
        if (has("lightning")) f["lightning"] = true
        if (has("exceptional")) f["exceptional"] = true
        return f
    }

    private fun snapshot(): Snapshot {
        val cfg = macsConfig()
        val states = ha.states.value
        fun num(key: String): Double? = cfg.s(key)?.let { states[it] }?.numeric
        val voiceMood = when (voice.state.value) {
            VoiceState.Listening -> "listening"
            VoiceState.Thinking -> "thinking"
            VoiceState.Error -> "confused"
            else -> voice.emotion.value
        }
        val baseMood = states[cfg.s("mood_entity") ?: "select.macs_mood"]?.state?.takeIf { it != "unknown" && it != "unavailable" } ?: "idle"
        val satMood = cfg.s("assist_satellite")?.takeIf { it.isNotBlank() }?.let { satelliteMood(states[it]?.state) }
        val batteryMode = cfg.s("battery", "device")
        val (devBat, devCharging) = if (batteryMode == "device") deviceBattery() else null to null
        // Capteur numérique ramené sur 0–100 avec son unité (configurée, sinon celle du capteur) et sa plage.
        fun scaled(kind: MacsOptions.Kind, entityKey: String): Double? {
            val st = cfg.s(entityKey)?.takeIf { it.isNotBlank() }?.let { states[it] } ?: return null
            val v = st.numeric ?: return null
            val unit = MacsOptions.unit(kind, cfg.s(kind.unitKey), st.unit)
            val (dMin, dMax) = MacsOptions.defaultRange(kind, unit)
            return normalize(v, cfg.d(kind.minKey) ?: dMin, cfg.d(kind.maxKey) ?: dMax)
        }
        // Sans capteur dédié : valeur de l'entité météo (attribut `temperature`, `wind_speed`…), sans template dans HA.
        val weather = cfg.s("weather_entity")?.let { states[it] }
        fun fromWeather(kind: MacsOptions.Kind, attr: String, unitAttr: String): Double? {
            val v = weather?.attrDouble(attr) ?: return null
            val unit = MacsOptions.unit(kind, cfg.s(kind.unitKey), weather.attrString(unitAttr))
            val (dMin, dMax) = MacsOptions.defaultRange(kind, unit)
            return normalize(v, cfg.d(kind.minKey) ?: dMin, cfg.d(kind.maxKey) ?: dMax)
        }
        val battery = devBat?.let { normalize(it, 0.0, 100.0) } ?: if (batteryMode == "sensor") scaled(MacsOptions.Kind.Battery, "battery_sensor") else null
        val charging = devCharging ?: if (batteryMode == "sensor") cfg.s("charging_sensor")?.let { states[it] }?.let { MacsOptions.charging(it) } else null
        // Capteur non configuré → valeur manuelle de l'intégration MACS (number.macs_*, déjà sur 0–100),
        // comme le fait macs-card.
        fun manual(id: String) = states[id]?.numeric?.coerceIn(0.0, 100.0)
        val conditions = cfg.s("weather_entity")?.let { conditionsFor(states[it]?.state) }
            ?: conditionsFor(null).mapValues { (k, _) -> states["switch.macs_weather_conditions_$k"]?.state == "on" }
        val batteryValue = if (batteryMode == "none") null else battery ?: manual("number.macs_battery_charge")
        val chargingValue = if (batteryMode == "none") null else charging ?: states["switch.macs_charging"]?.let { it.state == "on" }
        // Humeur : assistant vocal de l'app, puis satellite Assist (s'il est actif), puis l'entité d'humeur ;
        // batterie faible (≤ 20 %) hors charge = triste, comme la carte d'origine.
        var mood = voiceMood ?: satMood?.takeIf { it != "idle" } ?: baseMood
        if (voiceMood == null && (satMood == null || satMood == "idle") && batteryValue != null && batteryValue <= 20.0 && chargingValue != true &&
            cfg.b("low_battery_sad", true)) mood = "sad"
        return Snapshot(
            mood = mood,
            temperature = scaled(MacsOptions.Kind.Temperature, "temperature_sensor")
                ?: fromWeather(MacsOptions.Kind.Temperature, "temperature", "temperature_unit") ?: manual("number.macs_temperature"),
            windspeed = scaled(MacsOptions.Kind.Wind, "wind_sensor")
                ?: fromWeather(MacsOptions.Kind.Wind, "wind_speed", "wind_speed_unit") ?: manual("number.macs_windspeed"),
            precipitation = scaled(MacsOptions.Kind.Precipitation, "precipitation_sensor") ?: manual("number.macs_precipitation"),
            battery = batteryValue,
            charging = chargingValue,
            conditions = conditions,
            brightness = states["number.macs_brightness"]?.numeric?.coerceIn(0.0, 100.0) ?: 100.0,
            animations = states["switch.macs_animations_enabled"]?.let { it.state == "on" } ?: cfg.b("animations_enabled", true),
        )
    }

    private fun post(msg: JsonObject) {
        webView?.evaluateJavascript("window.macsPost($msg)", null)
    }

    private fun num(v: Double?) = v?.let { JsonPrimitive(it) } ?: JsonNull

    private fun turnsJson(): JsonArray = JsonArray(voice.turns.value.take(macsConfig().i("max_turns", 2)).map { t ->
        buildJsonObject {
            put("heard", t.heard)
            put("reply", t.reply)
            put("error", t.error)
            put("ts", t.ts)
            put("runId", t.id)
        }
    })

    private fun sendInit() {
        val s = snapshot()
        val cfg = macsConfig()
        post(buildJsonObject {
            put("type", "macs:init")
            put("recipient", "frontend")
            put("config", buildJsonObject {
                put("assist_satellite_enabled", !cfg.s("assist_satellite").isNullOrBlank())
                put("assist_pipeline_entity", "native")
                put("max_turns", cfg.i("max_turns", 2))
                put("auto_brightness_enabled", cfg.b("auto_brightness_enabled"))
                put("auto_brightness_timeout_minutes", cfg.d("auto_brightness_timeout_minutes", 5.0))
                put("auto_brightness_min", cfg.d("auto_brightness_min", 0.0))
                put("auto_brightness_max", cfg.d("auto_brightness_max", 100.0))
                put("auto_brightness_pause_animations", cfg.b("auto_brightness_pause_animations", true))
                put("battery_state_sensor_enabled", s.charging != null)
                put("debug_mode", "None")
            })
            put("mood", s.mood)
            // Valeurs encore inconnues (Home Assistant pas encore connecté…) omises : un `null` fait afficher
            // à M.A.C.S. « Looks like there might be a problem with [...] ». Elles suivent dès qu'elles arrivent.
            put("sensors", buildJsonObject {
                s.temperature?.let { put("temperature", it) }
                s.windspeed?.let { put("windspeed", it) }
                s.precipitation?.let { put("precipitation", it) }
                s.battery?.let { put("battery_charge", it) }
                s.charging?.let { put("charging", it) }
                put("weatherConditions", JsonObject(s.conditions.mapValues { JsonPrimitive(it.value) }))
            })
            put("brightness", s.brightness)
            put("animations_enabled", s.animations)
        })
        post(buildJsonObject {
            put("type", "macs:init")
            put("recipient", "assist-bridge")
            put("config", buildJsonObject {
                put("assist_pipeline_entity", "native")
                put("max_turns", cfg.i("max_turns", 2))
            })
            put("turns", turnsJson())
        })
        last = s
        lastTurns = turnsJson()
        lastAutoBrightness = autoBrightnessKey()
    }

    private fun pushDeltas() {
        val s = snapshot()
        val p = last
        fun send(type: String, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = post(buildJsonObject {
            put("type", type)
            put("recipient", "frontend")
            block()
        })
        if (p == null || p.mood != s.mood) send("macs:mood") { put("mood", s.mood); put("reset_sleep", true) }
        if (p?.temperature != s.temperature && s.temperature != null) send("macs:temperature") { put("temperature", s.temperature) }
        if (p?.windspeed != s.windspeed && s.windspeed != null) send("macs:windspeed") { put("windspeed", s.windspeed) }
        if (p?.precipitation != s.precipitation && s.precipitation != null) send("macs:precipitation") { put("precipitation", s.precipitation) }
        if (p?.battery != s.battery && s.battery != null) send("macs:battery_charge") { put("battery_charge", s.battery) }
        if (p?.charging != s.charging && s.charging != null) send("macs:charging") { put("charging", s.charging) }
        if (p?.conditions != s.conditions) send("macs:weather_conditions") { s.conditions.forEach { (k, v) -> put(k, v) } }
        if (p?.brightness != s.brightness) send("macs:brightness") { put("brightness", s.brightness) }
        // Luminosité automatique active : M.A.C.S. gère lui-même la pause des animations.
        if (p?.animations != s.animations && !macsConfig().b("auto_brightness_enabled")) send("macs:animations_enabled") { put("enabled", s.animations) }
        // Options de luminosité automatique modifiées : nouvelle initialisation de la page.
        val ab = autoBrightnessKey()
        if (lastAutoBrightness != null && ab != lastAutoBrightness) { lastAutoBrightness = ab; sendInit(); return }
        lastAutoBrightness = ab
        last = s
        val turns = turnsJson()
        if (turns != lastTurns) {
            lastTurns = turns
            post(buildJsonObject { put("type", "macs:turns"); put("recipient", "all"); put("turns", turns) })
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onLoaded() = Unit

        @JavascriptInterface
        fun onMessage(json: String) {
            if (json.contains("\"macs:ready\"")) main.post {
                ready = true
                sendInit()
            }
        }
    }

    @Suppress("unused")
    private fun EntityState.isOn() = state == "on"
}
