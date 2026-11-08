package fr.pergens.hamacs

import fr.pergens.hamacs.ha.EntityState
import fr.pergens.hamacs.ha.Fmt
import fr.pergens.hamacs.ha.StateSpan
import fr.pergens.hamacs.ha.VirtualEntities
import fr.pergens.hamacs.ha.parseHistory
import fr.pergens.hamacs.ha.parseTs
import fr.pergens.hamacs.ui.cards.Period
import fr.pergens.hamacs.ui.cards.duration
import fr.pergens.hamacs.ui.cards.periods
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Données reçues de Home Assistant : historique, présence, vigilance météo, formats. */
class HaDataTest {
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun st(id: String, state: String, attrs: String = "{}", changed: Long = 0L) = EntityState(id, state, obj(attrs), changed, changed)

    // --- Historique -------------------------------------------------------------------------------

    @Test fun `horodatages - secondes, millisecondes, ISO`() {
        assertEquals(1_762_500_000_000L, parseTs(JsonPrimitive(1_762_500_000.0)))
        assertEquals(1_762_500_000_123L, parseTs(JsonPrimitive(1_762_500_000_123L)))
        assertEquals(1_762_500_000_000L, parseTs(JsonPrimitive("2025-11-07T07:20:00+00:00")))
        assertNull(parseTs(JsonPrimitive("hier")))
        assertNull(parseTs(null))
    }

    @Test fun `historique minimal - lu puis lc, lignes illisibles ignorees`() {
        val res = obj("""{"sensor.a":[{"s":"on","lu":1762500000.5},{"s":"off","lc":1762500060},{"s":"x"},5],"sensor.b":[]}""")
        val h = parseHistory(res)
        assertEquals(listOf(StateSpan(1_762_500_000_500L, "on"), StateSpan(1_762_500_060_000L, "off")), h["sensor.a"])
        assertEquals(emptyList<StateSpan>(), h["sensor.b"])
    }

    // --- Présence ---------------------------------------------------------------------------------

    private val h = 3_600_000L

    @Test fun `periodes - fusion, inconnu prolonge, zone nommee`() {
        val now = 24 * h
        val spans = listOf(
            StateSpan(0, "home"), StateSpan(2 * h, "unavailable"), StateSpan(3 * h, "home"),
            StateSpan(8 * h, "not_home"), StateSpan(10 * h, "Travail"), StateSpan(18 * h, "home"),
        )
        assertEquals(
            listOf(
                Period(0, 8 * h, true, null), Period(8 * h, 10 * h, false, null),
                Period(10 * h, 18 * h, false, "Travail"), Period(18 * h, now, true, null),
            ),
            periods(spans, 24, now),
        )
    }

    @Test fun `periodes - coupees au debut de la fenetre`() {
        val now = 30 * h
        val p = periods(listOf(StateSpan(0, "off"), StateSpan(10 * h, "on")), 24, now)
        assertEquals(listOf(Period(6 * h, 10 * h, false, null), Period(10 * h, now, true, null)), p)
    }

    @Test fun `durees affichees`() {
        assertEquals("1 min", duration(10_000))
        assertEquals("45 min", duration(45 * 60_000L))
        assertEquals("2 h 05", duration(125 * 60_000L))
    }

    // --- Vigilance météo --------------------------------------------------------------------------

    @Test fun `vigilance - entites virtuelles par couleur`() {
        val src = st("sensor.departement_weather_alert", "Orange", """{"Vent violent":"Orange","Orages":"Jaune","Canicule":"Vert","Pluie-inondation":"Jaune","friendly_name":"Vigilance"}""", 1000L)
        val out = VirtualEntities.augment(mapOf(src.entityId to src), title = { "titre $it" }, now = 5000L)
        assertEquals("2", out["sensor.hadash_weather_alert_yellow"]!!.state)
        assertEquals("Orages, Pluie-inondation", out["sensor.hadash_weather_alert_yellow"]!!.attrString("names"))
        assertEquals("1", out["sensor.hadash_weather_alert_orange"]!!.state)
        assertEquals("0", out["sensor.hadash_weather_alert_red"]!!.state)
        assertEquals(src, out[src.entityId])
    }

    @Test fun `vigilance - date de changement stable tant que rien ne change`() {
        val src = st("sensor.x_weather_alert", "Rouge", """{"Neige":"Rouge"}""", 1000L)
        val first = VirtualEntities.augment(mapOf(src.entityId to src), { "" }, now = 2000L)["sensor.hadash_weather_alert_red"]!!
        val again = VirtualEntities.augment(mapOf(src.entityId to src), { "" }, now = 9000L)["sensor.hadash_weather_alert_red"]!!
        assertEquals(first.lastChanged, again.lastChanged)
        val src2 = st("sensor.x_weather_alert", "Vert", """{"Neige":"Vert"}""", 1000L)
        val changed = VirtualEntities.augment(mapOf(src2.entityId to src2), { "" }, now = 12_000L)["sensor.hadash_weather_alert_red"]!!
        assertEquals("0", changed.state)
        assertEquals(12_000L, changed.lastChanged)
    }

    @Test fun `sans capteur de vigilance - rien d'ajoute`() {
        val s = st("sensor.temp", "21")
        assertEquals(mapOf(s.entityId to s), VirtualEntities.augment(mapOf(s.entityId to s), { "" }))
    }

    // --- Formats ----------------------------------------------------------------------------------

    @Test fun `nombres a la francaise`() {
        assertEquals("22,0", Fmt.number(22.0, 1))
        assertEquals("—", Fmt.number(null, 1))
        assertEquals("2,109", Fmt.number(2.1093, 3))
    }

    @Test fun `etat avec unite`() {
        assertEquals("21,5°C", Fmt.state(st("sensor.t", "21.49", """{"unit_of_measurement":"°C"}"""), 1))
        assertEquals("80%", Fmt.state(st("sensor.b", "80", """{"unit_of_measurement":"%"}""")))
        assertEquals("3 kWh", Fmt.state(st("sensor.e", "3", """{"unit_of_measurement":"kWh"}""")))
        assertEquals("—", Fmt.state(st("sensor.e", "unavailable")))
        assertEquals("—", Fmt.state(null))
        assertEquals("on €", Fmt.state(st("sensor.x", "on"), suffix = " €"))
    }

    @Test fun `entite - nom, indisponibilite, attributs`() {
        val e = st("light.salon", "unknown", """{"friendly_name":"Salon","brightness":128,"list":["a","b"],"on":true}""")
        assertEquals("Salon", e.name)
        assertEquals("light", e.domain)
        assertEquals(true, e.isUnavailable)
        assertEquals(128.0, e.attrDouble("brightness")!!, 0.0)
        assertEquals(true, e.attrBool("on"))
        assertEquals(listOf("a", "b"), e.attrList("list"))
        assertEquals("cuisine", st("light.cuisine", "on").name)
    }
}
