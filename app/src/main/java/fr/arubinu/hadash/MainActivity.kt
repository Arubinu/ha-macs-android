package fr.arubinu.hadash

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
import fr.arubinu.hadash.config.o
import fr.arubinu.hadash.config.s
import androidx.compose.foundation.layout.padding
import fr.arubinu.hadash.ui.DashState
import fr.arubinu.hadash.ui.EditBanner
import fr.arubinu.hadash.ui.StatusOverlays
import fr.arubinu.hadash.ui.cards.alerts.AlertOverlayHost
import fr.arubinu.hadash.ui.LocalDashState
import fr.arubinu.hadash.ui.Overlays
import fr.arubinu.hadash.ui.ScreenHost
import fr.arubinu.hadash.ui.components.tapHold
import fr.arubinu.hadash.ui.settings.SettingsScreen
import fr.arubinu.hadash.ui.theme.Dash
import fr.arubinu.hadash.ui.theme.DashThemeProvider
import fr.arubinu.hadash.ui.theme.themeFromConfig
import fr.arubinu.hadash.ui.theme.withAccent
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
        val text = runCatching { contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }.getOrNull()
        // null = import réussi (importAll renvoie le message d'erreur éventuel).
        val error = if (text == null) getString(R.string.import_unreadable) else App.instance.config.importAll(text)
        transferMessage.value = if (error == null) getString(R.string.import_done) to false
        else getString(R.string.import_error, error) to true
        // Import réussi depuis l'assistant : la configuration est complète, l'assistant se ferme.
        if (error == null) App.instance.config.finishSetup()
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(fr.arubinu.hadash.config.AppLocale.wrap(newBase))
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
            LaunchedEffect(lastActivity, settings.dimSeconds, settings.dimLevel) {
                animateBrightness(null)
                if (settings.dimSeconds > 0) {
                    delay((lastActivity + settings.dimSeconds * 1000L - System.currentTimeMillis()).coerceAtLeast(0))
                    animateBrightness(settings.dimLevel)
                }
            }
            LaunchedEffect(settings.voiceUrl) { app.voice.warmUp() }
            // Kiosque : appliqué au démarrage et à chaque changement du réglage.
            LaunchedEffect(settings.kioskEnabled) { fr.arubinu.hadash.kiosk.Kiosk.apply(this@MainActivity) }
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
                                // Kiosque : les réglages (qui permettent de le désactiver) demandent le code.
                                if (app.config.settings.value.kioskEnabled) state.askKioskMenu() else state.showSettings = true
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
                                fr.arubinu.hadash.setup.SetupWizard(
                                    onImport = {
                                        transferMessage.value = null
                                        importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                                    },
                                    importError = transferMessage.value?.takeIf { it.second }?.first,
                                ) {
                                    app.config.finishSetup()
                                    // Retour direct au tableau de bord, même si l'assistant a été lancé depuis les réglages.
                                    state.showSettings = false
                                    state.editMode = false
                                }
                            }
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
        fr.arubinu.hadash.kiosk.Kiosk.onResume(this)
    }

    override fun onStop() {
        super.onStop()
        fr.arubinu.hadash.kiosk.Kiosk.onStop()
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
