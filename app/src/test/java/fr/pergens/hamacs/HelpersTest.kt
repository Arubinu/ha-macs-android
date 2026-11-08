package fr.pergens.hamacs

import fr.pergens.hamacs.ha.EntityState
import fr.pergens.hamacs.ui.Markdown
import fr.pergens.hamacs.ui.NightMode
import fr.pergens.hamacs.ui.cards.alerts.hasTemplate
import fr.pergens.hamacs.ui.cards.alerts.jsString
import fr.pergens.hamacs.ui.cards.alerts.rawValue
import fr.pergens.hamacs.ui.cards.alerts.resolveAttrPath
import fr.pergens.hamacs.update.Updater
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Petites fonctions sans Android : versions, notes de version, mode nuit, journal, alertes. */
class HelpersTest {
    // --- Mises à jour ---------------------------------------------------------------------------

    @Test fun `version plus recente nombre par nombre`() {
        assertTrue(Updater.newer("1.0.2", "1.0.1"))
        assertTrue(Updater.newer("1.2.10", "1.2.9"))
        assertTrue(Updater.newer("2.0.0", "1.99.99"))
        assertFalse(Updater.newer("1.0.1", "1.0.1"))
        assertFalse(Updater.newer("1.0.0", "1.0.1"))
        assertTrue(Updater.newer("1.1", "1.0.9"))
    }

    @Test fun `stable avant ses preversions, preversions dans l'ordre`() {
        assertTrue(Updater.newer("1.3.0", "1.3.0-beta.2"))
        assertFalse(Updater.newer("1.3.0-beta.2", "1.3.0"))
        assertTrue(Updater.newer("1.3.0-beta.2", "1.3.0-beta.1"))
        assertTrue(Updater.newer("1.3.0-beta.10", "1.3.0-beta.9"))
        assertTrue(Updater.newer("1.3.0-beta.1", "1.2.9"))
    }

    // --- Notes de version -----------------------------------------------------------------------

    @Test fun `markdown - titres, puces, paragraphes, liens et images`() {
        val md = "# Titre\n\n- Point [lien](http://x)\n* Autre ![img](a.png)\n\nTexte <!-- note -->\n\n\n"
        assertEquals(
            listOf(
                Markdown.Block.Heading(1, "Titre"), Markdown.Block.Gap,
                Markdown.Block.Bullet("Point lien"), Markdown.Block.Bullet("Autre"), Markdown.Block.Gap,
                Markdown.Block.Para("Texte"),
            ),
            Markdown.parse(md),
        )
    }

    @Test fun `markdown - rien apres la fin des nouveautes`() {
        val md = "## Nouveau\r\n- A\r\n${Markdown.END}\n- app-arm64-v8a-release.apk"
        assertEquals(listOf(Markdown.Block.Heading(2, "Nouveau"), Markdown.Block.Bullet("A")), Markdown.parse(md))
    }

    // --- Mode nuit ------------------------------------------------------------------------------

    @Test fun `heures du mode nuit`() {
        assertEquals(21 * 60 + 30, NightMode.parse("21:30"))
        assertEquals(7 * 60, NightMode.parse("7"))
        assertNull(NightMode.parse("24:00"))
        assertNull(NightMode.parse("12:60"))
        assertNull(NightMode.parse("abc"))
        assertEquals("07:05", NightMode.format(7 * 60 + 5))
    }

    @Test fun `soleil absent ou illisible - heure de repli`() {
        assertEquals(1290, NightMode.minutes("sun", null, 1290))
        assertEquals(1290, NightMode.minutes("sun", "pas une date", 1290))
        assertEquals(1290, NightMode.minutes("xx:yy", null, 1290))
        assertEquals(22 * 60, NightMode.minutes("22:00", null, 1290))
    }

    // --- Journal --------------------------------------------------------------------------------

    @Test fun `adresse du journal sans secret`() {
        assertEquals(
            "http://ha.local:8123/api/hls/…/master_playlist.m3u8",
            CrashGuard.redact("http://ha.local:8123/api/hls/abcdef123/master_playlist.m3u8?token=xyz"),
        )
        assertEquals("rtsp://…@192.168.1.5:554/h264", CrashGuard.redact("rtsp://admin:secret@192.168.1.5:554/h264"))
        assertEquals("", CrashGuard.redact(null))
    }

    // --- Alertes --------------------------------------------------------------------------------

    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test fun `chemin d'attribut imbrique`() {
        val a = obj("""{"activity":[{"forecast":"pluie"}],"x":{"y":3}}""")
        assertEquals("pluie", jsString(resolveAttrPath(a, "activity.0.forecast")))
        assertEquals("pluie", jsString(resolveAttrPath(a, "activity[0].forecast")))
        assertEquals("3", jsString(resolveAttrPath(a, "x.y")))
        assertNull(resolveAttrPath(a, "activity.5.forecast"))
        assertNull(resolveAttrPath(a, ""))
    }

    @Test fun `conversion comme String() de JavaScript`() {
        assertEquals("", jsString(null))
        assertEquals("", jsString(JsonNull))
        assertEquals("a,1,true", jsString(Json.parseToJsonElement("""["a",1,true]""")))
        assertEquals("[object Object]", jsString(obj("{}")))
    }

    @Test fun `valeur comparee - etat ou attribut`() {
        val e = EntityState("sensor.t", "21.5", obj("""{"unit":"C","list":[1,2]}"""), 0L, 0L)
        assertEquals("21.5", rawValue(e, null))
        assertEquals("1,2", rawValue(e, "list"))
    }

    @Test fun `detection de modele jinja`() {
        assertTrue(hasTemplate("{{ states('x') }}"))
        assertTrue(hasTemplate("{% if %}"))
        assertFalse(hasTemplate("texte simple"))
        assertFalse(hasTemplate(null))
    }
}
