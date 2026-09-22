package app.systemresponse

import org.json.JSONObject

data class ActivityState(val available: Boolean = false, val playing: Boolean = false, val call: Boolean = false,
    val held: Boolean = false, val automation: Boolean = false, val event: Int = 0, val source: String = "", val connected: Boolean = false, val guardReason: String? = null, val handoffVersion: Int? = null) {
    fun json(): String = JSONObject().put("available", available).put("playing", playing).put("call", call).put("held", held)
        .put("automation", automation).put("event", event).put("source", source).put("connected", connected).put("guardReason", guardReason ?: JSONObject.NULL).put("handoffVersion", handoffVersion ?: JSONObject.NULL).toString()
    companion object {
        fun parse(text: String): ActivityState {
            require(text.length < 1600)
            val o = JSONObject(text)
            return ActivityState(o.getBoolean("available"), o.getBoolean("playing"), o.getBoolean("call"), o.getBoolean("held"),
                o.getBoolean("automation"), o.getInt("event"), o.getString("source"), o.getBoolean("connected"), if (o.isNull("guardReason")) null else o.optString("guardReason").take(180), if (o.isNull("handoffVersion")) null else o.getInt("handoffVersion")).also {
                require(it.event >= 0 && it.source.length < 200)
            }
        }
    }
}

class MediaEdges {
    private var previous: Set<String>? = null
    private val pending = mutableMapOf<String, Long>()
    var event = 0; private set
    var source = ""; private set
    fun nextDeadline(delay: Long): Long? = pending.values.minOrNull()?.plus(delay)
    fun reset() { previous = null; pending.clear() }
    fun sample(active: Set<String>, now: Long, delay: Long, suppress: Boolean) {
        val before = previous; previous = active.toSet()
        if (before == null || suppress) { pending.clear(); return }
        (active - before).forEach { pending[it] = now }
        pending.keys.retainAll(active)
        val matured = pending.filter { now - it.value >= delay }.keys.sorted()
        if (matured.isNotEmpty()) { event++; source = matured.first(); matured.forEach { pending.remove(it) } }
    }
}
