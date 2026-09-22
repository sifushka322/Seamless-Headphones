import AppKit
import ImageIO

// Use the same symbol and palette as the approved sidebar mark.
let root = CommandLine.arguments[1]
try FileManager.default.createDirectory(atPath: root, withIntermediateDirectories: true)
let mint = NSColor(srgbRed: 0.39, green: 0.87, blue: 0.72, alpha: 1)
let background = NSColor(srgbRed: 0.118, green: 0.204, blue: 0.188, alpha: 1)
func render(pixels: Int, tile: Bool, url: URL) {
    let context = CGContext(data: nil, width: pixels, height: pixels, bitsPerComponent: 8, bytesPerRow: pixels * 4,
        space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    context.scaleBy(x: CGFloat(pixels) / 1024, y: CGFloat(pixels) / 1024)
    if tile {
        context.setFillColor(background.cgColor)
        context.addPath(CGPath(roundedRect: CGRect(x: 70, y: 70, width: 884, height: 884), cornerWidth: 250, cornerHeight: 250, transform: nil))
        context.fillPath()
    }
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(cgContext: context, flipped: false)
    let config = NSImage.SymbolConfiguration(pointSize: 400, weight: .semibold)
        .applying(NSImage.SymbolConfiguration(hierarchicalColor: mint))
    let symbol = NSImage(systemSymbolName: "airpods.pro", accessibilityDescription: nil)!.withSymbolConfiguration(config)!
    let width: CGFloat = tile ? 620 : 580
    let height = width * symbol.size.height / symbol.size.width
    symbol.draw(in: CGRect(x: (1024-width)/2, y: (1024-height)/2, width: width, height: height))
    NSGraphicsContext.restoreGraphicsState()
    let destination = CGImageDestinationCreateWithURL(url as CFURL, "public.png" as CFString, 1, nil)!
    CGImageDestinationAddImage(destination, context.makeImage()!, nil)
    precondition(CGImageDestinationFinalize(destination))
}
for size in [16, 32, 128, 256, 512] {
    for scale in [1, 2] {
        render(pixels: size * scale, tile: true, url: URL(fileURLWithPath: "\(root)/icon_\(size)x\(size)\(scale == 2 ? "@2x" : "").png"))
    }
}
if CommandLine.arguments.count > 2 {
    let output = URL(fileURLWithPath: CommandLine.arguments[2])
    try FileManager.default.createDirectory(at: output.deletingLastPathComponent(), withIntermediateDirectories: true)
    render(pixels: 432, tile: false, url: output)
}
