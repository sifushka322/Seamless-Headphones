import Foundation
import CryptoKit

enum WireError: Error { case invalid, oversized, authentication, replay }

struct Packet: Codable, Equatable {
    var type: String
    var id: String = ""
    var target: String = ""
    var detail: String = ""
    var device: String = ""
}

/// ASCII newline framing remains valid at the minimum ATT payload of 20 bytes.
struct FrameBuffer {
    private var bytes = Data()
    mutating func append(_ chunk: Data) throws -> [String] {
        var frames = [String]()
        for byte in chunk {
            if byte == 10 {
                guard let frame = String(data: bytes, encoding: .utf8) else { bytes.removeAll(); throw WireError.invalid }
                frames.append(frame); bytes.removeAll()
            } else {
                bytes.append(byte)
                if bytes.count > 4096 { bytes.removeAll(); throw WireError.oversized }
            }
        }
        return frames
    }
}

final class SecureWire {
    let session: String
    private let key: SymmetricKey
    private let role: String
    private var outgoing: UInt64 = 0
    private var incoming: UInt64 = 0
    init(secret: Data, session: String, role: String) {
        precondition(secret.count == 32)
        key = SymmetricKey(data: secret); self.session = session; self.role = role
    }
    func encode(_ packet: Packet) throws -> Data {
        outgoing += 1
        let payload = try JSONEncoder().encode(packet).base64EncodedString()
        let body = "1|\(session)|\(role)|\(outgoing)|\(payload)"
        let signature = Data(HMAC<SHA256>.authenticationCode(for: Data(body.utf8), using: key)).base64EncodedString()
        let frame = Data("\(body)|\(signature)\n".utf8)
        guard frame.count <= 4096 else { throw WireError.oversized }
        return frame
    }
    func decode(_ frame: String) throws -> Packet {
        guard frame.utf8.count <= 4096 else { throw WireError.oversized }
        let parts = frame.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 6, parts[0] == "1", parts[1] == session,
              parts[2] == (role == "mac" ? "android" : "mac"),
              let sequence = UInt64(parts[3]), sequence > incoming,
              let payload = Data(base64Encoded: parts[4]), let signature = Data(base64Encoded: parts[5])
        else { throw WireError.invalid }
        let body = Data(parts.prefix(5).joined(separator: "|").utf8)
        guard HMAC<SHA256>.isValidAuthenticationCode(signature, authenticating: body, using: key) else { throw WireError.authentication }
        let packet = try JSONDecoder().decode(Packet.self, from: payload)
        incoming = sequence
        return packet
    }
}

enum HandoffMode: String, CaseIterable {
    case sequential, parallel, receiverFirst
    var title: String { switch self {
        case .sequential: return "Сначала отключить"
        case .parallel: return "Параллельно"
        case .receiverFirst: return "Получатель первым"
    } }
}
enum Stage: String { case preparing, releasing, acquiring, complete }
enum HandoffAction: Equatable { case release, acquire, tryAcquire, complete }

/// Effects are issued once. Release and acquisition may finish in either order.
struct Transfer {
    let id: String
    let target: String
    let mode: HandoffMode
    var stage: Stage = .preparing
    let started: Date
    let clockStarted = ProcessInfo.processInfo.systemUptime
    private(set) var actions: [HandoffAction] = []
    private(set) var releaseStarted = false
    private(set) var releaseDone = false
    private(set) var acquireStarted = false
    private(set) var acquireDone = false
    private(set) var fallback = false
    private(set) var acquisitionDetail = ""
    var earlyActive: Bool { mode == .receiverFirst && acquireStarted && !fallback && stage != .complete }
    var elapsed: Double { ProcessInfo.processInfo.systemUptime - clockStarted }
    init(target: String, now: Date = Date(), id: String = UUID().uuidString, mode: HandoffMode = .sequential) {
        self.id = id; self.target = target; started = now; self.mode = mode
    }
    mutating func accept(_ packet: Packet) -> Bool {
        actions = []
        guard packet.id == id, stage != .complete else { return false }
        switch packet.type {
        case "ready":
            guard stage == .preparing else { return false }
            if mode == .sequential { releaseStarted = true; stage = .releasing; actions = [.release] }
            else if mode == .parallel {
                releaseStarted = true; acquireStarted = true; stage = .acquiring; actions = [.release, .acquire]
            } else { acquireStarted = true; stage = .acquiring; actions = [.tryAcquire] }
        case "released":
            guard releaseStarted, !releaseDone else { return false }
            releaseDone = true
            if !acquireStarted { acquireStarted = true; stage = .acquiring; actions = [.acquire] }
            else if acquireDone { stage = .complete; actions = [.complete] }
        case "result", "earlyResult":
            guard acquireStarted, !acquireDone, (packet.type == "earlyResult") == earlyActive else { return false }
            acquireDone = true; acquisitionDetail = packet.detail
            if releaseDone || !releaseStarted { stage = .complete; actions = [.complete] }
        case "retryable":
            // Only a terminal, known failure can authorize a second connection attempt.
            guard earlyActive, !releaseStarted else { return false }
            fallback = true; acquireStarted = false; releaseStarted = true; stage = .releasing; actions = [.release]
        default: return false
        }
        return true
    }
    func expired(at now: Date) -> Bool { now.timeIntervalSince(started) >= 35 }
}
