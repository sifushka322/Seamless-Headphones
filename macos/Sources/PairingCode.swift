import AppKit
import CoreImage

enum PairingCode {
    static func payload(key: String, device: String, name: String) -> Data? {
        guard Data(base64Encoded: key)?.count == 32 else { return nil }
        return try? JSONSerialization.data(withJSONObject: ["app": "seamless-headphones", "version": 1,
                                                            "key": key, "device": device, "name": name])
    }
    static func image(key: String, device: String, name: String) -> NSImage? {
        guard let data = payload(key: key, device: device, name: name),
              let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(data, forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 6, y: 6)),
              let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return NSImage(cgImage: cg, size: NSSize(width: output.extent.width, height: output.extent.height))
    }
}
