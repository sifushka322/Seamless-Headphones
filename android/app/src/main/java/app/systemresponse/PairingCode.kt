package app.systemresponse

import org.json.JSONObject
import java.util.Base64

// Deliberately not a data class: never include the secret in generated toString/debug output.
class PairingCode(val key: String, val device: String, val name: String) {
    companion object {
        fun parse(raw: String): PairingCode {
            require(raw.length <= 2048)
            val json = JSONObject(raw)
            require(json.getString("app") == "seamless-headphones" && json.getInt("version") == 1)
            val key = json.getString("key")
            require(key.length == 44 && Base64.getDecoder().decode(key).size == 32)
            val device = json.optString("device")
            require(device.isEmpty() || Regex("[0-9A-Fa-f]{2}([:-][0-9A-Fa-f]{2}){5}").matches(device))
            val name = json.optString("name").take(120).filter { !it.isISOControl() }
            return PairingCode(key, device, name)
        }
    }
}
