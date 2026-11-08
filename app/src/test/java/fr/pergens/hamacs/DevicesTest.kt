package fr.pergens.hamacs

import fr.pergens.hamacs.config.Settings
import fr.pergens.hamacs.detections.DetectionScanner
import fr.pergens.hamacs.ha.EntityState
import fr.pergens.hamacs.macs.MacsOptions
import fr.pergens.hamacs.macs.MacsOptions.Kind
import fr.pergens.hamacs.ui.CameraStreams
import fr.pergens.hamacs.ui.NightMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

/** Caméras (flux, captures), M.A.C.S. (unités, charge), mode nuit. */
class DevicesTest {
    // --- Flux des caméras -------------------------------------------------------------------------

    private fun prev(s: String) = CameraStreams.previewRankOf(CameraStreams.norm(s))
    private fun full(s: String) = CameraStreams.fullscreenRankOf(CameraStreams.norm(s))

    @Test fun `flux leger pour la carte, flux net pour le plein ecran`() {
        assertEquals(0, prev("camera.argus_1_fluide Argus 1 Fluide"))
        assertEquals(2, prev("camera.argus_1_clair Argus 1 Clair"))
        assertEquals(0, full("camera.argus_1_clair Argus 1 Clair"))
        assertEquals(2, full("camera.argus_1_fluide Argus 1 Fluide"))
        assertEquals(0, full("camera.entree_hd Entrée HD"))
        assertEquals(0, prev("camera.jardin_sub Jardin sub"))
    }

    @Test fun `captures toujours en dernier, inconnu au milieu`() {
        assertEquals(3, prev("camera.argus_1_snapshots Argus 1 Snapshots fluide"))
        assertEquals(3, full("camera.x Instantanés"))
        assertEquals(1, prev("camera.salon Salon"))
        assertEquals(1, full("camera.salon Salon"))
    }

    @Test fun `mots courts reconnus seulement entiers`() {
        assertEquals(1, full("camera.cabinet Cabinet"))
        assertEquals(1, prev("camera.hdmi_sdr Hdmisdr"))
        assertEquals(0, full("camera.porte_net Porte Net"))
    }

    @Test fun `accents ignores`() {
        assertEquals("entree instantane", CameraStreams.norm("Entrée Instantané"))
    }

    @Test fun `libelle court d'un flux`() {
        assertEquals("Fluide", CameraStreams.labelOf("Argus 1 Fluide", "Argus 1"))
        assertEquals("Clair", CameraStreams.labelOf("argus 1 - clair", "Argus 1"))
        assertEquals("Argus 1", CameraStreams.labelOf("Argus 1", "Argus 1"))
        assertEquals("Salon", CameraStreams.labelOf("Salon", null))
        assertEquals("Autre nom", CameraStreams.labelOf("Autre nom", "Argus 1"))
    }

    // --- Dates des captures -----------------------------------------------------------------------

    @Test fun `date dans le nom d'une capture - formats automatiques`() {
        val t = LocalDateTime.of(2025, 11, 4, 20, 30, 12)
        assertEquals(t, DetectionScanner.timeFromName("Entree_2025-11-04_20-30-12", ""))
        assertEquals(t, DetectionScanner.timeFromName("RecM02_20251104203012", ""))
        assertEquals(t, DetectionScanner.timeFromName("20251104T203012", ""))
        assertEquals(t, DetectionScanner.timeFromName("cam 2025.11.04 20h30m12", ""))
    }

    @Test fun `date - la derniere du nom, date invalide sautee`() {
        assertEquals(LocalDateTime.of(2025, 11, 4, 20, 30, 12), DetectionScanner.timeFromName("20240101000000_20251104203012", ""))
        assertEquals(LocalDateTime.of(2024, 1, 1, 0, 0, 0), DetectionScanner.timeFromName("20240101000000_20251399203012", ""))
        assertNull(DetectionScanner.timeFromName("video_sans_date", ""))
    }

    @Test fun `date - motif choisi`() {
        assertEquals(LocalDateTime.of(2025, 11, 4, 20, 30, 0), DetectionScanner.timeFromName("cam_04-11-2025_2030", "dd-MM-yyyy_HHmm"))
        assertEquals(LocalDateTime.of(2025, 11, 4, 20, 30, 12), DetectionScanner.timeFromName("cam20251104_203012", "yyyyMMdd'_'HHmmss"))
        assertNull(DetectionScanner.timeFromName("cam_sans_date", "dd-MM-yyyy"))
    }

    @Test fun `date - horodatage Unix`() {
        val t = DetectionScanner.timeFromName("event_1762500000123", "")!!
        assertEquals(LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(1_762_500_000L), java.time.ZoneId.systemDefault()), t)
    }

    // --- M.A.C.S. ---------------------------------------------------------------------------------

    @Test fun `unite retenue - choisie, sinon capteur, sinon defaut`() {
        assertEquals("f", MacsOptions.unit(Kind.Temperature, "°F", "°C"))
        assertEquals("c", MacsOptions.unit(Kind.Temperature, "auto", "°C"))
        assertEquals("kph", MacsOptions.unit(Kind.Wind, null, "km/h"))
        assertEquals("mph", MacsOptions.unit(Kind.Wind, null, "beaufort"))
        assertEquals("v", MacsOptions.unit(Kind.Battery, "", "V"))
    }

    @Test fun `plages par defaut converties`() {
        assertEquals(5.0 to 30.0, MacsOptions.defaultRange(Kind.Temperature, "c"))
        assertEquals(41.0 to 86.0, MacsOptions.defaultRange(Kind.Temperature, "f"))
        assertEquals(0.0 to 100.0, MacsOptions.defaultRange(Kind.Precipitation, "%"))
    }

    private fun st(state: String, attrs: String = "{}") = EntityState("sensor.x", state, Json.parseToJsonElement(attrs).jsonObject, 0, 0)

    @Test fun `en charge - attributs puis etat`() {
        assertEquals(true, MacsOptions.charging(st("off", """{"is_charging":true}""")))
        assertEquals(false, MacsOptions.charging(st("on", """{"charging":"discharging"}""")))
        assertEquals(true, MacsOptions.charging(st("Charging")))
        assertEquals(false, MacsOptions.charging(st("not_charging")))
        assertNull(MacsOptions.charging(st("unavailable")))
    }

    // --- Mode nuit --------------------------------------------------------------------------------

    private fun night(nightStart: String, nightEnd: String) = Settings(
        haUrl = "", haToken = "", voiceUrl = "", keepScreenOn = false, textScale = 0f, idleSeconds = 0,
        presenceDetection = false, presenceSensitivity = 1, dimSeconds = 0, dimLevel = 0f, tapFullscreen = false,
        fullscreenFill = false, nightEnabled = true, nightStart = nightStart, nightEnd = nightEnd,
    )

    @Test fun `nuit a cheval sur minuit`() {
        val s = night(nightStart = "22:00", nightEnd = "07:00")
        assertTrue(NightMode.isNight(s, emptyMap(), LocalTime.of(23, 0)))
        assertTrue(NightMode.isNight(s, emptyMap(), LocalTime.of(3, 0)))
        assertFalse(NightMode.isNight(s, emptyMap(), LocalTime.of(7, 0)))
        assertFalse(NightMode.isNight(s, emptyMap(), LocalTime.of(12, 0)))
    }

    @Test fun `nuit dans la journee et mode coupe`() {
        val s = night(nightStart = "13:00", nightEnd = "15:00")
        assertTrue(NightMode.isNight(s, emptyMap(), LocalTime.of(14, 0)))
        assertFalse(NightMode.isNight(s, emptyMap(), LocalTime.of(16, 0)))
        assertFalse(NightMode.isNight(s.copy(nightEnabled = false), emptyMap(), LocalTime.of(14, 0)))
    }

    @Test fun `soleil absent - 21 h 30 a 7 h`() {
        val s = night(nightStart = "sun", nightEnd = "sun")
        assertTrue(NightMode.isNight(s, emptyMap(), LocalTime.of(21, 30)))
        assertFalse(NightMode.isNight(s, emptyMap(), LocalTime.of(21, 29)))
        assertTrue(NightMode.isNight(s, emptyMap(), LocalTime.of(6, 59)))
    }
}
