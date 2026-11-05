package fr.arubinu.hadash.ha

import fr.arubinu.hadash.App
import fr.arubinu.hadash.R
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Entités calculées par l'app à partir des entités de Home Assistant, pour ne pas avoir à créer de capteurs
 * « template » dans HA. Elles apparaissent comme les autres (AlertTicker, listes d'entités…), avec le préfixe
 * `sensor.hadash_`, mais n'ont pas d'historique.
 *
 * - `sensor.hadash_weather_alert_yellow|orange|red` : phénomènes en vigilance jaune / orange / rouge du capteur
 *   d'alertes Météo-France (`sensor.<département>_weather_alert`). État = nombre de phénomènes ; attributs
 *   `names` (liste lisible) et `array`.
 */
object VirtualEntities {
    const val PREFIX = "sensor.hadash_"

    private enum class Level(val id: String, val value: String, val title: Int, val icon: String) {
        Yellow("yellow", "Jaune", R.string.virtual_weather_alert_yellow, "mdi:alert"),
        Orange("orange", "Orange", R.string.virtual_weather_alert_orange, "mdi:alert"),
        Red("red", "Rouge", R.string.virtual_weather_alert_red, "mdi:alert-octagon"),
    }
    private val LEVEL_VALUES = setOf("Vert", "Jaune", "Orange", "Rouge")

    /** Dernière valeur calculée : `lastChanged` ne bouge que si l'état ou les phénomènes changent. */
    private val previous = HashMap<String, EntityState>()

    fun isVirtual(entityId: String) = entityId.startsWith(PREFIX)

    /** États de HA complétés des entités calculées. */
    fun augment(states: Map<String, EntityState>): Map<String, EntityState> {
        val out = HashMap(states)
        weatherAlerts(states).forEach { out[it.entityId] = it }
        return out
    }

    private fun weatherAlerts(states: Map<String, EntityState>): List<EntityState> {
        // Capteur de vigilance Météo-France : phénomène → couleur (Vert, Jaune, Orange, Rouge).
        val source = states.values.firstOrNull { e ->
            e.domain == "sensor" && e.entityId.endsWith("_weather_alert") &&
                e.attributes.values.count { (it as? JsonPrimitive)?.content in LEVEL_VALUES } > 0
        } ?: return emptyList()
        val ctx = App.instance
        return Level.entries.map { level ->
            val names = source.attributes.entries
                .filter { (k, v) -> k != "friendly_name" && (v as? JsonPrimitive)?.content == level.value }
                .map { it.key }.sorted()
            val id = "${PREFIX}weather_alert_${level.id}"
            val state = names.size.toString()
            val attrs = JsonObject(mapOf(
                "names" to JsonPrimitive(names.joinToString(", ")),
                "array" to JsonArray(names.map { JsonPrimitive(it) }),
                "source" to JsonPrimitive(source.entityId),
                "icon" to JsonPrimitive(level.icon),
                "friendly_name" to JsonPrimitive(ctx.getString(level.title)),
            ))
            val prev = previous[id]
            val now = System.currentTimeMillis()
            val changed = prev == null || prev.state != state || prev.attributes["names"] != attrs["names"]
            EntityState(
                entityId = id, state = state, attributes = attrs,
                lastChanged = if (changed) (if (prev == null) source.lastChanged else now) else prev!!.lastChanged,
                lastUpdated = if (changed) now else prev!!.lastUpdated,
            ).also { previous[id] = it }
        }
    }
}
