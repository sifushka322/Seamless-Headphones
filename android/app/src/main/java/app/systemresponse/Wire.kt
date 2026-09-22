package app.systemresponse

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

data class Packet(val type: String, val id: String = "", val target: String = "", val detail: String = "", val device: String = "") {
    fun json(): ByteArray = JSONObject().put("type", type).put("id", id).put("target", target).put("detail", detail).put("device", device).toString().toByteArray(StandardCharsets.UTF_8)
    companion object {
        fun decode(data: ByteArray): Packet {
            val obj = JSONObject(String(data, StandardCharsets.UTF_8))
            return Packet(obj.getString("type"), obj.optString("id"), obj.optString("target"), obj.optString("detail"), obj.optString("device"))
        }
    }
}

class FrameBuffer {
    private val bytes = ByteArrayOutputStream()
    fun append(chunk: ByteArray): List<String> {
        val frames = mutableListOf<String>()
        for (b in chunk) {
            if (b == 10.toByte()) { frames.add(bytes.toString("UTF-8")); bytes.reset() }
            else { bytes.write(b.toInt()); if (bytes.size() > 4096) { bytes.reset(); error("Frame too large") } }
        }
        return frames
    }
}

class SecureWire(private val key: ByteArray, val session: String, private val role: String) {
    private var outgoing = 0L
    private var incoming = 0L
    init { require(key.size == 32) }
    private fun hmac(bytes: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(bytes) }
    fun encode(packet: Packet): ByteArray {
        val payload = Base64.getEncoder().encodeToString(packet.json())
        val body = "1|$session|$role|${++outgoing}|$payload"
        val signature = Base64.getEncoder().encodeToString(hmac(body.toByteArray(StandardCharsets.UTF_8)))
        return "$body|$signature\n".toByteArray(StandardCharsets.UTF_8).also { require(it.size <= 4096) }
    }
    fun decode(frame: String): Packet {
        require(frame.length <= 4096)
        val parts = frame.split('|')
        require(parts.size == 6 && parts[0] == "1" && parts[1] == session && parts[2] == if (role == "android") "mac" else "android")
        val seq = parts[3].toLong()
        require(seq > incoming)
        val body = parts.take(5).joinToString("|").toByteArray(StandardCharsets.UTF_8)
        require(MessageDigest.isEqual(hmac(body), Base64.getDecoder().decode(parts[5])))
        val packet = Packet.decode(Base64.getDecoder().decode(parts[4]))
        incoming = seq
        return packet
    }
}
