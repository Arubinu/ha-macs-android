package fr.pergens.hamacs.ui

import android.content.Context
import androidx.media3.common.PlaybackException
import fr.pergens.hamacs.App
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Flux et caméras que la tablette ne sait pas décoder (ex. 4K H.265 sur Galaxy Tab A8), mémorisés pour ne plus perdre
 * de temps à les essayer : plein écran ouvert directement sur le flux de la carte, capture signalée tout de suite.
 * Propre à l'appareil (prefs `video`, non exportées) ; réinitialisable dans Réglages › Affichage.
 */
object VideoCompat {
    private val prefs by lazy { App.instance.getSharedPreferences("video", Context.MODE_PRIVATE) }
    private val _unplayable by lazy { MutableStateFlow(prefs.getStringSet("unplayable", emptySet()).orEmpty().toSet()) }
    val unplayable: StateFlow<Set<String>> get() = _unplayable

    fun isUnplayable(key: String) = key in _unplayable.value

    fun mark(key: String) {
        if (isUnplayable(key)) return
        val set = _unplayable.value + key
        prefs.edit().putStringSet("unplayable", set).apply()
        _unplayable.value = set
    }

    fun reset() {
        prefs.edit().remove("unplayable").apply()
        _unplayable.value = emptySet()
    }

    /** Erreur due au décodeur (format non pris en charge), pas au réseau ni au flux. */
    fun isDecoderError(e: PlaybackException) = e.errorCode in setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    )
}
