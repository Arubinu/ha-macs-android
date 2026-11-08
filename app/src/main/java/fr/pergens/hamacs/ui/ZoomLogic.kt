package fr.pergens.hamacs.ui

/**
 * Zoom d'une vidéo plein écran (pincer, glisser, double toucher). Image affichée = contenu agrandi de [Zoom.scale]
 * autour du centre de la vue puis décalé de ([Zoom.x], [Zoom.y]) pixels. Calculs purs (testés sans Android).
 */
internal object ZoomLogic {
    const val MAX = 5f
    const val DOUBLE_TAP = 2.5f

    data class Zoom(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
        val zoomed get() = scale > 1.01f
    }

    /** Décalage borné : le contenu agrandi couvre toujours toute la vue (aucun bord noir en se déplaçant). */
    fun clamp(z: Zoom, w: Float, h: Float): Zoom {
        val s = z.scale.coerceIn(1f, MAX)
        val mx = (s - 1f) * w / 2f
        val my = (s - 1f) * h / 2f
        return Zoom(s, z.x.coerceIn(-mx, mx), z.y.coerceIn(-my, my))
    }

    /**
     * Pincement de facteur [factor] autour de ([cx], [cy]) (point qui reste sous les doigts) puis glissement
     * ([px], [py]), dans une vue [w] × [h].
     */
    fun transform(z: Zoom, cx: Float, cy: Float, factor: Float, px: Float, py: Float, w: Float, h: Float): Zoom {
        val s = (z.scale * factor).coerceIn(1f, MAX)
        val k = s / z.scale
        val dx = cx - w / 2f
        val dy = cy - h / 2f
        return clamp(Zoom(s, dx - (dx - z.x) * k + px, dy - (dy - z.y) * k + py), w, h)
    }

    /** Double toucher : zoom ×[DOUBLE_TAP] centré sur le point touché, ou retour à la taille normale. */
    fun doubleTap(z: Zoom, tx: Float, ty: Float, w: Float, h: Float): Zoom =
        if (z.zoomed) Zoom() else transform(Zoom(), tx, ty, DOUBLE_TAP, 0f, 0f, w, h)
}
