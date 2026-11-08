package fr.pergens.hamacs.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Trames du protocole Wyoming (testées par `WyomingProtocolTest`) : une ligne JSON d'en-tête (`type`, `data` ou
 * `data_length`, `payload_length`), puis les données JSON et l'audio brut.
 */
internal object WyomingProtocol {
    const val VERSION = "1.5.4"
    /** Tailles annoncées plafonnées : une valeur énorme ferait allouer des Go et planter l'app. */
    const val MAX_DATA = 1_000_000
    const val MAX_PAYLOAD = 8_000_000
    private const val MAX_LINE = 1_000_000

    private val json = Json { ignoreUnknownKeys = true }

    /** En-tête puis audio d'un événement, prêts à écrire. */
    fun encode(type: String, data: JsonObject, payload: ByteArray?): ByteArray {
        val length = payload?.size ?: 0
        val header = buildJsonObject {
            put("type", type)
            put("version", VERSION)
            if (data.isNotEmpty()) put("data", data)
            if (length > 0) put("payload_length", length)
        }.toString() + "\n"
        return if (length > 0) header.toByteArray() + payload!! else header.toByteArray()
    }

    /**
     * Événement suivant ; null si le flux est fermé, tronqué ou annonce une taille démesurée. Une ligne vide ou
     * illisible donne un événement sans type (ignoré).
     */
    fun read(input: InputStream): WyomingSatellite.Event? {
        val line = readLine(input) ?: return null
        val empty = WyomingSatellite.Event("", JsonObject(emptyMap()), null)
        if (line.isBlank()) return empty
        val head = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return empty
        var data = (head["data"] as? JsonObject) ?: JsonObject(emptyMap())
        val dataLength = (head["data_length"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        if (dataLength > MAX_DATA) return null
        if (dataLength > 0) {
            val extra = readExactly(input, dataLength) ?: return null
            runCatching { json.parseToJsonElement(extra.toString(Charsets.UTF_8)).jsonObject }.getOrNull()?.let { data = JsonObject(data + it) }
        }
        val payloadLength = (head["payload_length"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        if (payloadLength > MAX_PAYLOAD) return null
        val payload = if (payloadLength > 0) readExactly(input, payloadLength) ?: return null else null
        return WyomingSatellite.Event((head["type"] as? JsonPrimitive)?.content ?: "", data, payload)
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray? {
        val b = ByteArray(n)
        var read = 0
        while (read < n) {
            val k = input.read(b, read, n - read)
            if (k < 0) return null
            read += k
        }
        return b
    }

    private fun readLine(input: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return null
            if (c == '\n'.code) return b.toString("UTF-8")
            b.write(c)
            if (b.size() > MAX_LINE) return null
        }
    }
}
