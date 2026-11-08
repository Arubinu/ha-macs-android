package fr.pergens.hamacs

import fr.pergens.hamacs.detections.HistoryLogic
import fr.pergens.hamacs.detections.HistoryLogic.Period
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class HistoryLogicTest {
    private val today = LocalDate.of(2026, 11, 8)

    @Test fun `periodes`() {
        assertEquals(LocalDateTime.of(2026, 11, 8, 0, 0) to LocalDateTime.of(2026, 11, 9, 0, 0), HistoryLogic.range(Period.Today, null, today))
        assertEquals(LocalDateTime.of(2026, 11, 7, 0, 0) to LocalDateTime.of(2026, 11, 8, 0, 0), HistoryLogic.range(Period.Yesterday, null, today))
        assertEquals(LocalDateTime.of(2026, 11, 2, 0, 0), HistoryLogic.range(Period.Week, null, today).first)
        assertEquals(LocalDateTime.of(2026, 10, 10, 0, 0), HistoryLogic.range(Period.Month, null, today).first)
        val d = LocalDate.of(2026, 10, 1)
        assertEquals(d.atStartOfDay() to d.plusDays(1).atStartOfDay(), HistoryLogic.range(Period.Day, d, today))
    }

    @Test fun `histogramme par heure sur une journee`() {
        val (s, e) = HistoryLogic.range(Period.Today, null, today)
        assertTrue(HistoryLogic.hourly(s, e))
        val h = HistoryLogic.histogram(
            listOf(LocalDateTime.of(2026, 11, 8, 14, 5), LocalDateTime.of(2026, 11, 8, 14, 59), LocalDateTime.of(2026, 11, 8, 23, 0), LocalDateTime.of(2026, 11, 7, 14, 0)),
            s, e,
        )
        assertEquals(24, h.size)
        assertEquals(2, h[14].count)
        assertEquals(1, h[23].count)
        assertEquals(3, h.sumOf { it.count }) // hors periode ignoree
    }

    @Test fun `histogramme par jour sur une semaine`() {
        val (s, e) = HistoryLogic.range(Period.Week, null, today)
        assertFalse(HistoryLogic.hourly(s, e))
        val h = HistoryLogic.histogram(listOf(LocalDateTime.of(2026, 11, 2, 1, 0), LocalDateTime.of(2026, 11, 8, 22, 0)), s, e)
        assertEquals(7, h.size)
        assertEquals(1, h.first().count)
        assertEquals(1, h.last().count)
        assertEquals(LocalDateTime.of(2026, 11, 8, 0, 0), h.last().start)
    }

    @Test fun `jours avec detections`() {
        val days = HistoryLogic.days(listOf(LocalDateTime.of(2026, 11, 1, 1, 0), LocalDateTime.of(2026, 11, 8, 2, 0), LocalDateTime.of(2026, 11, 8, 3, 0)))
        assertEquals(listOf(LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 1)), days)
    }
}
