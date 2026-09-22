import Foundation
import CoreAudio
import AppKit

struct MediaObservation {
    var available = false
    var active: Set<String> = []
    var microphone = false
    var names: [String: String] = [:]
}

enum MediaMonitor {
    static let sources: [(String, String)] = [
        ("com.spotify.client", "Spotify"), ("com.apple.Music", "Apple Music"),
        ("org.videolan.vlc", "VLC"), ("com.colliderli.iina", "IINA"),
        ("com.apple.QuickTimePlayerX", "QuickTime"), ("com.apple.Safari", "Safari"),
        ("com.google.Chrome", "Chrome"), ("org.mozilla.firefox", "Firefox")
    ]
    static let defaults: Set<String> = Set(sources.prefix(5).map(\.0))
    static func observe() -> MediaObservation {
        var address = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyProcessObjectList, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        let system = AudioObjectID(kAudioObjectSystemObject)
        var size: UInt32 = 0
        guard AudioObjectHasProperty(system, &address), AudioObjectGetPropertyDataSize(system, &address, 0, nil, &size) == noErr else { return MediaObservation() }
        if size == 0 { return MediaObservation(available: true) }
        var ids = [AudioObjectID](repeating: 0, count: Int(size) / MemoryLayout<AudioObjectID>.size)
        guard ids.withUnsafeMutableBytes({ AudioObjectGetPropertyData(system, &address, 0, nil, &size, $0.baseAddress!) }) == noErr else { return MediaObservation() }
        var result = MediaObservation(available: true)
        for id in ids {
            // Input of every process, rather than the default input device, protects conferencing apps.
            if AudioDevices.number(id, kAudioProcessPropertyIsRunningInput) == 1 { result.microphone = true }
            guard AudioDevices.number(id, kAudioProcessPropertyIsRunningOutput) == 1 else { continue }
            let pid = AudioDevices.number(id, kAudioProcessPropertyPID).map { pid_t(bitPattern: $0) }
            let app = pid.flatMap { NSRunningApplication(processIdentifier: $0) }
            let bundle = AudioDevices.string(id, kAudioProcessPropertyBundleID) ?? app?.bundleIdentifier ?? "unknown.\(id)"
            if bundle == Bundle.main.bundleIdentifier || bundle.hasPrefix("app.systemresponse") { continue }
            let canonical = sources.first { bundle == $0.0 || bundle.hasPrefix($0.0 + ".") }?.0 ?? bundle
            result.active.insert(canonical); result.names[canonical] = app?.localizedName ?? canonical
        }
        return result
    }
}
