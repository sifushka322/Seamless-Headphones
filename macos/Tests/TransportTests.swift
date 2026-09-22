import Foundation
import Vision
import AppKit

@main struct TransportTests {
    static func main() throws {
        var queue = PacketQueue()
        for i in 0..<100 { precondition(queue.append(Packet(type: "activity", detail: "state-\(i)"))) }
        precondition(queue.count == 1)
        precondition(queue.append(Packet(type: "prepare", id: "handoff")))
        let key = Data(0..<32)
        let sender = SecureWire(secret: key, session: "test", role: "mac")
        let receiver = SecureWire(secret: key, session: "test", role: "android")
        let command = queue.next()!
        precondition(command.type == "prepare")
        let received = try receiver.decode(String(decoding: sender.encode(command), as: UTF8.self).trimmingCharacters(in: .newlines))
        precondition(received == command)
        let snapshot = queue.next()!
        precondition(snapshot.detail == "state-99")
        let latest = try receiver.decode(String(decoding: sender.encode(snapshot), as: UTF8.self).trimmingCharacters(in: .newlines))
        precondition(latest == snapshot && queue.next() == nil)
        print("PASS snapshot flood cannot delay command or reorder authentication")
        for i in 0..<32 { precondition(queue.append(Packet(type: "request", id: "\(i)"))) }
        precondition(!queue.append(Packet(type: "request")))
        queue.reset(); precondition(queue.count == 0)
        print("PASS bounded command queue and reset")
        let vector = try Data(contentsOf: URL(fileURLWithPath: "protocol/pairing-vector.json"))
        let object = try JSONSerialization.jsonObject(with: vector) as! [String: Any]
        let payload = PairingCode.payload(key: key.base64EncodedString(), device: "00-11-22-33-44-55", name: "Buds • тест")!
        let generated = try JSONSerialization.jsonObject(with: payload) as! NSDictionary
        precondition(generated == object as NSDictionary)
        precondition(PairingCode.payload(key: "invalid", device: "", name: "") == nil)
        let image = PairingCode.image(key: key.base64EncodedString(), device: "00-11-22-33-44-55", name: "Buds • тест")!
        let cg = image.cgImage(forProposedRect: nil, context: nil, hints: nil)!
        let request = VNDetectBarcodesRequest(); request.symbologies = [.qr]
        try VNImageRequestHandler(cgImage: cg).perform([request])
        let decoded = request.results!.first!.payloadStringValue!
        let decodedObject = try JSONSerialization.jsonObject(with: Data(decoded.utf8)) as! NSDictionary
        precondition(decodedObject == generated)
        print("PASS QR image decodes to shared pairing vector")
        let bitmap = NSBitmapImageRep(cgImage: cg)
        try bitmap.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: ".build/pairing-test.png"))
    }
}
