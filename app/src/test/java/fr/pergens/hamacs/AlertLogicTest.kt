package fr.pergens.hamacs

import fr.pergens.hamacs.ha.EntityState
import fr.pergens.hamacs.ui.cards.alerts.breathe
import fr.pergens.hamacs.ui.cards.alerts.compareState
import fr.pergens.hamacs.ui.cards.alerts.evalJinja
import fr.pergens.hamacs.ui.cards.alerts.jsFloat
import fr.pergens.hamacs.ui.cards.alerts.keyframes
import fr.pergens.hamacs.ui.cards.alerts.parseTimestamp
import fr.pergens.hamacs.ui.cards.alerts.phase
import fr.pergens.hamacs.ui.cards.alerts.triggerTimeLabel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** Moteur des alertes (AlertTicker refait dans l'app) : conditions, templates, heure de déclenchement, animations. */
class AlertLogicTest {
    // --- Conditions -------------------------------------------------------------------------------

    @Test fun `egalite - nombres comme nombres, sinon texte`() {
        assertTrue(compareState("20.0", "20", "="))
        assertTrue(compareState("on", "on", "=="))
        assertFalse(compareState("On", "on", "="))
        assertTrue(compareState("21", "20", "!="))
        assertTrue(compareState("off", "on", "!="))
    }

    @Test fun `comparaisons numeriques - prefixe numerique comme parseFloat`() {
        assertTrue(compareState("25.5 °C", "25", ">"))
        assertTrue(compareState("5", "10", "<"))
        assertTrue(compareState("10", "10", ">="))
        assertTrue(compareState("10", "10", "<="))
        assertFalse(compareState("abc", "10", ">"))
        assertFalse(compareState("unavailable", "10", "<"))
    }

    @Test fun `contient - sans casse`() {
        assertTrue(compareState("Porte OUVERTE", "ouverte", "contains"))
        assertTrue(compareState("fermee", "ouverte", "not_contains"))
    }

    @Test fun `anciennete d'un horodatage`() {
        val now = 1_762_500_000_000L
        val tenMinAgo = "2025-11-07T07:10:00+00:00"
        assertTrue(compareState(tenMinAgo, "300", "older", now))
        assertFalse(compareState(tenMinAgo, "300", "newer", now))
        assertTrue(compareState((now / 1000 - 30).toString(), "60", "newer", now))
        assertFalse(compareState("pas une date", "60", "older", now))
    }

    @Test fun `operateur inconnu - egalite stricte`() {
        assertTrue(compareState("x", "x", "~"))
        assertFalse(compareState("1", "1.0", "~"))
    }

    @Test fun `conversions de nombres et dates`() {
        assertEquals(12.5, jsFloat(" 12.5abc")!!, 0.0)
        assertEquals(-3.0, jsFloat("-3")!!, 0.0)
        assertEquals(1e3, jsFloat("1e3")!!, 0.0)
        assertNull(jsFloat("abc"))
        assertEquals(1_762_500_000_000L, parseTimestamp("1762500000"))
        assertEquals(1_762_500_000_000L, parseTimestamp("1762500000000"))
        assertEquals(1_762_500_000_000L, parseTimestamp("2025-11-07T07:20:00Z"))
    }

    // --- Templates locaux -------------------------------------------------------------------------

    private val states = mapOf(
        "sensor.t" to EntityState("sensor.t", "21.456", Json.parseToJsonElement("""{"forecast":[{"c":"pluie"}]}""").jsonObject, 0, 0),
        "binary_sensor.p" to EntityState("binary_sensor.p", "on", Json.parseToJsonElement("{}").jsonObject, 0, 0),
    )
    private fun eval(e: String) = evalJinja(e, states) { it.state }

    @Test fun `templates - etats, attributs, filtres`() {
        assertEquals("21.456", eval("states('sensor.t')"))
        assertEquals("21.5", eval("states('sensor.t') | round(1)"))
        assertEquals("21", eval("states(\"sensor.t\") | int"))
        assertEquals("unknown", eval("states('sensor.absent')"))
        assertEquals("inconnu", eval("states('sensor.absent') | default('inconnu')"))
        assertEquals("pluie", eval("state_attr('sensor.t', 'forecast.0.c')"))
        assertEquals("PLUIE", eval("state_attr('sensor.t', 'forecast[0].c') | upper"))
        assertEquals("true", eval("is_state('binary_sensor.p', 'on')"))
        assertEquals("false", eval("is_state('binary_sensor.p', 'off')"))
        assertNull(eval("now().hour"))
    }

    // --- Heure du déclenchement -------------------------------------------------------------------

    @Test fun `heure du declenchement - aujourd'hui, hier, autre jour`() {
        val zone = ZoneId.of("Europe/Paris")
        val today = LocalDate.of(2026, 11, 8)
        fun at(d: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 11, d, h, m, 0, 0, zone).toInstant().toEpochMilli()
        fun label(ms: Long) = triggerTimeLabel(ms, today, zone, Locale.FRANCE, { "$it (hier)" }, { t, d -> "$t ($d)" })
        assertEquals("09:58", label(at(8, 9, 58)))
        assertEquals("18:20 (hier)", label(at(7, 18, 20)))
        assertEquals("18:20 (4 nov.)", label(at(4, 18, 20)))
    }

    // --- Animations -------------------------------------------------------------------------------

    @Test fun `phase d'un cycle, avec decalage et temps negatif`() {
        assertEquals(0f, phase(0, 1000), 1e-6f)
        assertEquals(0.25f, phase(1250, 1000), 1e-6f)
        assertEquals(0.75f, phase(0, 1000, delay = 250), 1e-6f)
        assertEquals(0f, phase(500, 0), 0f)
    }

    @Test fun `respiration et images-cles`() {
        assertEquals(0f, breathe(0f), 1e-6f)
        assertEquals(1f, breathe(0.5f), 1e-6f)
        assertEquals(0f, breathe(1f), 1e-6f)
        val stops = arrayOf(0f to 0f, 0.5f to 1f, 1f to 0f)
        assertEquals(0f, keyframes(-1f, *stops), 0f)
        assertEquals(1f, keyframes(0.5f, *stops), 1e-6f)
        assertEquals(0.5f, keyframes(0.25f, *stops), 1e-6f)
        assertEquals(0f, keyframes(2f, *stops), 0f)
    }
}
