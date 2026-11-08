package fr.pergens.hamacs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Fichiers du dépôt : chaînes des 8 langues, agencement d'exemple. Lancé depuis le dossier `app/`. */
class ResourcesTest {
    private val res = File("src/main/res")
    private val langs = listOf("", "-fr", "-de", "-es", "-it", "-nl", "-pt", "-pl")

    /** Nom → texte des chaînes d'une langue (tableaux et pluriels compris, par élément). */
    private fun strings(lang: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "values$lang/strings.xml"))
        val out = LinkedHashMap<String, String>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i) as? org.w3c.dom.Element ?: continue
            if (n.getAttribute("translatable") == "false") continue
            val name = n.getAttribute("name")
            when (n.tagName) {
                "string" -> out[name] = n.textContent
                // Pluriels : formes propres a chaque langue (few / many en polonais), seul le nom compte.
                "plurals" -> out["$name[]"] = n.textContent
                "string-array" -> {
                    val items = n.getElementsByTagName("item")
                    for (j in 0 until items.length) {
                        val it = items.item(j) as org.w3c.dom.Element
                        out["$name[${it.getAttribute("quantity").ifEmpty { j.toString() }}]"] = it.textContent
                    }
                }
            }
        }
        return out
    }

    @Test fun `memes chaines dans les 8 langues`() {
        // Nom de l'app : non traduit (repli sur values).
        val base = strings("").keys - "app_name"
        val errors = langs.drop(1).flatMap { l ->
            val keys = strings(l).keys
            (base - keys).map { "values$l : manque $it" } + (keys - base).map { "values$l : en trop $it" }
        }
        assertTrue(errors.joinToString("\n"), errors.isEmpty())
    }

    private val placeholder = Regex("""%(\d+\$)?[sdf]""")

    @Test fun `memes parametres dans chaque traduction`() {
        val base = strings("")
        val errors = langs.drop(1).flatMap { l ->
            strings(l).mapNotNull { (k, v) ->
                val want = base[k]?.let { b -> placeholder.findAll(b).map { it.value }.sorted().toList() } ?: return@mapNotNull null
                // Les pluriels peuvent omettre le nombre (« un », « eins »…).
                if (k.contains("[")) return@mapNotNull null
                val got = placeholder.findAll(v).map { it.value }.sorted().toList()
                if (got != want) "values$l : $k a $got au lieu de $want" else null
            }
        }
        assertTrue(errors.joinToString("\n"), errors.isEmpty())
    }

    @Test fun `pourcentage seul echappe dans les chaines a parametres`() {
        // Une chaîne formatée avec un % non échappé plante à l'affichage (IllegalFormatException).
        val errors = langs.flatMap { l ->
            strings(l).filter { (_, v) -> placeholder.containsMatchIn(v) && Regex("%(?![0-9sdf%])").containsMatchIn(v.replace("%%", "")) }
                .keys.map { "values$l : $it" }
        }
        assertTrue(errors.joinToString("\n"), errors.isEmpty())
    }

    private val dashboard: JsonObject by lazy {
        Json.parseToJsonElement(File("src/main/assets/dashboard.json").readText()) as JsonObject
    }

    private fun walk(e: JsonElement, f: (JsonObject) -> Unit) {
        when (e) {
            is JsonObject -> { f(e); e.values.forEach { walk(it, f) } }
            is JsonArray -> e.forEach { walk(it, f) }
            else -> {}
        }
    }

    @Test fun `agencement d'exemple - cartes typees, cameras avec entite`() {
        val errors = mutableListOf<String>()
        walk(dashboard) { o ->
            val type = (o["type"] as? JsonPrimitive)?.content
            if (type == "camera" && o["entity"] == null) errors += "caméra sans entité : $o"
        }
        assertTrue(errors.joinToString("\n"), errors.isEmpty())
        assertTrue("aucun écran", dashboard.isNotEmpty())
    }

    @Test fun `agencement d'exemple - aucun jeton ni secret`() {
        val text = File("src/main/assets/dashboard.json").readText()
        listOf("ha_token", "eyJ", "Bearer ", "password").forEach { assertTrue("« $it » trouvé dans dashboard.json", it !in text) }
    }

    @Test fun `fins de ligne LF dans les sources`() {
        val bad = File("src").walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "xml", "json") && '\r' in it.readText() }
            .map { it.path }.toList()
        assertEquals(emptyList<String>(), bad)
    }
}
