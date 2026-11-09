package fr.pergens.hamacs

import fr.pergens.hamacs.update.Updater
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Choix de la Release et de l'APK pour la mise à jour (réponse de l'API GitHub). */
class UpdaterTest {
    private fun release(tag: String, prerelease: Boolean = false, draft: Boolean = false, abis: List<String> = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86", "universal")) =
        """{"tag_name":"$tag","prerelease":$prerelease,"draft":$draft,"body":"Notes $tag","assets":[""" +
            abis.joinToString(",") { """{"name":"ha-macs-android-$tag-$it.apk","browser_download_url":"https://x/$tag/$it.apk","size":${if (it == "universal") 120 else 44}}""" } +
            "]}"

    private fun list(vararg r: String): List<JsonObject> = Json.parseToJsonElement("[${r.joinToString(",")}]").jsonArray.map { it.jsonObject }

    @Test fun `APK de l'architecture de la tablette`() {
        val r = Updater.parseRelease(list(release("v1.0.2"))[0], listOf("arm64-v8a", "armeabi-v7a"))!!
        assertEquals("1.0.2", r.version)
        assertEquals("https://x/v1.0.2/arm64-v8a.apk", r.apkUrl)
        assertEquals(44L, r.size)
        assertEquals("Notes v1.0.2", r.notes)
        assertFalse(r.prerelease)
    }

    @Test fun `architecture absente - APK universel`() {
        val r = Updater.parseRelease(list(release("v1.0.2", abis = listOf("universal")))[0], listOf("arm64-v8a"))!!
        assertEquals("https://x/v1.0.2/universal.apk", r.apkUrl)
    }

    @Test fun `aucun APK - release ignoree`() {
        assertNull(Updater.parseRelease(list(release("v1.0.2", abis = emptyList()))[0], listOf("arm64-v8a")))
    }

    @Test fun `plus recente stable, sans brouillon ni preversion`() {
        val l = list(release("v1.0.1"), release("v1.0.10"), release("v1.0.9"), release("v1.1.0-beta.1", prerelease = true), release("v2.0.0", draft = true))
        assertEquals("1.0.10", Updater.latestOf(l, prereleases = false, abis = listOf("arm64-v8a"))!!.version)
    }

    @Test fun `preversions acceptees si demande`() {
        val l = list(release("v1.0.1"), release("v1.1.0-beta.1", prerelease = true))
        val r = Updater.latestOf(l, prereleases = true, abis = listOf("arm64-v8a"))!!
        assertEquals("1.1.0-beta.1", r.version)
        assertTrue(r.prerelease)
    }

    /** Release avec les deux variantes : complète (`-<abi>.apk`) et lite (`-lite-<abi>.apk`). */
    private fun both(tag: String) =
        """{"tag_name":"$tag","prerelease":false,"draft":false,"body":"","assets":[""" +
            listOf("arm64-v8a", "universal", "lite-arm64-v8a", "lite-universal").joinToString(",") {
                """{"name":"ha-macs-android-$tag-$it.apk","browser_download_url":"https://x/$tag/$it.apk","size":1}"""
            } + "]}"

    @Test fun `variante complete - jamais un APK lite`() {
        val r = Updater.parseRelease(list(both("v1.2.0"))[0], listOf("arm64-v8a"), lite = false)!!
        assertEquals("https://x/v1.2.0/arm64-v8a.apk", r.apkUrl)
    }

    @Test fun `variante lite - son APK, sinon son universel`() {
        assertEquals("https://x/v1.2.0/lite-arm64-v8a.apk", Updater.parseRelease(list(both("v1.2.0"))[0], listOf("arm64-v8a"), lite = true)!!.apkUrl)
        assertEquals("https://x/v1.2.0/lite-universal.apk", Updater.parseRelease(list(both("v1.2.0"))[0], listOf("x86"), lite = true)!!.apkUrl)
    }

    @Test fun `variante lite - release sans APK lite ignoree`() {
        val l = list(release("v1.1.0"), both("v1.2.0"))
        assertEquals("1.2.0", Updater.latestOf(l, prereleases = false, abis = listOf("arm64-v8a"), lite = true)!!.version)
        assertNull(Updater.parseRelease(list(release("v1.1.0"))[0], listOf("arm64-v8a"), lite = true))
    }

    @Test fun `liste vide - rien`() {
        assertNull(Updater.latestOf(emptyList(), prereleases = true, abis = listOf("arm64-v8a")))
    }
}
