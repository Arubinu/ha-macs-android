package fr.pergens.hamacs.ui

import fr.pergens.hamacs.config.Settings
import fr.pergens.hamacs.ha.EntityState
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Mode nuit : la nuit va du début au lendemain (heures fixes, ou coucher / lever du soleil lus dans `sun.sun`,
 * sans rien créer dans Home Assistant). Sans `sun.sun`, le soleil vaut 21 h 30 / 7 h.
 */
object NightMode {
    private const val SUNSET_FALLBACK = 21 * 60 + 30
    private const val SUNRISE_FALLBACK = 7 * 60

    /** Minute du jour d'un réglage ("sun" ou "HH:MM"). */
    fun minutes(spec: String, sun: String?, fallback: Int): Int {
        if (spec != "sun") return parse(spec) ?: fallback
        val iso = sun ?: return fallback
        return runCatching {
            val t = OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).toLocalTime()
            t.hour * 60 + t.minute
        }.getOrDefault(fallback)
    }

    fun parse(hhmm: String): Int? {
        val p = hhmm.split(':')
        val h = p.getOrNull(0)?.toIntOrNull() ?: return null
        val m = p.getOrNull(1)?.toIntOrNull() ?: 0
        return if (h in 0..23 && m in 0..59) h * 60 + m else null
    }

    fun format(min: Int) = "%02d:%02d".format(min / 60, min % 60)

    fun isNight(s: Settings, states: Map<String, EntityState>, now: LocalTime = LocalTime.now()): Boolean {
        if (!s.nightEnabled) return false
        val sun = states["sun.sun"]
        val start = minutes(s.nightStart, sun?.attrString("next_setting"), SUNSET_FALLBACK)
        val end = minutes(s.nightEnd, sun?.attrString("next_rising"), SUNRISE_FALLBACK)
        val m = now.hour * 60 + now.minute
        return if (start <= end) m in start until end else m >= start || m < end
    }
}
