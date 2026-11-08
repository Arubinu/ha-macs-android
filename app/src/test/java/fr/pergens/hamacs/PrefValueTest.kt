package fr.pergens.hamacs

import fr.pergens.hamacs.config.PrefValue
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** Typage des réglages à l'import (bug de la v1.0.0 : nombres écrits en texte, plantage au démarrage). */
class PrefValueTest {
    @Test fun `nombre entier - Int, jamais du texte`() {
        assertEquals(PrefValue.I(30), PrefValue.of("fullscreen_seconds", JsonPrimitive(30)))
        assertEquals(PrefValue.I(-1), PrefValue.of("wall_seconds", JsonPrimitive(-1)))
    }

    @Test fun `grand nombre - Long`() {
        assertEquals(PrefValue.L(1_762_500_000_000L), PrefValue.of("some_time", JsonPrimitive(1_762_500_000_000L)))
    }

    @Test fun `decimal - Float`() {
        assertEquals(PrefValue.F(0.35f), PrefValue.of("other_level", JsonPrimitive(0.35)))
    }

    @Test fun `reglage decimal ecrit comme un entier - reste un Float`() {
        assertEquals(PrefValue.F(1f), PrefValue.of("text_scale", JsonPrimitive(1)))
        assertEquals(PrefValue.F(1f), PrefValue.of("dim_level", JsonPrimitive("1")))
    }

    @Test fun `reglage entier ecrit en decimal - reste un Int`() {
        assertEquals(PrefValue.I(60), PrefValue.of("idle_seconds", JsonPrimitive(60.0)))
    }

    @Test fun `oui-non - Boolean`() {
        assertEquals(PrefValue.B(true), PrefValue.of("kiosk", JsonPrimitive(true)))
        assertEquals(PrefValue.B(false), PrefValue.of("kiosk", JsonPrimitive(false)))
    }

    @Test fun `texte qui ressemble a un nombre ou a un booleen - reste du texte`() {
        assertEquals(PrefValue.S("1234"), PrefValue.of("access_code", JsonPrimitive("1234")))
        assertEquals(PrefValue.S("true"), PrefValue.of("label", JsonPrimitive("true")))
        assertEquals(PrefValue.S("http://192.168.1.2:8123"), PrefValue.of("ha_url", JsonPrimitive("http://192.168.1.2:8123")))
    }
}
