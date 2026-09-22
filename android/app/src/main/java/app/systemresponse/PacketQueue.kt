package app.systemresponse

/** Coalesce unsent snapshots; encode only at dequeue to preserve authenticated ordering. */
class PacketQueue {
    private val commands = ArrayDeque<Packet>()
    private val snapshots = linkedMapOf<String, Packet>()
    val size get() = commands.size + snapshots.size
    fun add(packet: Packet): Boolean {
        if (packet.type in setOf("activity", "autoStatus", "ping", "pong")) snapshots[packet.type] = packet
        else { if (commands.size >= 32) return false; commands.addLast(packet) }
        return true
    }
    fun next(): Packet? {
        if (commands.isNotEmpty()) return commands.removeFirst()
        val key = snapshots.keys.firstOrNull() ?: return null
        return snapshots.remove(key)
    }
    fun clear() { commands.clear(); snapshots.clear() }
}
