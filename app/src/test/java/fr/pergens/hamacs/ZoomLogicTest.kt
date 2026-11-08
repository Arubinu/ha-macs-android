package fr.pergens.hamacs

import fr.pergens.hamacs.ui.ZoomLogic
import fr.pergens.hamacs.ui.ZoomLogic.Zoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomLogicTest {
    private val w = 1000f
    private val h = 600f

    @Test fun `pas de zoom sous 1 ni au-dela du maximum`() {
        assertEquals(1f, ZoomLogic.transform(Zoom(), 500f, 300f, 0.5f, 0f, 0f, w, h).scale)
        assertEquals(ZoomLogic.MAX, ZoomLogic.transform(Zoom(4f), 500f, 300f, 3f, 0f, 0f, w, h).scale)
    }

    @Test fun `le point pince reste sous les doigts`() {
        // Sans zoom, le point de contenu sous (800, 150) est a (300, -150) du centre. Apres un zoom x2, il est
        // affiche en centre + decalage + 2 x (300, -150) : toujours en (800, 150).
        val z = ZoomLogic.transform(Zoom(), 800f, 150f, 2f, 0f, 0f, w, h)
        assertEquals(800f, 500f + z.x + 300f * 2f, 0.01f)
        assertEquals(150f, 300f + z.y - 150f * 2f, 0.01f)
    }

    @Test fun `deplacement borne aux bords du contenu`() {
        val z = ZoomLogic.transform(Zoom(2f), 500f, 300f, 1f, 5000f, -5000f, w, h)
        assertEquals(500f, z.x, 0.01f)   // (2 - 1) * 1000 / 2
        assertEquals(-300f, z.y, 0.01f)  // (2 - 1) * 600 / 2
        // Sans zoom : aucun deplacement possible.
        val n = ZoomLogic.transform(Zoom(), 500f, 300f, 1f, 200f, 200f, w, h)
        assertEquals(0f, n.x, 0.01f)
        assertEquals(0f, n.y, 0.01f)
    }

    @Test fun `double toucher - zoom sur le point puis retour`() {
        val z = ZoomLogic.doubleTap(Zoom(), 700f, 300f, w, h)
        assertTrue(z.zoomed)
        assertEquals(ZoomLogic.DOUBLE_TAP, z.scale)
        // Le point touche reste a sa place : decalage = (centre - point) * (echelle - 1).
        assertEquals((500f - 700f) * 1.5f, z.x, 0.01f)
        assertFalse(ZoomLogic.doubleTap(z, 700f, 300f, w, h).zoomed)
    }
}
