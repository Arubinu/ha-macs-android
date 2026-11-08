package fr.pergens.hamacs

import fr.pergens.hamacs.config.BlockOverrides
import fr.pergens.hamacs.config.s
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Surcharges des blocs (mode édition) appliquées à l'agencement. */
class BlockOverridesTest {
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test fun `identifiant d'entite`() {
        assertTrue(BlockOverrides.isEntityId("light.salon_1"))
        assertFalse(BlockOverrides.isEntityId("Light.Salon"))
        assertFalse(BlockOverrides.isEntityId("salon"))
        assertFalse(BlockOverrides.isEntityId(null))
    }

    @Test fun `cle d'un bloc`() {
        assertEquals("light.a", BlockOverrides.keyOf(obj("""{"type":"light","entity":"light.a"}""")))
        assertEquals("member:alex", BlockOverrides.keyOf(obj("""{"id":"alex","person":"person.alex"}""")))
        assertEquals("mgc:sensor.p1", BlockOverrides.keyOf(obj("""{"type":"mini_graph","entities":["sensor.p1","sensor.p2"]}""")))
        assertEquals("swipe:light.a", BlockOverrides.keyOf(obj("""{"type":"swipe","cards":[{"entity":"light.a"}]}""")))
        assertEquals("webview:http://x/y", BlockOverrides.keyOf(obj("""{"type":"webview","url":"http://x/y?z=1"}""")))
        assertNull(BlockOverrides.keyOf(obj("""{"type":"clock"}""")))
    }

    @Test fun `sans surcharge - agencement inchange`() {
        val root = obj("""{"cards":[{"entity":"light.a"}]}""")
        assertSame(root, BlockOverrides.apply(root, mapOf("light.a" to obj("""{"protected":true}"""))))
    }

    @Test fun `surcharge appliquee avec la cle d'origine`() {
        val root = obj("""{"cards":[{"type":"light","entity":"light.a","name":"A"}]}""")
        val out = BlockOverrides.apply(root, mapOf("light.a" to obj("""{"set":{"entity":"light.b"}}""")))
        val card = out["cards"]!!.jsonArray[0].jsonObject
        assertEquals("light.b", card.s("entity"))
        assertEquals("A", card.s("name"))
        assertEquals("light.a", card.s("_key"))
    }

    @Test fun `structure seulement - les champs d'un bloc ne sont pas appliques`() {
        val root = obj("""{"cards":[{"entity":"light.a"}]}""")
        val out = BlockOverrides.apply(root, mapOf("light.a" to obj("""{"set":{"entity":"light.b"}}""")), structuralOnly = true)
        assertEquals("light.a", out["cards"]!!.jsonArray[0].jsonObject.s("entity"))
    }

    @Test fun `alertes non touchees`() {
        val root = obj("""{"cards":[{"type":"alert_ticker","entity":"light.a"}]}""")
        val out = BlockOverrides.apply(root, mapOf("light.a" to obj("""{"set":{"entity":"light.b"}}""")))
        assertEquals("light.a", out["cards"]!!.jsonArray[0].jsonObject.s("entity"))
    }

    @Test fun `entite remplacee - reglages retrouves par la nouvelle`() {
        val blocks = mapOf(
            "light.a" to obj("""{"set":{"entity":"light.b"},"protected":true}"""),
            "graph:bar:light.a" to obj("""{"height":120}"""),
        )
        assertEquals("light.a", BlockOverrides.resolve(blocks, "light.b"))
        assertEquals("graph:bar:light.a", BlockOverrides.resolve(blocks, "graph:bar:light.b"))
        assertEquals("light.z", BlockOverrides.resolve(blocks, "light.z"))
        val al = BlockOverrides.aliases(blocks)
        assertSame(blocks["light.a"], al["light.b"])
        assertSame(blocks["graph:bar:light.a"], al["graph:bar:light.b"])
    }

    @Test fun `recherche d'un bloc`() {
        val root = obj("""{"a":[{"type":"x","entity":"light.a"},{"type":"mini_graph","entities":[{"entity":"sensor.t"}]}]}""")
        assertEquals("x", BlockOverrides.find(root, "light.a")?.s("type"))
        assertNull(BlockOverrides.find(root, "light.a", type = "y"))
        assertEquals("mini_graph", BlockOverrides.findWithEntity(root, "sensor.t", "mini_graph")?.s("type"))
    }
}
