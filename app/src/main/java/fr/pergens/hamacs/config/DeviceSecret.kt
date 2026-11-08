package fr.pergens.hamacs.config

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.Base64
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
    private fun seed(context: Context): String =
        "hadash-token:${context.packageName}:" + Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()

    fun encrypt(context: Context, plain: String): String = encryptWith(seed(context), plain)

    fun decrypt(context: Context, value: String): String? = decryptWith(seed(context), value)

    private fun key(seed: String) = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8)), "AES")

    /** Chiffre avec la cle tiree de [seed] (paquet + ANDROID_ID sur l'appareil). */
    internal fun encryptWith(seed: String, plain: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(seed), GCMParameterSpec(128, iv)) }
        val data = iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(data)
    }

    /** Valeur en clair, ou null si elle a ete chiffree avec une autre cle (autre appareil) ou est illisible. */
    internal fun decryptWith(seed: String, value: String): String? = runCatching {
        val data = Base64.getDecoder().decode(value.removePrefix(PREFIX))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(seed), GCMParameterSpec(128, data, 0, 12))
        }
        String(cipher.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
    }.getOrNull()
}
