import Foundation
import CoreGraphics
import ImageIO

let root = CommandLine.arguments[1]
try FileManager.default.createDirectory(atPath: root, withIntermediateDirectories: true)
for size in [16, 32, 128, 256, 512] {
    for scale in [1, 2] {
        let pixels = size * scale
        let ctx = CGContext(data: nil, width: pixels, height: pixels, bitsPerComponent: 8, bytesPerRow: pixels * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.scaleBy(x: CGFloat(pixels) / 1024, y: CGFloat(pixels) / 1024)
        ctx.setFillColor(CGColor(red: 0.08, green: 0.43, blue: 0.38, alpha: 1))
        ctx.addPath(CGPath(roundedRect: CGRect(x: 70, y: 70, width: 884, height: 884), cornerWidth: 194, cornerHeight: 194, transform: nil)); ctx.fillPath()
        ctx.setStrokeColor(CGColor(gray: 1, alpha: 1)); ctx.setLineCap(.round); ctx.setLineWidth(44)
        for (i, height) in [120, 280, 440, 280, 120].enumerated() {
            let x = CGFloat(272 + i * 120)
            ctx.move(to: CGPoint(x: x, y: CGFloat(512 - height / 2))); ctx.addLine(to: CGPoint(x: x, y: CGFloat(512 + height / 2))); ctx.strokePath()
        }
        let suffix = scale == 2 ? "@2x" : ""
        let url = URL(fileURLWithPath: "\(root)/icon_\(size)x\(size)\(suffix).png")
        let destination = CGImageDestinationCreateWithURL(url as CFURL, "public.png" as CFString, 1, nil)!
        CGImageDestinationAddImage(destination, ctx.makeImage()!, nil)
        guard CGImageDestinationFinalize(destination) else { fatalError("Icon export failed") }
    }
}
