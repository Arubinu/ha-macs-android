package fr.pergens.hamacs

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Sous-tension de l'alimentation, quand l'appareil l'expose (capteur hwmon du noyau, ex. `rpi_volt` des cartes
 * Raspberry Pi : `in0_lcrit_alarm` = 1 tant que la tension est trop basse). Une alimentation trop faible provoque
 * des pertes du signal HDMI, des ralentissements ou des plantages : l'app le signale (pastille sur le tableau de
 * bord, journal de Réglages › À propos) pour que l'utilisateur sache d'où vient le problème.
 */
object PowerSupply {
    private const val TAG = "PowerSupply"
    /** Capteurs connus (nom du dossier hwmon) dont l'alarme `in0_lcrit_alarm` signale une sous-tension. */
    private val SENSORS = setOf("rpi_volt")

    private val alarm: File? by lazy {
        runCatching {
            File("/sys/class/hwmon").listFiles()?.firstNotNullOfOrNull { dir ->
                val name = runCatching { File(dir, "name").readText().trim() }.getOrNull()
                File(dir, "in0_lcrit_alarm").takeIf { name in SENSORS && runCatching { it.readText() }.isSuccess }
            }
        }.getOrNull()
    }

    private val _lastLow = MutableStateFlow<Long?>(null)
    /** Dernière sous-tension constatée (null = aucune depuis le démarrage, ou pas de capteur). */
    val lastLow: StateFlow<Long?> = _lastLow.asStateFlow()

    /** Relit le capteur toutes les 2 s (lecture d'un petit fichier, rien sans capteur). */
    fun start(context: Context, scope: CoroutineScope) {
        scope.launch {
            val file = alarm
            Log.i(TAG, "Capteur de sous-tension : ${file?.path ?: "aucun"}")
            if (file == null) return@launch
            while (true) {
                if (runCatching { file.readText().trim() == "1" }.getOrDefault(false)) {
                    _lastLow.value = System.currentTimeMillis()
                    CrashGuard.note(
                        context, "power:low", "Alimentation",
                        "Sous-tension détectée : l'alimentation ne fournit pas assez (signal HDMI perdu, ralentissements…). " +
                            "Utilisez une alimentation adaptée à l'appareil.",
                    )
                }
                delay(2_000)
            }
        }
    }
}
