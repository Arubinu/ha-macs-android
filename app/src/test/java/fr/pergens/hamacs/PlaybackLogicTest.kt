package fr.pergens.hamacs

import fr.pergens.hamacs.ui.PlaybackLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackLogicTest {
    @Test fun clock() {
        assertEquals("0:00", PlaybackLogic.clock(0))
        assertEquals("0:00", PlaybackLogic.clock(-500))
        assertEquals("0:07", PlaybackLogic.clock(7_999))
        assertEquals("12:34", PlaybackLogic.clock(754_000))
        assertEquals("1:02:03", PlaybackLogic.clock(3_723_000))
    }

    @Test fun fraction() {
        assertEquals(0f, PlaybackLogic.fraction(3_000, 0), 0f)
        assertEquals(0.5f, PlaybackLogic.fraction(3_000, 6_000), 0.0001f)
        assertEquals(1f, PlaybackLogic.fraction(7_000, 6_000), 0f)
        assertEquals(0f, PlaybackLogic.fraction(-10, 6_000), 0f)
    }

    @Test fun positionAt() {
        assertEquals(3_000L, PlaybackLogic.positionAt(0.5f, 6_000))
        assertEquals(6_000L, PlaybackLogic.positionAt(1.4f, 6_000))
        assertEquals(0L, PlaybackLogic.positionAt(-0.2f, 6_000))
        assertEquals(0L, PlaybackLogic.positionAt(0.5f, 0))
    }

    @Test fun hasProgress() {
        assertTrue(PlaybackLogic.hasProgress(6_000, live = false))
        assertFalse(PlaybackLogic.hasProgress(6_000, live = true))
        assertFalse(PlaybackLogic.hasProgress(0, live = false))
    }
}
