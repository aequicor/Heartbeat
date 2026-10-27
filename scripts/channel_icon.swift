// Draws a channel badge (BETA / DEV) over the Heartbeat icon.
// Usage: swift scripts/channel_icon.swift <beta|dev> <source.png> <size> <output.png>
import AppKit

let arguments = CommandLine.arguments
guard arguments.count == 5, let size = Int(arguments[3]), size > 0 else {
    FileHandle.standardError.write(Data("usage: channel_icon.swift <beta|dev> <source.png> <size> <output.png>\n".utf8))
    exit(2)
}
let channel = arguments[1]
guard let source = NSImage(contentsOfFile: arguments[2]) else {
    FileHandle.standardError.write(Data("cannot read \(arguments[2])\n".utf8))
    exit(1)
}
let palette: [String: (label: String, color: NSColor)] = [
    "beta": ("BETA", NSColor(srgbRed: 0.96, green: 0.62, blue: 0.04, alpha: 1)),
    "dev": ("DEV", NSColor(srgbRed: 0.28, green: 0.33, blue: 0.41, alpha: 1)),
]
guard let badge = palette[channel] else {
    FileHandle.standardError.write(Data("unknown channel \(channel)\n".utf8))
    exit(2)
}

let side = CGFloat(size)
guard let bitmap = NSBitmapImageRep(
    bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size, bitsPerSample: 8, samplesPerPixel: 4,
    hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0
) else { exit(1) }
bitmap.size = NSSize(width: side, height: side)
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
NSGraphicsContext.current?.imageInterpolation = .high
source.draw(in: NSRect(x: 0, y: 0, width: side, height: side), from: .zero, operation: .copy, fraction: 1)

// A pill over the lower edge; icons too small for legible text get a plain colored band.
let pill = NSRect(x: side * 0.14, y: side * 0.06, width: side * 0.72, height: side * 0.27)
let path = NSBezierPath(roundedRect: pill, xRadius: pill.height / 2, yRadius: pill.height / 2)
badge.color.setFill()
path.fill()
NSColor.white.withAlphaComponent(0.9).setStroke()
path.lineWidth = max(1, side * 0.012)
path.stroke()
if size >= 48 {
    let font = NSFont.systemFont(ofSize: pill.height * 0.58, weight: .heavy)
    let attributes: [NSAttributedString.Key: Any] = [
        .font: font, .foregroundColor: NSColor.white, .kern: pill.height * 0.06,
    ]
    let text = NSAttributedString(string: badge.label, attributes: attributes)
    let extent = text.size()
    text.draw(at: NSPoint(x: pill.midX - extent.width / 2, y: pill.midY - extent.height / 2))
}
NSGraphicsContext.restoreGraphicsState()

guard let png = bitmap.representation(using: .png, properties: [:]) else { exit(1) }
try png.write(to: URL(fileURLWithPath: arguments[4]))
