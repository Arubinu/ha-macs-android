package fr.pergens.hamacs

import fr.pergens.hamacs.config.s
import fr.pergens.hamacs.ha.EntityState
import fr.pergens.hamacs.ui.CameraPower
import fr.pergens.hamacs.ui.CameraWall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mur de caméras : onglets, caméras, noms, alimentation choisie, batterie affichée. */
class CameraWallTest {
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun st(id: String, state: String, name: String? = null) =
        EntityState(id, state, obj(if (name != null) """{"friendly_name":"$name"}""" else "{}"), 0, 0)

    private val dash = obj(
        """{"screens":[{"cards":[
            {"type":"switcher","id":"cams","pages":[
              {"id":"int","icon":"mdi:sofa","label":"Intérieur","cards":[
                {"type":"camera","entity":"camera.salon"},{"type":"camera","entity":"camera.entree","battery":"sensor.entree_battery"},
                {"type":"camera"}]},
              {"id":"ext","cards":[{"type":"camera","entity":"camera.balcon","battery":"sensor.balcon_battery","name":"Terrasse"}]}
            ]},
            {"type":"camera","entity":"camera.salon"},
            {"type":"camera","entity":"camera.garage"},
            {"type":"camera_detections","cameras":[{"name":"Porte d'entrée","entity":"camera.entree"},{"name":"Garage","match":"Garage"}]}
        ]}]}""",
    )

    @Test fun `onglets du premier bloc d'onglets de cameras`() {
        val pages = CameraWall.pages(dash)
        assertEquals(listOf("int", "ext"), pages.map { it.id })
        assertEquals(listOf("camera.salon", "camera.entree"), pages[0].cams.map { it.s("entity") })
        assertEquals("mdi:sofa", pages[0].icon)
        assertEquals(CameraWall.pages(obj("""{"cards":[]}""")), emptyList<Any>())
    }

    @Test fun `toutes les cameras, une par entite`() {
        assertEquals(listOf("camera.salon", "camera.entree", "camera.balcon", "camera.garage"), CameraWall.allCameras(dash).map { it.s("entity") })
    }

    @Test fun `nom - editeur, carte, detections, HA`() {
        val states = mapOf(
            "camera.salon" to st("camera.salon", "idle", "Salon cam"),
            "camera.garage" to st("camera.garage", "idle", "Garage Fluide"),
            "camera.entree" to st("camera.entree", "idle", "Argus 1"),
        )
        val cams = CameraWall.allCameras(dash).associateBy { it.s("entity")!! }
        val blocks = mapOf("camera.salon" to obj("""{"name":"Séjour"}"""))
        assertEquals("Séjour", CameraWall.displayName(cams["camera.salon"]!!, blocks, states, dash))
        assertEquals("Terrasse", CameraWall.displayName(cams["camera.balcon"]!!, blocks, states, dash))
        assertEquals("Porte d'entrée", CameraWall.displayName(cams["camera.entree"]!!, blocks, states, dash))
        assertEquals("Garage", CameraWall.displayName(cams["camera.garage"]!!, blocks, states, dash))
        assertEquals("Salon cam", CameraWall.displayName(cams["camera.salon"]!!, emptyMap(), states, dash))
    }

    @Test fun `nom - debut de nom HA reconnu seulement sur un mot entier`() {
        val card = obj("""{"type":"camera","entity":"camera.g2"}""")
        val states = mapOf("camera.g2" to st("camera.g2", "idle", "Garages du fond"))
        assertEquals("Garages du fond", CameraWall.displayName(card, emptyMap(), states, dash))
    }

    @Test fun `alimentation - choix de l'editeur, sinon secteur sans batterie`() {
        val salon = obj("""{"type":"camera","entity":"camera.salon"}""")
        val balcon = obj("""{"type":"camera","entity":"camera.balcon","battery":"sensor.balcon_battery"}""")
        assertEquals(CameraPower.Mains, CameraWall.power(salon, emptyMap(), emptyMap()))
        assertEquals(CameraPower.Battery, CameraWall.power(balcon, emptyMap(), emptyMap()))
        assertEquals(CameraPower.Solar, CameraWall.power(balcon, mapOf("camera.balcon" to obj("""{"power":"solar"}""")), emptyMap()))
        assertEquals(CameraPower.Battery, CameraWall.power(balcon, mapOf("camera.balcon" to obj("""{"power":"inconnu"}""")), emptyMap()))
    }

    @Test fun `batterie affichee - jamais sur secteur, sinon selon la carte ou le mur`() {
        val balcon = obj("""{"type":"camera","entity":"camera.balcon","battery":"sensor.balcon_battery"}""")
        val solar = mapOf("camera.balcon" to obj("""{"power":"solar","battery_wall":false}"""))
        assertTrue(CameraWall.showBattery(balcon, solar, emptyMap(), wall = false))
        assertFalse(CameraWall.showBattery(balcon, solar, emptyMap(), wall = true))
        assertFalse(CameraWall.showBattery(balcon, mapOf("camera.balcon" to obj("""{"power":"mains"}""")), emptyMap(), wall = false))
        assertFalse(CameraWall.showBattery(obj("""{"type":"camera","entity":"camera.salon"}"""), emptyMap(), emptyMap(), wall = false))
    }
}
