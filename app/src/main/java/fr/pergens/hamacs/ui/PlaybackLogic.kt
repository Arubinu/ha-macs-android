package fr.pergens.hamacs.ui

/** Calculs de la barre de lecture des enregistrements (sans Android, testés). */
object PlaybackLogic {
    /** Durée lisible : « 0:07 », « 12:34 », « 1:02:03 » (négatif ou inconnu : « 0:00 »). */
    fun clock(ms: Long): String {
        val s = (ms.coerceAtLeast(0L) / 1000L)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** Part lue, entre 0 et 1 (0 tant que la durée est inconnue). */
    fun fraction(position: Long, duration: Long): Float =
        if (duration <= 0L) 0f else (position.toFloat() / duration).coerceIn(0f, 1f)

    /** Position visée par un toucher à `fraction` de la barre. */
    fun positionAt(fraction: Float, duration: Long): Long =
        if (duration <= 0L) 0L else (fraction.coerceIn(0f, 1f) * duration).toLong()

    /** Un enregistrement (durée connue, pas un direct) : la barre a un sens. */
    fun hasProgress(duration: Long, live: Boolean): Boolean = !live && duration > 0L
}
