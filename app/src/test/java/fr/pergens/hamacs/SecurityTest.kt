package fr.pergens.hamacs

import fr.pergens.hamacs.config.DeviceSecret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chiffrement du jeton et des codes dans l'export (lié à l'appareil). */
class SecurityTest {
    private val device = "hadash-token:fr.pergens.hamacs:0123456789abcdef"
    private val other = "hadash-token:fr.pergens.hamacs:fedcba9876543210"

    @Test fun `aller-retour sur le meme appareil`() {
        val token = "eyJhbGciOiJIUzI1NiJ9.fake.token-éà"
        val enc = DeviceSecret.encryptWith(device, token)
        assertTrue(DeviceSecret.isEncrypted(enc))
        assertFalse(token in enc)
        assertEquals(token, DeviceSecret.decryptWith(device, enc))
    }

    @Test fun `autre appareil - illisible`() {
        assertNull(DeviceSecret.decryptWith(other, DeviceSecret.encryptWith(device, "1234")))
    }

    @Test fun `deux chiffrements differents (vecteur aleatoire)`() {
        assertNotEquals(DeviceSecret.encryptWith(device, "1234"), DeviceSecret.encryptWith(device, "1234"))
    }

    @Test fun `valeur alteree ou invalide - null sans planter`() {
        val enc = DeviceSecret.encryptWith(device, "1234")
        // Caractère du milieu (les derniers peuvent ne porter que des bits de remplissage base64 : rien ne change).
        val i = enc.length / 2
        val flipped = enc.substring(0, i) + (if (enc[i] == 'A') "B" else "A") + enc.substring(i + 1)
        assertNull(DeviceSecret.decryptWith(device, flipped))
        assertNull(DeviceSecret.decryptWith(device, "enc:v1:pas-du-base64!"))
        assertNull(DeviceSecret.decryptWith(device, "enc:v1:"))
    }

    @Test fun `texte en clair non reconnu comme chiffre`() {
        assertFalse(DeviceSecret.isEncrypted("1234"))
        assertFalse(DeviceSecret.isEncrypted("eyJhbGciOi"))
    }
}
