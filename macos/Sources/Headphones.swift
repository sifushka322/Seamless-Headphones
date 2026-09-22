import Foundation
import CoreAudio
import IOBluetooth

struct Headset: Identifiable {
    let id: String
    let name: String
}

enum AudioDevices {
    static func number(_ id: AudioObjectID, _ selector: AudioObjectPropertySelector, scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeGlobal) -> UInt32? {
        var address = AudioObjectPropertyAddress(mSelector: selector, mScope: scope, mElement: kAudioObjectPropertyElementMain)
        var value: UInt32 = 0; var size = UInt32(MemoryLayout<UInt32>.size)
        guard AudioObjectGetPropertyData(id, &address, 0, nil, &size, &value) == noErr else { return nil }
        return value
    }
    static func string(_ id: AudioObjectID, _ selector: AudioObjectPropertySelector) -> String? {
        var address = AudioObjectPropertyAddress(mSelector: selector, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        var value: Unmanaged<CFString>?; var size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        guard AudioObjectGetPropertyData(id, &address, 0, nil, &size, &value) == noErr else { return nil }
        return value?.takeRetainedValue() as String?
    }
    static func all() -> [AudioDeviceID] {
        var address = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDevices, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size) == noErr else { return [] }
        guard size > 0 else { return [] }
        var values = [AudioDeviceID](repeating: 0, count: Int(size) / MemoryLayout<AudioDeviceID>.size)
        let status = values.withUnsafeMutableBytes { AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size, $0.baseAddress!) }
        return status == noErr ? values : []
    }
    static func hasOutput(_ id: AudioDeviceID) -> Bool {
        var address = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyStreams, mScope: kAudioObjectPropertyScopeOutput, mElement: kAudioObjectPropertyElementMain)
        var size: UInt32 = 0
        return AudioObjectGetPropertyDataSize(id, &address, 0, nil, &size) == noErr && size > 0
    }
    static func normalized(_ value: String) -> String { value.uppercased().filter { $0.isHexDigit } }
    static func output(for address: String) -> AudioDeviceID? {
        let key = normalized(address)
        guard key.count == 12 else { return nil }
        return all().first { id in
            guard hasOutput(id), let uid = string(id, kAudioDevicePropertyDeviceUID),
                  let transport = number(id, kAudioDevicePropertyTransportType),
                  transport == kAudioDeviceTransportTypeBluetooth || transport == kAudioDeviceTransportTypeBluetoothLE else { return false }
            // CoreAudio Bluetooth UIDs embed the address. Never identify devices by display name.
            return normalized(uid).contains(key)
        }
    }
    static var currentName: String {
        guard let id = number(AudioObjectID(kAudioObjectSystemObject), kAudioHardwarePropertyDefaultOutputDevice) else { return "Неизвестно" }
        return string(id, kAudioObjectPropertyName) ?? "Неизвестно"
    }
    static var microphoneActive: Bool {
        let processes = MediaMonitor.observe()
        if processes.available { return processes.microphone }
        guard let id = number(AudioObjectID(kAudioObjectSystemObject), kAudioHardwarePropertyDefaultInputDevice) else { return false }
        return number(id, kAudioDevicePropertyDeviceIsRunningSomewhere) == 1
    }
    static func select(_ id: AudioDeviceID) -> Bool {
        var address = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDefaultOutputDevice, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        var value = id
        guard AudioObjectSetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, UInt32(MemoryLayout<AudioDeviceID>.size), &value) == noErr else { return false }
        return number(AudioObjectID(kAudioObjectSystemObject), kAudioHardwarePropertyDefaultOutputDevice) == id
    }
}

final class Headphones {
    private let queue = DispatchQueue(label: "app.systemresponse.headphones")
    private var operation = UUID()
    private let lock = NSLock()
    private func begin() -> UUID { lock.lock(); defer { lock.unlock() }; operation = UUID(); return operation }
    private func current(_ token: UUID) -> Bool { lock.lock(); defer { lock.unlock() }; return operation == token }
    static func paired() -> [Headset] {
        var seen = Set<String>()
        return (IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice] ?? []).compactMap {
            guard let address = $0.addressString else { return nil }
            // Bluetooth major class 4 = audio/video. Avoid keyboards/mice in the picker.
            guard $0.deviceClassMajor == 4 else { return nil }
            // macOS can return multiple records for the same Bluetooth address.
            // Keep the original address for saved selections; names are not unique identities.
            let key = AudioDevices.normalized(address)
            guard key.count == 12, seen.insert(key).inserted else { return nil }
            return Headset(id: address, name: $0.name ?? address)
        }.sorted { $0.name < $1.name }
    }
    func preflight(address: String) -> String? {
        guard !address.isEmpty, let device = IOBluetoothDevice(addressString: address), device.isPaired() else { return "Выбери сопряжённые наушники" }
        guard IOBluetoothHostController.default()?.powerState == kBluetoothHCIPowerStateON else { return "Включи Bluetooth на Mac" }
        if AudioDevices.microphoneActive { return "Микрофон Mac используется. Заверши разговор перед передачей" }
        return nil
    }
    func cancel() { _ = begin() }
    func release(address: String, completion: @escaping (Bool, String) -> Void) {
        let token = begin()
        queue.async { [weak self] in
            guard let self, self.current(token) else { return }
            guard let device = IOBluetoothDevice(addressString: address) else {
                DispatchQueue.main.async { if self.current(token) { completion(false, "Наушники не найдены") } }; return
            }
            let status = device.isConnected() ? device.closeConnection() : kIOReturnSuccess
            let released = status == kIOReturnSuccess && !device.isConnected()
            DispatchQueue.main.async {
                guard self.current(token) else { return }
                completion(released, released ? "Mac освободил наушники" : "macOS не отключила наушники")
            }
        }
    }
    func acquire(address: String, completion: @escaping (Bool, String) -> Void) {
        let token = begin()
        queue.async { [weak self] in
            guard let self, self.current(token) else { return }
            guard let device = IOBluetoothDevice(addressString: address) else {
                DispatchQueue.main.async { if self.current(token) { completion(false, "Наушники не найдены") } }; return
            }
            // Explicit short HCI timeout. Baseband success alone is never reported as audio success.
            if !device.isConnected() { _ = device.openConnection(nil, withPageTimeout: 8000, authenticationRequired: false) }
            DispatchQueue.main.async { self.waitForOutput(address: address, token: token, remaining: 40, completion: completion) }
        }
    }
    private func waitForOutput(address: String, token: UUID, remaining: Int, completion: @escaping (Bool, String) -> Void) {
        guard current(token) else { return }
        if AudioDevices.microphoneActive { completion(false, "Микрофон начал использоваться. Выбор аудиовыхода остановлен"); return }
        if let id = AudioDevices.output(for: address), AudioDevices.select(id) {
            completion(true, "Наушники выбраны системным выходом Mac"); return
        }
        guard remaining > 0 else { completion(false, "macOS не предоставила аудиовыход. Подключи наушники в настройках Bluetooth"); return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in
            self?.waitForOutput(address: address, token: token, remaining: remaining - 1, completion: completion)
        }
    }
}
