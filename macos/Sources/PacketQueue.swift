import Foundation

/// Coalesce only unsent snapshots. Authenticate at dequeue so sequence numbers stay ordered.
struct PacketQueue {
    private var commands: [Packet] = []
    private var snapshots: [Packet] = []
    var count: Int { commands.count + snapshots.count }
    mutating func append(_ packet: Packet) -> Bool {
        if ["activity", "autoStatus", "ping", "pong"].contains(packet.type) {
            snapshots.removeAll { $0.type == packet.type }
            snapshots.append(packet)
        } else {
            guard commands.count < 32 else { return false }
            commands.append(packet)
        }
        return true
    }
    mutating func next() -> Packet? {
        if !commands.isEmpty { return commands.removeFirst() }
        return snapshots.isEmpty ? nil : snapshots.removeFirst()
    }
    mutating func reset() { commands.removeAll(); snapshots.removeAll() }
}
