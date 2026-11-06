package fr.pergens.hamacs

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.provider.Settings
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import fr.pergens.hamacs.config.o
import fr.pergens.hamacs.config.s
import androidx.compose.foundation.layout.padding
import fr.pergens.hamacs.ui.DashState
import fr.pergens.hamacs.ui.EditBanner
import fr.pergens.hamacs.ui.StatusOverlays
import fr.pergens.hamacs.ui.cards.alerts.AlertOverlayHost
import fr.pergens.hamacs.ui.LocalDashState
import fr.pergens.hamacs.ui.Overlays
import fr.pergens.hamacs.ui.ScreenHost
import fr.pergens.hamacs.ui.components.tapHold
import fr.pergens.hamacs.ui.settings.SettingsScreen
import fr.pergens.hamacs.ui.theme.Dash
import fr.pergens.hamacs.ui.theme.DashThemeProvider
import fr.pergens.hamacs.ui.theme.themeFromConfig
import fr.pergens.hamacs.ui.theme.withAccent
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) App.instance.voice.startListening()
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted.intValue++
        if (granted) updatePresence()
    }
    /** Kiosque : incrémenté par le bouton Retour (code demandé, puis menu du kiosque). */
    private val kioskRequest = mutableIntStateOf(0)

    /** Incrémenté à chaque réponse de permission caméra (rafraîchit l'écran des réglages). */
    private val cameraGranted = mutableIntStateOf(0)

    /** Résultat du dernier export/import (message, erreur ?) affiché dans les Réglages. */
    private val transferMessage = mutableStateOf<Pair<String, Boolean>?>(null)

    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        transferMessage.value = runCatching {
            contentResolver.openOutputStream(uri)!!.use { it.write(App.instance.config.exportAll().toByteArray()) }
            getString(R.string.export_done) to false
        }.getOrElse { getString(R.string.import_error, it.message ?: "") to true }
    }

    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        applyImport(runCatching { contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }.getOrNull())
    }

    /** Notification de Home Assistant affichée en bandeau. */
    private val haBanner = mutableStateOf<fr.pergens.hamacs.ha.MobileAppLink.Command.Banner?>(null)

    /** Mode nuit : écran noir affiché (au-dessus de tout, absorbe le premier contact). */
    private val nightBlackout = mutableStateOf(false)

    /** Sauvegarde envoyée par un téléphone (QR code) : fenêtre ouverte depuis les réglages ou l'assistant. */
    private val phoneTransfer = mutableStateOf(false)

    /**
     * Applique un fichier de sauvegarde (sélecteur de fichiers ou téléphone) ; retourne le message affiché (erreur ?).
     * Import réussi depuis l'assistant : la configuration est complète, l'assistant se ferme. Jeton à saisir
     * (sauvegarde d'un autre appareil) : l'assistant reste ouvert sur la connexion.
     */
    private fun applyImport(text: String?): Pair<String, Boolean> {
        // null = import réussi (importAll renvoie le message d'erreur éventuel).
        val error = if (text == null) getString(R.string.import_unreadable) else App.instance.config.importAll(text)
        val needsToken = error == null && App.instance.config.importNeedsToken
        val message = when {
            needsToken -> getString(R.string.import_token_needed) to true
            error == null -> getString(R.string.import_done) to false
            else -> getString(R.string.import_error, error) to true
        }
        transferMessage.value = message
        if (error == null && !needsToken) App.instance.config.finishSetup()
        return message
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(fr.pergens.hamacs.config.AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Paysage uniquement (dans les deux sens), même si le manifeste est ignoré par le système.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val app = App.instance
        app.voice.requestPermission = { micPermission.launch(Manifest.permission.RECORD_AUDIO) }
        enterImmersive()

        // Racine native : l'interface Compose, puis la couche M.A.C.S. au-dessus (elle ne capte aucun appui).
        val root = FrameLayout(this)
        val compose = ComposeView(this)
        val macsLayer = FrameLayout(this)
        root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(macsLayer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        enterImmersive()
        app.macs.attach(macsLayer)

        compose.setContent {
            // Mode nuit, écran noir : M.A.C.S. (couche native au-dessus) est masqué lui aussi.
            LaunchedEffect(nightBlackout.value) { macsLayer.animate().alpha(if (nightBlackout.value) 0f else 1f).setDuration(400).start() }
            val dashboard by app.config.dashboard.collectAsState()
            val settings by app.config.settings.collectAsState()
            val theme = remember(dashboard, settings.accentColor) { themeFromConfig(dashboard.o("theme")).withAccent(settings.accentColor) }
            val state = remember {
                DashState(app.ha, app.voice, { app.config.dashboard.value.o("popups") }, dashboard.s("start_screen") ?: "home")
            }

            LaunchedEffect(settings.keepScreenOn) {
                if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            LaunchedEffect(settings.presenceDetection, settings.presenceSensitivity) { updatePresence() }
            // Atténuation de l'écran : contact ou mouvement devant la caméra = activité.
            val lastMotion by app.presence.lastMotion.collectAsState()
            val lastActivity = maxOf(state.lastInteraction, if (settings.presenceDetection) lastMotion else 0L)
            // Home Assistant (notifications de l'intégration mobile_app) : écran, caméra, bandeau.
            LaunchedEffect(Unit) {
                app.link.commands.collect { c ->
                    state.lastInteraction = System.currentTimeMillis()
                    when (c) {
                        is fr.pergens.hamacs.ha.MobileAppLink.Command.Camera -> state.player = c.entity
                        is fr.pergens.hamacs.ha.MobileAppLink.Command.Banner -> haBanner.value = c
                        else -> Unit
                    }
                }
            }
            LaunchedEffect(nightBlackout.value) { app.link.screenOn.value = !nightBlackout.value }
            // Mode nuit : recalculé chaque minute (heures fixes ou soleil de sun.sun).
            var night by remember { mutableStateOf(false) }
            LaunchedEffect(settings) {
                while (true) {
                    night = fr.pergens.hamacs.ui.NightMode.isNight(settings, app.ha.states.value)
                    delay(30_000)
                }
            }
            LaunchedEffect(lastActivity, settings.dimSeconds, settings.dimLevel, night, settings.nightSeconds, settings.nightBlack, settings.nightLevel) {
                animateBrightness(null)
                nightBlackout.value = false
                if (night) {
                    // La nuit : écran noir (ou très atténué) après une inactivité plus courte.
                    delay((lastActivity + settings.nightSeconds * 1000L - System.currentTimeMillis()).coerceAtLeast(0))
                    if (settings.nightBlack) { nightBlackout.value = true; animateBrightness(0.01f) } else animateBrightness(settings.nightLevel)
                } else if (settings.dimSeconds > 0) {
                    delay((lastActivity + settings.dimSeconds * 1000L - System.currentTimeMillis()).coerceAtLeast(0))
                    animateBrightness(settings.dimLevel)
                }
            }
            LaunchedEffect(settings.voiceUrl) { app.voice.warmUp() }
            // Kiosque : appliqué au démarrage et à chaque changement du réglage.
            LaunchedEffect(settings.kioskEnabled) { fr.pergens.hamacs.kiosk.Kiosk.apply(this@MainActivity) }
            LaunchedEffect(kioskRequest.intValue) {
                if (kioskRequest.intValue > 0 && settings.kioskEnabled && state.pin == null && !state.kioskMenu) state.askKioskMenu()
            }

            // Taille du texte : seule l'échelle des polices change, les dimensions en dp restent fixes,
            // donc la mise en page et les alignements sont conservés.
            val baseDensity = LocalDensity.current
            val systemScale = resources.configuration.fontScale
            val density = remember(baseDensity, settings.textScale) {
                Density(baseDensity.density, if (settings.textScale > 0f) settings.textScale else systemScale)
            }

            CompositionLocalProvider(LocalDensity provides density) {
                DashThemeProvider(theme) {
                    CompositionLocalProvider(LocalDashState provides state) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Dash.colors.background)
                                // Toute interaction repousse le retour automatique à l'accueil.
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            awaitPointerEvent(PointerEventPass.Initial)
                                            state.lastInteraction = System.currentTimeMillis()
                                        }
                                    }
                                }
                        ) {
                            ScreenHost(dashboard)
                            // Bannière des alertes (AlertTicker) quand leur carte n'est pas à l'écran.
                            AlertOverlayHost()
                            // Zone cachée (coin haut-gauche) : appui long = réglages.
                            Box(Modifier.align(Alignment.TopStart).size(48.dp).tapHold(onTap = null, onHold = {
                                // Kiosque ou code maître : les réglages demandent le code.
                                state.askSettings()
                            }))
                            Overlays(dashboard)
                            AnimatedVisibility(
                                state.showSettings,
                                enter = slideInHorizontally(tween(300)) { it / 8 } + fadeIn(tween(300)),
                                exit = slideOutHorizontally(tween(250)) { it / 8 } + fadeOut(tween(250)),
                            ) {
                                SettingsScreen(
                                    onClose = { state.showSettings = false },
                                    cameraPermissionTick = cameraGranted.intValue,
                                    requestCamera = { cameraPermission.launch(Manifest.permission.CAMERA) },
                                    requestMic = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                                    transferMessage = transferMessage.value,
                                    onExport = {
                                        transferMessage.value = null
                                        exportFile.launch("ha-dashboard-${java.time.LocalDate.now()}.json")
                                    },
                                    onImport = {
                                        transferMessage.value = null
                                        importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                                    },
                                    onPhone = { transferMessage.value = null; phoneTransfer.value = true },
                                    onEditBlocks = {
                                        state.showSettings = false
                                        // L'écran affiché reste le même : on navigue ensuite si on le souhaite.
                                        state.editMode = true
                                    },
                                )
                            }
                            AnimatedVisibility(
                                state.editMode && !state.showSettings,
                                // En bas : les onglets du haut restent accessibles pendant l'édition.
                                Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                                enter = fadeIn(tween(250)), exit = fadeOut(tween(200)),
                            ) { EditBanner { state.editMode = false } }
                            if (!state.showSettings) StatusOverlays(state)
                            // Assistant de premier démarrage (ou relancé depuis les réglages), par-dessus tout.
                            val setupPending by app.config.setupPending.collectAsState()
                            AnimatedVisibility(setupPending, enter = fadeIn(tween(300)), exit = fadeOut(tween(300))) {
                                fr.pergens.hamacs.setup.SetupWizard(
                                    onImport = {
                                        transferMessage.value = null
                                        importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                                    },
                                    importError = transferMessage.value?.takeIf { it.second }?.first,
                                    onPhone = { transferMessage.value = null; phoneTransfer.value = true },
                                ) {
                                    app.config.finishSetup()
                                    // Retour direct au tableau de bord, même si l'assistant a été lancé depuis les réglages.
                                    state.showSettings = false
                                    state.editMode = false
                                }
                            }
                            // Mode nuit, écran noir : le premier contact rallume seulement (rien n'est actionné dessous).
                            if (nightBlackout.value) Box(
                                Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            awaitPointerEvent().changes.forEach { it.consume() }
                                            state.lastInteraction = System.currentTimeMillis()
                                            nightBlackout.value = false
                                        }
                                    }
                                }
                            )
                            // Sauvegarde d'une autre tablette : nouveaux codes à définir (codes chiffrés illisibles ici).
                            // Sauvegarde par le téléphone (QR code) : téléchargement ou envoi d'un fichier.
                            if (phoneTransfer.value) fr.pergens.hamacs.ui.PhoneTransferDialog(
                                onClose = { phoneTransfer.value = false },
                                export = { app.config.exportAll() },
                                onImport = { applyImport(it) },
                            )
                            fr.pergens.hamacs.ui.CodeReplaceDialog()
                            // « Supprimer les animations » d'Android activé : conseil au démarrage.
                            fr.pergens.hamacs.ui.AnimationsNotice(state)
                            // Sonnette, détecteurs : caméra en plein écran sur un événement (option du bloc caméra).
                            fr.pergens.hamacs.ui.CameraEventWatcher()
                            // Son envoyé au lecteur DLNA : pastille « en lecture » (pause, arrêt).
                            fr.pergens.hamacs.ui.DlnaNowPlaying()
                            // Notification de Home Assistant : bandeau en haut (refermé au toucher ou après 15 s).
                            haBanner.value?.let { b -> fr.pergens.hamacs.ui.HaBanner(b.title, b.message) { haBanner.value = null } }
                        }
                    }
                }
            }
        }
    }

    private var brightnessAnimator: ValueAnimator? = null
    private var brightnessTarget: Float? = null

    /**
     * Fait varier en douceur la luminosité de la fenêtre vers [target] (0–1), ou revient à la
     * luminosité du système si `null`.
     */
    private fun animateBrightness(target: Float?) {
        val current = window.attributes.screenBrightness
        if (target == null && current < 0f && brightnessTarget == null) return
        if (target != null && brightnessTarget == target) return
        val system = runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f)
        brightnessTarget = target
        brightnessAnimator?.cancel()
        brightnessAnimator = ValueAnimator.ofFloat(if (current < 0f) system else current, target ?: system).apply {
            duration = if (target == null) 400L else 1500L
            addUpdateListener { a ->
                window.attributes = window.attributes.apply { screenBrightness = a.animatedValue as Float }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Retour au réglage automatique du système une fois revenu à sa valeur.
                    if (brightnessTarget == null) window.attributes = window.attributes.apply { screenBrightness = -1f }
                }
            })
            start()
        }
    }

    /** Démarre ou arrête l'analyse caméra selon le réglage et la permission (aussi après une photo d'avatar). */
    fun updatePresence() {
        val app = App.instance
        val s = app.config.settings.value
        app.presence.sensitivity = s.presenceSensitivity
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        when {
            !s.presenceDetection -> app.presence.stop()
            granted -> app.presence.start(this)
            else -> cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        fr.pergens.hamacs.kiosk.Kiosk.onResume(this)
        // Test du journal et de la relance après plantage (versions de débogage uniquement) :
        // am start -n fr.pergens.hamacs/.MainActivity --ez debug_crash true
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("debug_crash", false) == true) {
            intent.removeExtra("debug_crash")
            window.decorView.post { throw IllegalStateException("Plantage de test (debug_crash)") }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        fr.pergens.hamacs.kiosk.Kiosk.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Écran d'accueil/kiosque : le bouton retour ne quitte pas l'application ; en mode kiosque, il
        // demande le code puis ouvre le menu du kiosque.
        kioskRequest.intValue++
    }
}
