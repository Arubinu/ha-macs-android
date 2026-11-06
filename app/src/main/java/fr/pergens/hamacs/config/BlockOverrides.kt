package fr.pergens.hamacs.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Surcharges des blocs réglées en mode édition (préférence `block_overrides`).
 *
 * Chaque objet de configuration qui désigne une entité est identifié par une clé stable : son entité
 * d'origine (`entity` de dashboard.json), ou `member:<id>` pour un membre. Le champ `set` d'une surcharge
 * remplace des champs de cet objet (entité, capteurs, listes d'entités…) ; les autres champs (`protected`,
 * `always_live`, `height`, `config`…) sont les réglages du bloc.
 */
object BlockOverrides {
    private val ENTITY_ID = Regex("^[a-z_]+\\.[a-z0-9_]+$")

    fun isEntityId(v: String?): Boolean = v != null && ENTITY_ID.matches(v)

    /** Clé d'un objet de configuration, ou null s'il ne désigne pas d'entité modifiable. */
    fun keyOf(o: JsonObject): String? =
        o.s("_key")
            ?: carouselKey(o)
            ?: roomGridKey(o)
            ?: cameraTabsKey(o)
            ?: pagesKey(o)
            ?: membersKey(o)
            ?: multiGraphKey(o)
            ?: detectionsKey(o)
            ?: o.s("entity")?.takeIf { isEntityId(it) }
            ?: o.s("id")?.takeIf { o["person"] != null }?.let { "member:$it" }
            ?: o.s("url")?.takeIf { o.s("type") == "webview" || o.s("type") == "windy" }?.let { "webview:${it.substringBefore('?')}" }

    /**
     * Clé propre à un graphique multi-courbes (`mini_graph` avec `entities`) : `mgc:<1re entité d'origine>`.
     * Ses réglages (titre, liste des courbes) ne concernent que cette carte, même si une entité est partagée.
     */
    fun multiGraphKey(o: JsonObject): String? {
        if (o.s("type") != "mini_graph") return null
        val first = (o["entities"] as? JsonArray)?.firstOrNull() ?: return null
        val id = (first as? JsonObject)?.s("entity") ?: (first as? JsonPrimitive)?.content ?: return null
        return "mgc:$id"
    }

    /**
     * Clé d'un carrousel (`swipe`) : `swipe:<clé de sa 1re carte d'origine>`. Sa liste de cartes et ses options
     * de défilement sont réglées à part ; chaque carte garde ses propres réglages.
     */
    fun carouselKey(o: JsonObject): String? {
        if (o.s("type") != "swipe") return null
        val first = (o["cards"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        return keyOf(first)?.let { "swipe:$it" }
    }

    /**
     * Clé d'une grille de pièces (`room_grid`) : `rooms:<entité ou nom de sa 1re pièce d'origine>`. Sa liste de
     * pièces (ajout, retrait, ordre, contenu de chaque pièce) est réglée à part.
     */
    fun roomGridKey(o: JsonObject): String? {
        if (o.s("type") != "room_grid") return null
        val first = (o["rooms"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        return (first.s("entity") ?: first.s("name"))?.let { "rooms:$it" }
    }

    /**
     * Clé d'onglets de caméras (`switcher` dont toutes les pages ne contiennent que des caméras) : `tabs:<id>`.
     * Ses onglets (ajout, retrait, ordre, nom, icône, caméras de chacun) sont réglés à part.
     */
    fun cameraTabsKey(o: JsonObject): String? {
        if (o.s("type") != "switcher") return null
        val id = o.s("id") ?: return null
        val cards = (o["pages"] as? JsonArray)?.flatMap { ((it as? JsonObject)?.get("cards") as? JsonArray).orEmpty() } ?: return null
        if (cards.isEmpty() || cards.any { (it as? JsonObject)?.s("type") != "camera" }) return null
        return "tabs:$id"
    }

    /** Clé de la zone des détections (`camera_detections`) : `detections:<id>` (`main` sans identifiant). */
    fun detectionsKey(o: JsonObject): String? =
        if (o.s("type") == "camera_detections") "detections:" + (o.s("id") ?: "main") else null

    /**
     * Clé d'un sélecteur de pages qui n'est pas un onglet de caméras : `pages:<id>`. Ses pages (nom, icône, pages
     * masquées) sont réglées à part ; leur contenu garde ses propres réglages.
     */
    fun pagesKey(o: JsonObject): String? {
        if (o.s("type") != "switcher") return null
        val id = o.s("id") ?: return null
        return if (cameraTabsKey(o) == null) "pages:$id" else null
    }

    /** Clé de la carte des membres : `members:main`. Sa liste de membres est réglée à part (assistant). */
    fun membersKey(o: JsonObject): String? = if (o.s("type") == "members") MEMBERS_KEY else null
    const val MEMBERS_KEY = "members:main"

    /** Surcharges de structure (listes de cartes ou de pièces), appliquées aussi à la base des éditeurs. */
    fun isStructural(key: String) = key.startsWith("swipe:") || key.startsWith("rooms:") || key.startsWith("tabs:") ||
        key.startsWith("pages:") || key.startsWith("members:")

    /**
     * dashboard.json avec les champs `set` appliqués à chaque objet concerné. [structuralOnly] : seulement la
     * structure (cartes d'un carrousel), base des éditeurs de chaque carte.
     */
    fun apply(root: JsonObject, blocks: Map<String, JsonObject>, structuralOnly: Boolean = false): JsonObject {
        if (blocks.values.none { it["set"] is JsonObject }) return root
        fun walk(e: JsonElement): JsonElement = when (e) {
            // L'AlertTicker a son propre éditeur (configuration complète) : ses alertes ne sont pas touchées.
            is JsonObject if e.s("type") in setOf("alert_ticker", "ticker") -> e
            is JsonObject -> {
                val key = keyOf(e)
                val set = key?.takeIf { !structuralOnly || isStructural(it) }?.let { blocks[it]?.get("set") as? JsonObject }
                // Surcharge appliquée d'abord, puis aux objets qu'elle contient (cartes ajoutées à un carrousel…).
                // `_key` garde la clé d'origine (utile quand le champ qui la définit, ex. `url`, est remplacé).
                val base = if (set.isNullOrEmpty()) e else JsonObject(e + set + ("_key" to JsonPrimitive(key)))
                JsonObject(base.mapValues { walk(it.value) })
            }
            is JsonArray -> JsonArray(e.map { walk(it) })
            else -> e
        }
        return walk(root) as JsonObject
    }

    /** Entité d'origine → entité choisie, pour les blocs dont l'entité a été remplacée. */
    private fun renames(blocks: Map<String, JsonObject>): Map<String, String> =
        blocks.mapNotNull { (k, v) ->
            ((v["set"] as? JsonObject)?.get("entity") as? JsonPrimitive)?.content?.takeIf { it != k && isEntityId(it) }?.let { k to it }
        }.toMap()

    /**
     * Surcharges accessibles aussi par la nouvelle entité : `light.a` → `light.b` rend les réglages de
     * `light.a` (et `graph:<type>:light.a`) disponibles sous `light.b` (et `graph:<type>:light.b`).
     */
    fun aliases(blocks: Map<String, JsonObject>): Map<String, JsonObject> {
        val ren = renames(blocks)
        if (ren.isEmpty()) return blocks
        val out = blocks.toMutableMap()
        blocks.forEach { (k, v) ->
            ren.forEach { (from, to) ->
                val alias = when {
                    k == from -> to
                    k.endsWith(":$from") -> k.removeSuffix(from) + to
                    else -> null
                }
                if (alias != null && alias !in blocks) out[alias] = v
            }
        }
        return out
    }

    /** Clé d'enregistrement correspondant à [key] (qui peut désigner la nouvelle entité d'un bloc). */
    fun resolve(blocks: Map<String, JsonObject>, key: String): String {
        if (key in blocks) return key
        renames(blocks).forEach { (from, to) ->
            if (key == to) return from
            if (key.endsWith(":$to")) return key.removeSuffix(to) + from
        }
        return key
    }

    /** Carte de type [type] dont la liste `entities` contient l'entité [entity] (mini-graph multi-courbes…). */
    fun findWithEntity(root: JsonElement, entity: String, type: String): JsonObject? = when (root) {
        is JsonObject -> root.takeIf { o ->
            o.s("type") == type && (o["entities"] as? JsonArray)?.any { (it as? JsonObject)?.s("entity") == entity || (it as? JsonPrimitive)?.content == entity } == true
        } ?: root.values.firstNotNullOfOrNull { findWithEntity(it, entity, type) }
        is JsonArray -> root.firstNotNullOfOrNull { findWithEntity(it, entity, type) }
        else -> null
    }

    /** Premier objet de [root] dont la clé est [key] (et du type [type] si précisé). */
    fun find(root: JsonElement, key: String, type: String? = null): JsonObject? = when (root) {
        is JsonObject -> root.takeIf { keyOf(it) == key && (type == null || it.s("type") == type) }
            ?: root.values.firstNotNullOfOrNull { find(it, key, type) }
        is JsonArray -> root.firstNotNullOfOrNull { find(it, key, type) }
        else -> null
    }
}
