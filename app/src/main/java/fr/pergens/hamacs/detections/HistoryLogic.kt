package fr.pergens.hamacs.detections

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Calculs de l'historique des détections (périodes, histogramme d'activité), sans Android : testés. */
internal object HistoryLogic {
    enum class Period { Today, Yesterday, Week, Month, Day }

    /** Début (inclus) et fin (exclue) de [period] ; [day] pour [Period.Day]. */
    fun range(period: Period, day: LocalDate?, today: LocalDate): Pair<LocalDateTime, LocalDateTime> {
        val end = today.plusDays(1).atStartOfDay()
        return when (period) {
            Period.Today -> today.atStartOfDay() to end
            Period.Yesterday -> today.minusDays(1).atStartOfDay() to today.atStartOfDay()
            Period.Week -> today.minusDays(6).atStartOfDay() to end
            Period.Month -> today.minusDays(29).atStartOfDay() to end
            Period.Day -> (day ?: today).let { it.atStartOfDay() to it.plusDays(1).atStartOfDay() }
        }
    }

    /** Une seule journée : histogramme par heure ; sinon par jour. */
    fun hourly(start: LocalDateTime, end: LocalDateTime) = ChronoUnit.HOURS.between(start, end) <= 24

    /** Case de l'histogramme : [start] inclus, [end] exclu, [count] détections. */
    data class Bucket(val start: LocalDateTime, val end: LocalDateTime, val count: Int)

    /** Détections ([times]) réparties par heure (une journée) ou par jour, sur toute la période (cases vides comprises). */
    fun histogram(times: List<LocalDateTime>, start: LocalDateTime, end: LocalDateTime): List<Bucket> {
        val unit = if (hourly(start, end)) ChronoUnit.HOURS else ChronoUnit.DAYS
        val n = unit.between(start, end).toInt().coerceAtLeast(1)
        val counts = IntArray(n)
        times.forEach { t ->
            if (!t.isBefore(start) && t.isBefore(end)) {
                val i = unit.between(start, t).toInt()
                if (i in 0 until n) counts[i]++
            }
        }
        return List(n) { i -> Bucket(start.plus(i.toLong(), unit), start.plus(i + 1L, unit), counts[i]) }
    }

    /** Jours (du plus récent au plus ancien) qui ont au moins une détection : choix d'un jour. */
    fun days(times: List<LocalDateTime>): List<LocalDate> = times.map { it.toLocalDate() }.distinct().sortedDescending()
}
