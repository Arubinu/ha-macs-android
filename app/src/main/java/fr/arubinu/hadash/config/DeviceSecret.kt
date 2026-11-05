package fr.arubinu.hadash.config

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Chiffrement lié à l'appareil, pour le jeton Home Assistant de l'export : AES-GCM avec une clé tirée de
 * l'identifiant Android de l'app sur cet appareil (`ANDROID_ID`, propre à l'appareil, à l'utilisateur et à la clé de
 * signature de l'app). Le fichier exporté ne redonne le jeton que sur l'appareil qui l'a produit ; ailleurs, le jeton
 * est demandé. L'identifiant change après une réinitialisation de l'appareil.
 */
object DeviceSecret {
    private const val PREFIX = "enc:v1:"

    fun isEncrypted(value: String) = value.startsWith(PREFIX)

    @SuppressLint("HardwareIds")
    private fun key(context: Context): SecretKeySpec {
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        val bytes = MessageDigest.getInstance("SHA-256").digest("hadash-token:${context.packageName}:$id".toByteArray())
        return SecretKeySpec(bytes, "AES")
    }

    fun encrypt(context: Context, plain: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(context), GCMParameterSpec(128, iv)) }
        val data = iv + cipher.doFinal(plain.toByteArray())
        return PREFIX + Base64.encodeToString(data, Base64.NO_WRAP)
    }

    /** Valeur en clair, ou null si elle a été chiffrée sur un autre appareil (ou est illisible). */
    fun decrypt(context: Context, value: String): String? = runCatching {
        val data = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(context), GCMParameterSpec(128, data, 0, 12))
        }
        String(cipher.doFinal(data, 12, data.size - 12))
    }.getOrNull()
}
