package app.systemresponse

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TransportTest {
    @Test fun snapshotsDoNotDelayControlOrBreakAuthentication() {
        val queue = PacketQueue()
        repeat(100) { assertTrue(queue.add(Packet("activity", detail = "state-$it"))) }
        assertEquals(1, queue.size)
        queue.add(Packet("request", id = "handoff"))
        val sender = SecureWire(ByteArray(32), "test", "android")
        val receiver = SecureWire(ByteArray(32), "test", "mac")
        val control = queue.next()!!
        assertEquals("request", control.type)
        assertEquals(control, receiver.decode(String(sender.encode(control)).trim()))
        val latest = queue.next()!!
        assertEquals("state-99", latest.detail)
        assertEquals(latest, receiver.decode(String(sender.encode(latest)).trim()))
        assertNull(queue.next())
    }
    @Test fun commandQueueIsBoundedAndClearedOnReconnect() {
        val queue = PacketQueue()
        repeat(32) { assertTrue(queue.add(Packet("request", id = "$it"))) }
        assertFalse(queue.add(Packet("request")))
        queue.clear(); assertEquals(0, queue.size); assertNull(queue.next())
    }
    private fun vector(): String {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "protocol/pairing-vector.json").exists() }
        return File(root, "protocol/pairing-vector.json").readText()
    }
    @Test fun pairingAcceptsMacVectorWithoutExposingSecret() {
        val code = PairingCode.parse(vector())
        assertEquals("00-11-22-33-44-55", code.device)
        assertEquals("Buds • тест", code.name)
        assertEquals(32, java.util.Base64.getDecoder().decode(code.key).size)
        assertFalse(code.toString().contains(code.key))
    }
    @Test fun pairingRejectsForeignUnsupportedAndMalformedCodes() {
        for (raw in listOf(vector().replace("seamless-headphones", "other"), vector().replace("\"version\":1", "\"version\":2"),
            vector().replace("00-11-22-33-44-55", "not-an-address"), vector().replace("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=", "invalid"), "x".repeat(2049))) {
            assertThrows(IllegalArgumentException::class.java) { PairingCode.parse(raw) }
        }
    }
}
