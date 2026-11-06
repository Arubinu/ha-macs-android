package fr.pergens.hamacs.ha

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.Locale

data class EntityState(
    val entityId: String,
    val state: String,
    val attributes: JsonObject,
    /** Epoch millis. */
    val lastChanged: Long,
    val lastUpdated: Long,
) {
    val domain: String get() = entityId.substringBefore('.')
    val name: String get() = attrString("friendly_name") ?: entityId.substringAfter('.')
    val isUnavailable: Boolean get() = state == "unavailable" || state == "unknown"
    val numeric: Double? get() = state.toDoubleOrNull()
    val unit: String? get() = attrString("unit_of_measurement")

    /** Est considéré « actif » comme dans le frontend HA (lumière allumée, porte ouverte, lecture…). */
    val isActive: Boolean
        get() = when (domain) {
            "media_player" -> state !in setOf("off", "idle", "standby", "unavailable", "unknown")
            "cover" -> state == "open" || state == "opening"
            "lock" -> state == "unlocked"
            "alarm_control_panel" -> state != "disarmed"
            "person", "device_tracker" -> state == "home"
            "climate" -> state != "off"
            else -> state == "on" || state == "home" || state == "open" || state == "playing"
        }

    fun attr(key: String): JsonElement? = attributes[key]
    fun attrString(key: String): String? = (attributes[key] as? JsonPrimitive)?.contentOrNull
    fun attrDouble(key: String): Double? = (attributes[key] as? JsonPrimitive)?.doubleOrNull
    fun attrBool(key: String): Boolean? = (attributes[key] as? JsonPrimitive)?.booleanOrNull
    fun attrList(key: String): List<String> =
        (attributes[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
}

/** Formats « à la française » utilisés dans le dashboard (22,0°C, 2,109 EUR…). */
object Fmt {
    private val fr = Locale.FRANCE

    fun number(value: Double?, decimals: Int): String {
        if (value == null) return "—"
        return String.format(fr, "%.${decimals}f", value)
    }

    /** Équivalent du `parseFloat(x).toFixed(n).replace('.', ',')` des templates button-card. */
    fun state(e: EntityState?, decimals: Int? = null, suffix: String? = null): String {
        if (e == null) return "—"
        if (e.isUnavailable) return "—"
        val n = e.numeric
        val body = if (n != null && decimals != null) number(n, decimals) else e.state
        return body + (suffix ?: e.unit?.let { u -> if (u == "%" || u.startsWith("°")) u else " $u" } ?: "")
    }
}
