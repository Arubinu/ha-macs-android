package fr.pergens.hamacs

import fr.pergens.hamacs.ui.CameraPower.Battery
import fr.pergens.hamacs.ui.CameraPower.Mains
import fr.pergens.hamacs.ui.CameraPower.Solar
import fr.pergens.hamacs.ui.WallLogic
import fr.pergens.hamacs.ui.WallLogic.Cam
import org.junit.Assert.assertEquals
import org.junit.Test

class WallLogicTest {
    // --- Alimentation devinée -------------------------------------------------------------------

    @Test fun `sans historique, l'etat actuel decide`() {
        assertEquals(Mains, WallLogic.classify("chargecomplete", null))
        assertEquals(Solar, WallLogic.classify("charging", null))
        assertEquals(Battery, WallLogic.classify("discharging", null))
        assertEquals(Battery, WallLogic.classify("unavailable", null))
    }

    @Test fun `jamais en decharge sur 24 h - secteur`() {
        assertEquals(Mains, WallLogic.classify("chargecomplete", setOf("chargecomplete", "unavailable")))
    }

    @Test fun `charge et decharge sur 24 h - solaire, meme charge terminee en journee`() {
        assertEquals(Solar, WallLogic.classify("chargecomplete", setOf("discharging", "charging")))
        assertEquals(Solar, WallLogic.classify("discharging", setOf("chargecomplete")))
    }

    @Test fun `decharge seule - batterie`() {
        assertEquals(Battery, WallLogic.classify("discharging", setOf("discharging", "unavailable")))
    }

    @Test fun `etat en majuscules accepte`() {
        assertEquals(Mains, WallLogic.classify("ChargeComplete", null))
    }

    // --- Choix des directs ----------------------------------------------------------------------

    private val cams = listOf(
        Cam("entree", Mains), Cam("salon", Mains),
        Cam("balcon", Solar, battery = 80f), Cam("parking", Battery, battery = 90f),
    )

    @Test fun `sans rien de special - secteur seulement`() {
        val s = WallLogic.select(cams, emptySet(), null, forced = false, limit = 6, solarMin = 30)
        assertEquals(setOf("entree", "salon"), s.live)
        assertEquals(emptySet<String>(), s.capped)
    }

    @Test fun `tout en direct - solaire en plus, jamais la batterie seule`() {
        val s = WallLogic.select(cams, emptySet(), null, forced = true, limit = 6, solarMin = 30)
        assertEquals(setOf("entree", "salon", "balcon"), s.live)
    }

    @Test fun `mouvement - meme une camera sur batterie passe en direct`() {
        val s = WallLogic.select(cams, setOf("parking"), null, forced = false, limit = 6, solarMin = 30)
        assertEquals(setOf("entree", "salon", "parking"), s.live)
    }

    @Test fun `limite atteinte - le secteur garde sa place, la forcee est mise de cote`() {
        val s = WallLogic.select(cams, emptySet(), null, forced = true, limit = 2, solarMin = 30)
        assertEquals(setOf("entree", "salon"), s.live)
        assertEquals(setOf("balcon"), s.capped)
    }

    @Test fun `limite atteinte - le mouvement passe avant le secteur`() {
        val s = WallLogic.select(cams, setOf("parking"), null, forced = false, limit = 1, solarMin = 30)
        assertEquals(setOf("parking"), s.live)
        assertEquals(setOf("entree", "salon"), s.capped)
    }

    @Test fun `camera agrandie avant le secteur`() {
        val s = WallLogic.select(cams, emptySet(), "parking", forced = false, limit = 1, solarMin = 30)
        assertEquals(setOf("parking"), s.live)
    }

    @Test fun `solaire sous le seuil - reste en capture avec tout en direct`() {
        val low = listOf(Cam("balcon", Solar, battery = 25f))
        val s = WallLogic.select(low, emptySet(), null, forced = true, limit = 6, solarMin = 30)
        assertEquals(emptySet<String>(), s.live)
        assertEquals(setOf("balcon"), s.saving)
    }

    @Test fun `solaire sous le seuil - le mouvement l'emporte`() {
        val low = listOf(Cam("balcon", Solar, battery = 25f))
        val s = WallLogic.select(low, setOf("balcon"), null, forced = true, limit = 6, solarMin = 30)
        assertEquals(setOf("balcon"), s.live)
        assertEquals(emptySet<String>(), s.saving)
    }

    @Test fun `seuil a zero ou batterie inconnue - toujours en direct`() {
        val low = listOf(Cam("balcon", Solar, battery = 5f), Cam("jardin", Solar, battery = null))
        assertEquals(setOf("balcon", "jardin"), WallLogic.select(low, emptySet(), null, true, 6, solarMin = 0).live)
        assertEquals(setOf("jardin"), WallLogic.select(low, emptySet(), null, true, 6, solarMin = 30).live)
    }

    @Test fun `limite nulle ou negative - aucun direct sans planter`() {
        val s = WallLogic.select(cams, emptySet(), null, forced = false, limit = 0, solarMin = 30)
        assertEquals(emptySet<String>(), s.live)
        assertEquals(setOf("entree", "salon"), WallLogic.select(cams, emptySet(), null, false, -3, 30).capped)
    }

    // --- Grille -----------------------------------------------------------------------------------

    @Test fun `colonnes de la grille sur un ecran 16-10`() {
        val w = 1844f; val h = 1200f
        assertEquals(1, WallLogic.bestColumns(1, w, h))
        // Deux cameras l'une sous l'autre : cases plus grandes que cote a cote.
        assertEquals(1, WallLogic.bestColumns(2, w, h))
        assertEquals(2, WallLogic.bestColumns(4, w, h))
        assertEquals(2, WallLogic.bestColumns(6, w, h))
        assertEquals(3, WallLogic.bestColumns(9, w, h))
    }

    @Test fun `ecran en hauteur - une seule colonne pour deux cameras`() {
        assertEquals(1, WallLogic.bestColumns(2, 800f, 1280f))
    }
}
