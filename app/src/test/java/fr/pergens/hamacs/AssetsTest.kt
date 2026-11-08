package fr.pergens.hamacs

import fr.pergens.hamacs.dlna.Scpd
import fr.pergens.hamacs.ui.Markdown
import fr.pergens.hamacs.ui.cards.alerts.AtcThemes
import fr.pergens.hamacs.ui.cards.weatherIconName
import fr.pergens.hamacs.ui.theme.parseCssColor
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Fichiers embarqués (icônes, météo, traductions des alertes, descriptions DLNA) et petites conversions d'affichage. */
class AssetsTest {
    private val assets = File("src/main/assets")
    private val sources = File("src/main/java")
    private fun json(f: String): JsonObject = Json.parseToJsonElement(File(assets, f).readText()).jsonObject

    // --- Icônes -----------------------------------------------------------------------------------

    @Test fun `icones mdi de l'agencement et du code presentes dans mdi_json`() {
        val known = json("mdi.json").keys
        val used = Regex("\"mdi:([a-z0-9-]+)\"")
        val missing = (sources.walkTopDown().filter { it.extension == "kt" } + File(assets, "dashboard.json"))
            .flatMap { f -> used.findAll(f.readText()).map { f.name to it.groupValues[1] } }
            .filter { (_, name) -> name !in known }.map { (f, n) -> "$f : mdi:$n" }.distinct().toList()
        assertTrue(missing.joinToString("\n"), missing.isEmpty())
    }

    @Test fun `icones hue de l'agencement presentes dans hue_json`() {
        val known = json("hue.json").keys
        val missing = Regex("\"hue:([a-z0-9-]+)\"").findAll(File(assets, "dashboard.json").readText())
            .map { it.groupValues[1] }.filter { it !in known }.toList()
        assertTrue(missing.joinToString(), missing.isEmpty())
    }

    @Test fun `icones meteo presentes, fixes et animees`() {
        val conditions = listOf(
            "clear-night", "clear", "sunny", "partlycloudy", "cloudy", "fog", "hail", "lightning", "lightning-rainy", "pouring",
            "rainy", "snowy", "snowy-rainy", "windy", "windy-variant", "windy-exceptional", "exceptional", "sunrise", "sunset", null, "inconnu",
        )
        val missing = conditions.flatMap { c -> listOf(false, true).map { weatherIconName(c, it) } }.distinct()
            .flatMap { n -> listOf("weather/$n.svg", "weather/animated/$n.svg") }.filter { !File(assets, it).isFile }
        assertTrue(missing.joinToString(), missing.isEmpty())
    }

    // --- Alertes ----------------------------------------------------------------------------------

    private val langs = setOf("fr", "en", "de", "es", "it", "nl", "pt", "pl")

    @Test fun `traductions des alertes - memes cles dans les 8 langues`() {
        val i18n = json("alert_ticker/i18n.json")
        val errors = mutableListOf<String>()
        for (section in listOf("card", "editor", "default_msg", "app")) {
            val byLang = i18n[section]!!.jsonObject
            if (byLang.keys != langs) errors += "$section : langues ${byLang.keys}"
            val ref = byLang["en"]!!.jsonObject.keys
            byLang.forEach { (l, v) ->
                val keys = v.jsonObject.keys
                (ref - keys).forEach { errors += "$section/$l : manque $it" }
                (keys - ref).forEach { errors += "$section/$l : en trop $it" }
            }
        }
        assertTrue(errors.joinToString("\n"), errors.isEmpty())
    }

    @Test fun `themes des alertes - ceux de l'editeur existent dans l'app`() {
        val i18n = json("alert_ticker/i18n.json")
        val options = (i18n["theme_options"] as JsonArray).map { (it.jsonObject["value"] as JsonPrimitive).content }
        val named = i18n["theme_meta"]!!.jsonObject.keys + i18n["theme_desc"]!!.jsonObject.keys
        val missing = (options + named).distinct().filter { !AtcThemes.exists(it) }
        assertTrue(missing.joinToString(), missing.isEmpty())
        val untranslated = i18n["theme_desc"]!!.jsonObject.filter { (_, v) -> v.jsonObject.keys != langs }.keys
        assertTrue("descriptions incompletes : $untranslated", untranslated.isEmpty())
    }

    @Test fun `modeles d'alertes - nom et description dans les 8 langues`() {
        val errors = json("alert_ticker/i18n.json")["templates"]!!.jsonObject.flatMap { (id, t) ->
            listOf("name", "desc").mapNotNull { k ->
                val keys = (t.jsonObject[k] as? JsonObject)?.keys.orEmpty()
                if (keys != langs) "$id.$k : $keys" else null
            }
        }
        assertTrue(errors.joinToString("\n"), errors.isEmpty())
    }

    @Test fun `theme inconnu - theme d'urgence par defaut`() {
        assertTrue(AtcThemes.exists("FIRE"))
        assertEquals("emergency", AtcThemes["inexistant"].id)
        assertEquals("emergency", AtcThemes[null].id)
    }

    // --- DLNA -------------------------------------------------------------------------------------

    @Test fun `descriptions des services DLNA - XML valide avec leurs actions`() {
        val expected = mapOf(
            "AVTransport" to listOf("SetAVTransportURI", "Play", "Pause", "Stop", "Seek", "GetPositionInfo", "GetTransportInfo"),
            "RenderingControl" to listOf("GetVolume", "SetVolume", "GetMute", "SetMute"),
            "ConnectionManager" to listOf("GetProtocolInfo"),
        )
        expected.forEach { (service, actions) ->
            val xml = Scpd.of(service)!!
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
            val names = doc.getElementsByTagName("action").let { l -> (0 until l.length).map { (l.item(it) as org.w3c.dom.Element).getElementsByTagName("name").item(0).textContent } }
            actions.forEach { a -> assertTrue("$service : $a", a in names) }
            // Chaque argument renvoie à une variable d'état déclarée.
            val vars = doc.getElementsByTagName("stateVariable").let { l -> (0 until l.length).map { (l.item(it) as org.w3c.dom.Element).getElementsByTagName("name").item(0).textContent }.toSet() }
            val refs = doc.getElementsByTagName("relatedStateVariable").let { l -> (0 until l.length).map { l.item(it).textContent } }
            assertTrue("$service : ${refs - vars}", (refs - vars).isEmpty())
        }
        assertNull(Scpd.of("Inconnu"))
    }

    // --- Conversions d'affichage ------------------------------------------------------------------

    @Test fun `couleurs CSS`() {
        assertEquals(Color(0xFF8CF5FF), parseCssColor("#8CF5FF"))
        assertEquals(Color(0xFFFF0000), parseCssColor(" #f00 "))
        assertEquals(Color(0x80112233), parseCssColor("#11223380"))
        assertEquals(Color(255, 0, 0, 128).red, parseCssColor("rgba(255, 0, 0, 0.5)")!!.red, 1e-3f)
        assertEquals(0.5f, parseCssColor("rgba(255, 0, 0, 0.5)")!!.alpha, 1e-2f)
        assertEquals(Color.Transparent, parseCssColor("none"))
        assertEquals(Color.White, parseCssColor("WHITE"))
        assertNull(parseCssColor("#12"))
        assertNull(parseCssColor("rgb(1,2)"))
        assertNull(parseCssColor("bleu"))
        assertNull(parseCssColor(null))
    }

    @Test fun `markdown en ligne - gras et code`() {
        val s = Markdown.inline("Un **gras** et `code` et ** seul", Color.Red)
        assertEquals("Un gras et code et ** seul", s.text)
        assertEquals(2, s.spanStyles.size)
        assertEquals("gras", s.text.substring(s.spanStyles[0].start, s.spanStyles[0].end))
        assertEquals("code", s.text.substring(s.spanStyles[1].start, s.spanStyles[1].end))
    }
}
