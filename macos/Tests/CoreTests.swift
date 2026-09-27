import Foundation
import CryptoKit

@main struct CoreTests {
    static func main() throws {
        var count = 0
        func check(_ value: @autoclosure () -> Bool, _ name: String) {
            guard value() else { fatalError("FAILED: \(name)") }; count += 1; print("PASS \(name)")
        }
        func rejects(_ name: String, _ action: () throws -> Void) {
            do { try action(); fatalError("FAILED: accepted \(name)") } catch { count += 1; print("PASS \(name)") }
        }
        let key = Data(0..<32)
        let session = "00112233-4455-4677-8899-AABBCCDDEEFF"
        let sender = SecureWire(secret: key, session: session, role: "android")
        let receiver = SecureWire(secret: key, session: session, role: "mac")
        let packet = Packet(type: "prepare", id: "123", target: "android", detail: "Наушники 🎧")
        let encoded = try sender.encode(packet)
        var buffer = FrameBuffer(); var frames = [String]()
        for byte in encoded { frames += try buffer.append(Data([byte])) }
        check(frames.count == 1, "one-byte fragmentation")
        let decoded = try receiver.decode(frames[0]); check(decoded == packet, "authenticated Unicode round trip")
        rejects("replay") { _ = try receiver.decode(frames[0]) }
        rejects("wrong shared key") { _ = try SecureWire(secret: Data(repeating: 9, count: 32), session: session, role: "mac").decode(frames[0]) }
        rejects("old session") { _ = try SecureWire(secret: key, session: "NEW", role: "mac").decode(frames[0]) }
        rejects("reflection") { _ = try sender.decode(frames[0]) }
        rejects("tamper") { _ = try SecureWire(secret: key, session: session, role: "mac").decode(frames[0].replacingOccurrences(of: "|1|", with: "|2|")) }
        func signed(sequence: String, payload: Data) -> String {
            let body = "1|\(session)|android|\(sequence)|\(payload.base64EncodedString())"
            let signature = Data(HMAC<SHA256>.authenticationCode(for: Data(body.utf8), using: SymmetricKey(data: key))).base64EncodedString()
            return "\(body)|\(signature)"
        }
        let validPayload = try JSONEncoder().encode(packet)
        for sequence in ["+2", "02", " 2", "18446744073709551616"] {
            rejects("noncanonical or overflowing sequence \(sequence)") {
                _ = try receiver.decode(signed(sequence: sequence, payload: validPayload))
            }
        }
        rejects("signed packet rejects non-string fields") {
            _ = try receiver.decode(signed(sequence: "2", payload: Data(#"{"type":1,"id":"","target":"","detail":"","device":""}"#.utf8)))
        }
        let afterInvalid = try receiver.decode(signed(sequence: "2", payload: validPayload))
        check(afterInvalid == packet, "invalid authenticated payload does not consume the sequence")
        rejects("frame overflow") { var b = FrameBuffer(); _ = try b.append(Data(repeating: 65, count: 4097)) }
        var multi = FrameBuffer(); let all = try multi.append(encoded + encoded); check(all.count == 2, "coalesced frames")
        var invalidUTF8 = FrameBuffer()
        rejects("invalid UTF-8 frame") { _ = try invalidUTF8.append(Data([0xff, 10])) }
        let recovered = try invalidUTF8.append(encoded)
        check(recovered.count == 1 && recovered[0] == frames[0], "invalid frame bytes do not contaminate a new frame")
        var tx = Transfer(target: "android", now: Date(timeIntervalSince1970: 100), id: "tx")
        check(!tx.accept(Packet(type: "released", id: "tx")), "release before readiness rejected")
        check(!tx.accept(Packet(type: "ready", id: "old")), "stale transaction rejected")
        check(tx.accept(Packet(type: "ready", id: "tx")), "ready permits release")
        check(!tx.accept(Packet(type: "ready", id: "tx")), "duplicate readiness rejected")
        check(!tx.accept(Packet(type: "result", id: "tx")), "success before acquisition rejected")
        check(tx.accept(Packet(type: "released", id: "tx")), "release permits acquire")
        check(tx.accept(Packet(type: "result", id: "tx")), "success only after acquire")
        check(!tx.expired(at: Date(timeIntervalSince1970: 134)), "deadline not early")
        check(tx.expired(at: Date(timeIntervalSince1970: 135)), "deadline boundary")
        let data = try Data(contentsOf: URL(fileURLWithPath: "protocol/vectors.json"))
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        for vector in json["vectors"] as! [[String: String]] {
            let role = vector["role"] == "mac" ? "android" : "mac"
            let p = try SecureWire(secret: key, session: session, role: role).decode(vector["frame"]!)
            check(p.detail == "Готово" && p.id == "test-transaction", "cross-language golden vector \(role)")
        }
        print("\(count) Swift checks passed")
    }
}
