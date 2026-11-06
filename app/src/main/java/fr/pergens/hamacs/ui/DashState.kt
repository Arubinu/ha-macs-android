package fr.pergens.hamacs.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import fr.pergens.hamacs.config.b
import fr.pergens.hamacs.config.o
import fr.pergens.hamacs.config.s
import fr.pergens.hamacs.ha.EntityState
import fr.pergens.hamacs.ha.HaClient
import fr.pergens.hamacs.voice.VoiceAssistant
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Demande de code PIN (restriction-card, alarme). */
data class PinRequest(val title: String?, val expected: String?, val onOk: (String) -> Unit)

/** Bloc réglable en mode édition. */
sealed interface EditTarget {
    val key: String
    /** Bouton ou carte pilotant une entité. */
    data class Entity(override val key: String) : EditTarget
    /** Caméra. */
    data class Camera(override val key: String) : EditTarget
    /** Graphique : hauteur propre ([key] = `graph:<type>:<entité>`), [defaultHeight] = valeur de dashboard.json. */
    data class Graph(override val key: String, val entity: String, val defaultHeight: Int) : EditTarget
    /** Membre de la carte « members » ([key] = `member:<id>`) ; [selector] porte le code d'accès. */
    data class Member(override val key: String, val selector: String) : EditTarget
    /** Page web embarquée ([key] = `webview:<adresse sans paramètres>`) : Windy (mode, zoom, position) ou adresse. */
    data class Web(override val key: String) : EditTarget
    /** Carrousel ([key] = `swipe:…`) : liste de ses cartes (ajout, retrait, ordre) et options de défilement. */
    data class Swipe(override val key: String) : EditTarget
    /** Pièce n° [index] de la grille [key] (`rooms:…`) : nom, icône, entité, capteurs et appareils. */
    data class Room(override val key: String, val index: Int) : EditTarget
    /** Appareil n° [index] de la pièce n° [room] de la grille [key] : entité, icônes, couleur, action, code. */
    data class Device(override val key: String, val room: Int, val index: Int) : EditTarget
    /** Onglets de caméras ([key] = `tabs:<id>`) : onglets (nom, icône, ordre) et caméras de chacun. */
    data class Tabs(override val key: String) : EditTarget
    /** Onglet n° [index] des onglets de caméras [key] : icône, nom, onglet par défaut et ses caméras. */
    data class Tab(override val key: String, val index: Int) : EditTarget
    /** Zone des détections ([key] = `detections:…`) : stockage, durée, fichiers et caméras. */
    data class Detections(override val key: String) : EditTarget
    /** Caméra n° [index] de la zone des détections [key] : nom, reconnaissance des fichiers, dossier, entité. */
    data class DetectionCamera(override val key: String, val index: Int) : EditTarget
    /** Onglets d'un sélecteur de pages ([key] = `pages:<id>`) : nom et icône de chaque onglet. */
    data class Pages(override val key: String) : EditTarget
    /** M.A.C.S. ([key] = `macs`) : capteurs, satellite Assist, batterie, conversation, luminosité, animations. */
    data class Macs(override val key: String = "macs") : EditTarget
    /** Bloc dont seules les entités se règlent (météo…). */
    data class Block(override val key: String) : EditTarget
    /** Carte dont toute la configuration se modifie dans un éditeur dédié (AlertTicker…). */
    data class Card(override val key: String) : EditTarget
}

/**
 * État de navigation et dispatch des actions. Reproduit le fonctionnement « #hash » des
 * state-switch de Lovelace, mais chaque switcher garde sa propre page.
 */
class DashState(
    val ha: HaClient,
    val voice: VoiceAssistant,
    val popups: () -> JsonObject?,
    private val initialScreen: String,
) {
    private var currentScreen by mutableStateOf(initialScreen)
    /** Écran affiché. Retour à l'écran d'accueil : chaque sélecteur d'onglets ayant un favori y revient. */
    var screen: String
        get() = currentScreen
        set(value) {
            if (value != currentScreen) topMenu = null
            if (value != currentScreen && value == initialScreen) {
                favorites.forEach { (id, page) -> pages[id] = page }
                onHome.values.forEach { it() }
            }
            currentScreen = value
        }
    /** Barre d'onglets la plus haute de l'écran (fenêtre), pour placer la pastille « en lecture » à côté. */
    var topMenu by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    /** Écran d'accueil (au démarrage et après inactivité). */
    val homeScreen get() = initialScreen
    /** Page courante de chaque switcher, par identifiant. */
    val pages = mutableStateMapOf<String, String>()
    /** Onglet favori de chaque switcher (`"favorite"` de sa config), réaffiché au retour sur l'accueil. */
    val favorites = HashMap<String, String>()
    /** Actions au retour sur l'écran d'accueil (ex. membre favori), par clé. */
    val onHome = HashMap<String, () -> Unit>()
    /** Le membre favori a-t-il déjà été appliqué depuis le démarrage de l'app ? */
    var memberFavoriteApplied = false
    /** Pages connues par switcher, pour router un "#hash" vers le bon switcher. */
    val switcherPages = HashMap<String, Set<String>>()
    var popup by mutableStateOf<String?>(null)
    var moreInfo by mutableStateOf<String?>(null)
    var pin by mutableStateOf<PinRequest?>(null)
    var showSettings by mutableStateOf(false)
    /** Menu du kiosque (ouvert après le code d'accès). */
    var kioskMenu by mutableStateOf(false)
    /** Vidéo plein écran : URL directe (mp4/HLS) ou caméra à streamer ("camera.xxx"). */
    var player by mutableStateOf<String?>(null)
    /** Flux de repli du plein écran (celui de la carte) si la tablette ne sait pas décoder [player] (4K H.265…). */
    var playerFallback: String? = null

    /** Ouvre le plein écran sur [target], avec [fallback] si son flux n'est pas lisible par la tablette. */
    fun openPlayer(target: String, fallback: String?) {
        playerFallback = fallback?.takeIf { it != target }
        player = target
    }
    /** Horodatage de la dernière interaction (retour automatique à l'accueil). */
    var lastInteraction by mutableStateOf(System.currentTimeMillis())
    /** Mode édition : toucher un bloc ouvre ses réglages au lieu de l'actionner. */
    var editMode by mutableStateOf(false)
    /** Bloc en cours de réglage (mode édition). */
    var editTarget by mutableStateOf<EditTarget?>(null)
    /** Fenêtres à rouvrir, de la plus récente à la plus ancienne, quand on ferme la fenêtre en cours. */
    val editStack = androidx.compose.runtime.mutableStateListOf<EditTarget>()

    /** Ouvre [to] depuis la fenêtre [from], qui se rouvrira quand on fermera [to]. */
    fun openEditChild(from: EditTarget, to: EditTarget) {
        editStack.add(from)
        editTarget = to
    }

    /** En mode édition, ouvre les réglages du bloc et retourne true (l'action normale est ignorée). */
    fun editInstead(target: EditTarget?): Boolean {
        if (!editMode) return false
        target?.let { editTarget = it }
        return true
    }

    /** Le bloc lié à [entity] demande-t-il le code ? Réglage du mode édition, sinon la config. */
    fun isProtected(entity: String?, configDefault: Boolean): Boolean =
        entity?.let { fr.pergens.hamacs.App.instance.config.blocks.value[it]?.b("protected") } ?: configDefault

    /**
     * Interaction directe d'une carte avec [entity] (télécommande, lecteur, alarme, membres…) :
     * en mode édition, ouvre les réglages du bloc ; sinon demande le code d'accès si le bloc est protégé.
     */
    fun guard(entity: String?, configDefault: Boolean = false, code: String? = null, title: String? = null, run: () -> Unit) {
        lastInteraction = System.currentTimeMillis()
        if (editInstead(entity?.let { EditTarget.Entity(it) })) return
        if (isProtected(entity, configDefault)) {
            // [code] : code propre (membre) ; sinon le code d'accès.
            pin = PinRequest(title, code ?: fr.pergens.hamacs.App.instance.config.accessCode()) { run() }
        } else run()
    }

    /** Kiosque : code maître (sinon code d'accès), puis menu (réglages, quitter, redémarrer…). */
    fun askKioskMenu() {
        lastInteraction = System.currentTimeMillis()
        val app = fr.pergens.hamacs.App.instance
        val code = app.config.masterCode().ifEmpty { app.config.accessCode() }
        pin = PinRequest(app.getString(fr.pergens.hamacs.R.string.kiosk_pin_title), code) { kioskMenu = true }
    }

    /** Appui long sur le coin caché : menu du kiosque, sinon réglages (après le code maître s'il y en a un). */
    fun askSettings() {
        val app = fr.pergens.hamacs.App.instance
        val master = app.config.masterCode()
        when {
            app.config.settings.value.kioskEnabled -> askKioskMenu()
            master.isNotEmpty() -> {
                lastInteraction = System.currentTimeMillis()
                pin = PinRequest(app.getString(fr.pergens.hamacs.R.string.master_code), master) { showSettings = true }
            }
            else -> showSettings = true
        }
    }

    fun registerSwitcher(id: String, pageIds: Set<String>) {
        switcherPages[id] = pageIds
    }

    fun navigate(path: String) {
        val p = path.trim()
        when {
            p.startsWith("#") -> {
                val hash = p.removePrefix("#")
                if (popups()?.containsKey(hash) == true) {
                    popup = hash
                    return
                }
                switcherPages.forEach { (sw, ids) -> if (hash in ids) pages[sw] = hash }
            }
            p.contains('#') -> {
                // ex. "/dashboard-tablette/dashboard#appletv"
                p.substringBefore('#').trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }?.let { screen = it }
                navigate("#" + p.substringAfter('#'))
            }
            else -> p.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }?.let { screen = it }
        }
    }

    /**
     * Exécute une action au format Lovelace (tap_action / hold_action).
     * [entity] est l'entité de la carte, utilisée par défaut.
     */
    fun perform(action: JsonObject?, entity: String?, defaultAction: String = "toggle", protectedByConfig: Boolean = false) {
        lastInteraction = System.currentTimeMillis()
        val kind = action?.s("action") ?: defaultAction
        // En mode édition, la navigation entre écrans reste possible pour atteindre tous les blocs.
        if (kind != "navigate" && kind != "screen" && editInstead(entity?.let { EditTarget.Entity(it) })) return
        val run = {
            when (kind) {
                "none" -> Unit
                "toggle" -> entity?.let { ha.toggle(it) }
                "more-info" -> moreInfo = action?.s("entity") ?: entity
                "navigate" -> action?.s("navigation_path")?.let { navigate(it) }
                "screen" -> action?.s("screen")?.let { screen = it }
                "popup" -> popup = action?.s("popup")
                "voice" -> voice.toggleListening()
                "call-service", "perform-action" -> {
                    val svc = action?.s("perform_action") ?: action?.s("service")
                    if (svc != null && svc.contains('.')) {
                        val target = action?.o("target")?.takeIf { it.isNotEmpty() }
                            ?: entity?.let { e -> buildJsonObject { put("entity_id", JsonPrimitive(e)) } }
                        ha.fireService(svc.substringBefore('.'), svc.substringAfter('.'), action?.o("data"), target)
                    }
                }
                "url" -> Unit
                else -> entity?.let { ha.toggle(it) }
            }
        }
        // Protection : `"pin"` dans l'action ou dans le bouton (config), modifiable bloc par bloc en mode
        // édition. Le code est unique et se règle dans les Réglages.
        val needsCode = isProtected(entity, configDefault = protectedByConfig || action?.get("pin") != null)
        if (needsCode && kind != "none") {
            this.pin = PinRequest(action?.s("pin_title"), fr.pergens.hamacs.App.instance.config.accessCode()) { run() }
        } else run()
    }

    private fun JsonObject.isNotEmpty() = this.entries.isNotEmpty()
}

val LocalDashState = staticCompositionLocalOf<DashState> { error("DashState non fourni") }

/** Observe l'état d'une entité ; ne recompose que lorsque CETTE entité change. */
@Composable
fun rememberEntity(entityId: String?): State<EntityState?> {
    val ha = LocalDashState.current.ha
    val flow = remember(entityId) { ha.states.map { if (entityId == null) null else it[entityId] } }
    return flow.collectAsState(initial = entityId?.let { ha.states.value[it] })
}

@Composable
fun entity(entityId: String?): EntityState? {
    val st by rememberEntity(entityId)
    return st
}

internal fun JsonElement?.asObj(): JsonObject? = this as? JsonObject
