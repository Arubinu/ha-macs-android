package fr.pergens.hamacs

import fr.pergens.hamacs.voice.WyomingProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/** Trames du satellite vocal Wyoming (Home Assistant ↔ tablette). */
class WyomingProtocolTest {
    private fun input(vararg parts: ByteArray) = ByteArrayInputStream(parts.fold(ByteArray(0)) { a, b -> a + b })

    @Test fun `aller-retour avec donnees et audio`() {
        val pcm = ByteArray(320) { it.toByte() }
        val data = JsonObject(mapOf("rate" to JsonPrimitive(16000)))
        val frame = WyomingProtocol.encode("audio-chunk", data, pcm)
        val ev = WyomingProtocol.read(input(frame))!!
        assertEquals("audio-chunk", ev.type)
        assertEquals("16000", (ev.data["rate"] as JsonPrimitive).content)
        assertArrayEquals(pcm, ev.payload)
    }

    @Test fun `en-tete d'un evenement sans audio`() {
        val frame = WyomingProtocol.encode("ping", JsonObject(emptyMap()), null)
        val line = String(frame).trimEnd('\n')
        val head = Json.parseToJsonElement(line).jsonObject
        assertEquals("ping", (head["type"] as JsonPrimitive).content)
        assertEquals(WyomingProtocol.VERSION, (head["version"] as JsonPrimitive).content)
        assertFalse(head.containsKey("payload_length"))
        assertFalse(head.containsKey("data"))
    }

    @Test fun `donnees a part (data_length) fusionnees`() {
        val extra = """{"text":"Bonjour"}""".toByteArray()
        val ev = WyomingProtocol.read(input("""{"type":"transcript","data":{"a":1},"data_length":${extra.size}}""".toByteArray() + '\n'.code.toByte(), extra))!!
        assertEquals("Bonjour", (ev.data["text"] as JsonPrimitive).content)
        assertEquals("1", (ev.data["a"] as JsonPrimitive).content)
    }

    @Test fun `plusieurs evenements a la suite`() {
        val s = input(WyomingProtocol.encode("a", JsonObject(emptyMap()), byteArrayOf(1, 2)), WyomingProtocol.encode("b", JsonObject(emptyMap()), null))
        assertEquals("a", WyomingProtocol.read(s)!!.type)
        assertEquals("b", WyomingProtocol.read(s)!!.type)
        assertNull(WyomingProtocol.read(s))
    }

    @Test fun `ligne vide ou illisible - evenement sans type`() {
        assertEquals("", WyomingProtocol.read(input("\n".toByteArray()))!!.type)
        assertEquals("", WyomingProtocol.read(input("pas du json\n".toByteArray()))!!.type)
    }

    @Test fun `flux tronque - null`() {
        assertNull(WyomingProtocol.read(input("""{"type":"x","payload_length":10}""".toByteArray() + 10, byteArrayOf(1, 2, 3))))
        assertNull(WyomingProtocol.read(input("""{"type":"x"""".toByteArray())))
    }

    @Test fun `taille annoncee demesuree - refusee sans allouer`() {
        assertNull(WyomingProtocol.read(input("""{"type":"x","payload_length":2000000000}""".toByteArray() + 10)))
        assertNull(WyomingProtocol.read(input("""{"type":"x","data_length":2000000000}""".toByteArray() + 10)))
    }

    @Test fun `taille negative ignoree`() {
        val ev = WyomingProtocol.read(input("""{"type":"x","payload_length":-5}""".toByteArray() + 10))
        assertNotNull(ev)
        assertNull(ev!!.payload)
    }
}
