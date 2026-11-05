package fr.arubinu.hadash.macs

import fr.arubinu.hadash.ha.EntityState

/**
 * Options de M.A.C.S., reprises de l'éditeur de la carte d'origine : capteurs (température, vent, précipitations,
 * batterie, charge, conditions météo) avec unité et plage, satellite Assist, conversation, luminosité automatique
 * et animations. Enregistrées dans les surcharges de blocs sous la clé [KEY] (champ `config`).
 */
object MacsOptions {
    const val KEY = "macs"

    enum class Kind(val unitKey: String, val minKey: String, val maxKey: String, val units: List<String>, val fallback: String) {
        Temperature("temperature_unit", "temperature_min", "temperature_max", listOf("c", "f"), "c"),
        Wind("wind_unit", "wind_min", "wind_max", listOf("mph", "kph", "mps", "knots"), "mph"),
        Precipitation("precipitation_unit", "precipitation_min", "precipitation_max", listOf("%", "mm", "in"), "mm"),
        Battery("battery_unit", "battery_min", "battery_max", listOf("%", "v"), "%"),
    }

    private val ALIASES = mapOf(
        "c" to listOf("c", "°c", "celsius", "degc"),
        "f" to listOf("f", "°f", "fahrenheit", "degf"),
        "mph" to listOf("mph", "mi/h", "m/h"),
        "kph" to listOf("kph", "km/h", "kmh"),
        "mps" to listOf("mps", "m/s"),
        "knots" to listOf("knots", "knot", "kn", "kt", "kts"),
        "%" to listOf("%", "percent"),
        "mm" to listOf("mm", "mm/h"),
        "in" to listOf("in", "in/h", "inch", "inches"),
        "v" to listOf("v", "volt", "volts"),
    )

    private fun match(kind: Kind, token: String?): String? {
        val t = token?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "auto" } ?: return null
        return kind.units.firstOrNull { u -> t == u || ALIASES[u].orEmpty().contains(t) }
    }

    /** Unité retenue : celle choisie, sinon celle du capteur, sinon l'unité par défaut de la carte. */
    fun unit(kind: Kind, configured: String?, sensorUnit: String?): String =
        match(kind, configured) ?: match(kind, sensorUnit) ?: kind.fallback

    /** Plage par défaut de la carte d'origine (5–30 °C, 10–50 mph, 0–10 mm…), convertie dans [unit]. */
    fun defaultRange(kind: Kind, unit: String): Pair<Double, Double> = when (kind) {
        Kind.Temperature -> if (unit == "f") 41.0 to 86.0 else 5.0 to 30.0
        Kind.Wind -> when (unit) {
            "kph" -> 16.09 to 80.47
            "mps" -> 4.47 to 22.35
            "knots" -> 8.69 to 43.45
            else -> 10.0 to 50.0
        }
        Kind.Precipitation -> when (unit) {
            "%" -> 0.0 to 100.0
            "in" -> 0.0 to 0.39
            else -> 0.0 to 10.0
        }
        Kind.Battery -> 0.0 to 100.0
    }

    /** Capteur de charge : attributs usuels (`charging`, `is_charging`…), sinon son état. */
    fun charging(st: EntityState): Boolean? {
        fun parse(v: String?): Boolean? {
            val raw = v?.trim()?.lowercase()?.replace(Regex("[_-]+"), " ") ?: return null
            if (raw.isEmpty() || raw == "unknown" || raw == "unavailable") return null
            return raw in setOf("charging", "on", "true", "plugged", "1")
        }
        listOf("charging", "is_charging", "charge_state", "battery_charging", "plugged", "on", "powered", "ac_power")
            .firstNotNullOfOrNull { parse(st.attrString(it)) }?.let { return it }
        return parse(st.state)
    }
}
