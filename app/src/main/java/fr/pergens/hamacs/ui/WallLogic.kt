package fr.pergens.hamacs.ui

import kotlin.math.ceil
import kotlin.math.min

/**
 * Calculs du mur de caméras sans Android ni Compose (testés par `WallLogicTest`) : alimentation devinée, choix des
 * directs, colonnes de la grille.
 */
internal object WallLogic {
    val FULL = setOf("chargecomplete", "charge_complete", "charged", "full", "plugged")
    val CHARGING = setOf("charging", "on")
    val DISCHARGING = setOf("discharging", "not_charging", "notcharging", "off", "unplugged")

    /**
     * Alimentation d'après l'état de charge actuel [now] et ceux vus sur 24 h ([seen], null tant que l'historique n'est
     * pas arrivé) : jamais en décharge → secteur ; charge et décharge → solaire ; décharge seule → batterie.
     */
    fun classify(now: String, seen: Set<String>?): CameraPower {
        val cur = now.lowercase()
        if (seen == null) return when (cur) {
            in FULL -> CameraPower.Mains
            in CHARGING -> CameraPower.Solar
            else -> CameraPower.Battery
        }
        val all = seen + cur
        val discharges = all.any { it in DISCHARGING }
        val charges = all.any { it in CHARGING || it in FULL }
        return when {
            !discharges -> CameraPower.Mains
            charges -> CameraPower.Solar
            else -> CameraPower.Battery
        }
    }

    /** Caméra candidate au direct : alimentation et niveau de batterie (%, null si inconnu). */
    data class Cam(val id: String, val power: CameraPower, val battery: Float? = null)

    /**
     * [live] : caméras en direct ; [capped] : en capture faute de place ; [saving] : laissées en capture par
     * « Tout en direct » pour ménager leur batterie (solaire sous le seuil).
     */
    data class Selection(val live: Set<String>, val capped: Set<String>, val saving: Set<String>)

    /**
     * Directs du mur, par priorité : mouvement, caméra agrandie, secteur, puis « Tout en direct » ([forced]) pour les
     * caméras solaires (sauf sous [solarMin] % de batterie, 0 = toujours) ; jamais forcé sur batterie. Au plus [limit].
     */
    fun select(cams: List<Cam>, moving: Set<String>, focus: String?, forced: Boolean, limit: Int, solarMin: Int): Selection {
        val saving = mutableSetOf<String>()
        val ranked = cams.mapNotNull { c ->
            val prio = when {
                c.id in moving -> 0
                c.id == focus -> 1
                // Secteur avant « tout en direct » : une caméra sur secteur ne cède jamais sa place à une autre forcée.
                c.power == CameraPower.Mains -> 2
                forced && c.power == CameraPower.Solar && solarMin > 0 && c.battery != null && c.battery < solarMin -> {
                    saving += c.id
                    null
                }
                forced && c.power != CameraPower.Battery -> 3
                else -> null
            }
            prio?.let { c.id to it }
        }
        val sorted = ranked.sortedBy { it.second }.map { it.first }
        val n = limit.coerceAtLeast(0)
        return Selection(sorted.take(n).toSet(), sorted.drop(n).toSet(), saving)
    }

    /** Colonnes de la grille qui donnent les plus grandes cases 16:9 pour [n] caméras dans [w] × [h]. */
    fun bestColumns(n: Int, w: Float, h: Float): Int {
        var best = 1
        var bestArea = 0f
        for (c in 1..n) {
            val r = ceil(n / c.toFloat())
            val tileW = min(w / c, h / r * 16f / 9f)
            val area = tileW * tileW * 9f / 16f
            if (area > bestArea + 1f) { bestArea = area; best = c }
        }
        return best
    }
}
