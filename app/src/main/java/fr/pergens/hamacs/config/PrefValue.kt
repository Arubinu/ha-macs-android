package fr.pergens.hamacs.config

import kotlinx.serialization.json.JsonPrimitive

/**
 * Type d'un réglage importé (hors jeton et codes) : celui que l'app relit avec `getFloat` / `getInt`, sinon d'après la
 * valeur JSON. Un nombre n'est jamais écrit en texte : relu avec `getInt`, l'app planterait au démarrage.
 */
internal sealed interface PrefValue {
    data class F(val v: Float) : PrefValue
    data class I(val v: Int) : PrefValue
    data class L(val v: Long) : PrefValue
    data class B(val v: Boolean) : PrefValue
    data class S(val v: String) : PrefValue

    companion object {
        /** Réglages dont le type ne se déduit pas de la valeur (1 peut être un décimal). */
        val TYPES = mapOf(
            "text_scale" to "f", "dim_level" to "f", "idle_seconds" to "i", "presence_sensitivity" to "i", "dim_seconds" to "i",
            "night_seconds" to "i", "night_level" to "f",
        )

        fun of(key: String, p: JsonPrimitive): PrefValue {
            val c = p.content
            return when {
                TYPES[key] == "f" -> F(c.toFloat())
                TYPES[key] == "i" -> I(c.toDouble().toInt())
                !p.isString && (c == "true" || c == "false") -> B(c.toBoolean())
                !p.isString && c.toIntOrNull() != null -> I(c.toInt())
                !p.isString && c.toLongOrNull() != null -> L(c.toLong())
                !p.isString && c.toFloatOrNull() != null -> F(c.toFloat())
                else -> S(c)
            }
        }
    }
}
