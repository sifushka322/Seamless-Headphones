package app.systemresponse

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File

class WireTest {
    private val key = ByteArray(32) { it.toByte() }
    private val session = "00112233-4455-4677-8899-AABBCCDDEEFF"
    @Test fun fragmentedUnicodeRoundTrip() {
        val sender = SecureWire(key, session, "mac")
        val receiver = SecureWire(key, session, "android")
        val packet = Packet("prepare", "tx", "android", "Наушники 🎧")
        val buffer = FrameBuffer()
        val frames = sender.encode(packet).flatMap { buffer.append(byteArrayOf(it)) }
        assertEquals(1, frames.size); assertEquals(packet, receiver.decode(frames[0]))
        assertThrows(IllegalArgumentException::class.java) { receiver.decode(frames[0]) }
    }
    @Test fun rejectsWrongKeySessionAndDirection() {
        val frame = String(SecureWire(key, session, "mac").encode(Packet("release", "tx"))).trim()
        assertThrows(IllegalArgumentException::class.java) { SecureWire(ByteArray(32), session, "android").decode(frame) }
        assertThrows(IllegalArgumentException::class.java) { SecureWire(key, "new", "android").decode(frame) }
        assertThrows(IllegalArgumentException::class.java) { SecureWire(key, session, "mac").decode(frame) }
        assertThrows(IllegalArgumentException::class.java) { SecureWire(key, session, "android").decode(frame.replace("|1|", "|2|")) }
    }
    @Test fun frameBoundsAndCoalescing() {
        val buffer = FrameBuffer()
        assertThrows(IllegalStateException::class.java) { buffer.append(ByteArray(4097) { 65 }) }
        assertEquals(listOf("one", "two"), FrameBuffer().append("one\ntwo\n".toByteArray()))
    }
    @Test fun crossLanguageGoldenVectors() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }.first { File(it, "protocol/vectors.json").exists() }
        val data = JSONObject(File(root, "protocol/vectors.json").readText())
        val vectors = data.getJSONArray("vectors")
        for (i in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(i)
            val role = if (vector.getString("role") == "mac") "android" else "mac"
            val packet = SecureWire(key, session, role).decode(vector.getString("frame"))
            assertEquals("Готово", packet.detail); assertEquals("test-transaction", packet.id)
        }
    }
}
