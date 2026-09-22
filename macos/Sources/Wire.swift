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

enum Stage: String { case preparing, releasing, acquiring }

/// The Mac serializes all transactions; stale, duplicate and out-of-order replies do nothing.
struct Transfer {
    let id: String
    let target: String
    var stage: Stage = .preparing
    let started: Date
    init(target: String, now: Date = Date(), id: String = UUID().uuidString) {
        self.id = id; self.target = target; started = now
    }
    mutating func accept(_ packet: Packet) -> Bool {
        guard packet.id == id else { return false }
        switch (stage, packet.type) {
        case (.preparing, "ready"): stage = .releasing; return true
        case (.releasing, "released"): stage = .acquiring; return true
        case (.acquiring, "result"): return true
        default: return false
        }
    }
    func expired(at now: Date) -> Bool { now.timeIntervalSince(started) >= 35 }
}
