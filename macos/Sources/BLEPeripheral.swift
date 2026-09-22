import Foundation
import CoreBluetooth

final class BLEPeripheral: NSObject, CBPeripheralManagerDelegate {
    static let serviceID = CBUUID(string: "845E1000-7F5A-4CB5-9AE8-2DC18A64BB01")
    static let writeID = CBUUID(string: "845E1001-7F5A-4CB5-9AE8-2DC18A64BB01")
    static let notifyID = CBUUID(string: "845E1002-7F5A-4CB5-9AE8-2DC18A64BB01")
    var onPacket: (Packet) -> Void = { _ in }
    var onStatus: (String, Bool) -> Void = { _, _ in }
    var onDisconnect: () -> Void = {}
    private var manager: CBPeripheralManager!
    private var notifyCharacteristic: CBMutableCharacteristic!
    private var central: CBCentral?
    private var wire: SecureWire?
    private var buffer = FrameBuffer()
    private var pending = [Data]()
    private var secret: Data?
    private(set) var authenticated = false
    private var generation = UUID()

    func start(secret: Data) {
        self.secret = secret
        if manager == nil { manager = CBPeripheralManager(delegate: self, queue: .main) }
        else if manager.state == .poweredOn { publish() }
    }
    func stop() {
        manager?.stopAdvertising(); manager?.removeAllServices(); reset()
        secret = nil; onStatus("Связь выключена", false)
    }
    private func reset() {
        let hadPeer = central != nil
        generation = UUID(); central = nil; wire = nil; authenticated = false
        pending.removeAll(); buffer = FrameBuffer()
        if hadPeer { onDisconnect() }
    }
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        if peripheral.state == .poweredOn, secret != nil { publish() }
        else { reset(); onStatus(peripheral.state == .unauthorized ? "Разреши Bluetooth в настройках macOS" : "Bluetooth недоступен", false) }
    }
    private func publish() {
        manager.stopAdvertising(); manager.removeAllServices(); reset()
        let service = CBMutableService(type: Self.serviceID, primary: true)
        let write = CBMutableCharacteristic(type: Self.writeID, properties: .write, value: nil, permissions: .writeable)
        notifyCharacteristic = CBMutableCharacteristic(type: Self.notifyID, properties: .notify, value: nil, permissions: [])
        service.characteristics = [write, notifyCharacteristic]; manager.add(service)
    }
    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        guard error == nil, secret != nil else { onStatus("Не удалось создать BLE-сервис", false); return }
        peripheral.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [Self.serviceID], CBAdvertisementDataLocalNameKey: "Seamless Headphones"])
        onStatus("Ожидаем телефон рядом", false)
    }
    func peripheralManager(_ peripheral: CBPeripheralManager, didStartAdvertising error: Error?) {
        if let error { onStatus("BLE: \(error.localizedDescription)", false) }
    }
    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        guard self.central == nil, let secret else { return }
        self.central = central; authenticated = false; buffer = FrameBuffer(); pending.removeAll()
        let session = UUID().uuidString
        wire = SecureWire(secret: secret, session: session, role: "mac")
        enqueue(Data("HELLO|\(session)\n".utf8)); onStatus("Проверяем доверие…", false)
        let token = generation
        DispatchQueue.main.asyncAfter(deadline: .now() + 12) { [weak self] in
            guard let self, self.generation == token, !self.authenticated else { return }
            self.publish()
        }
    }
    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        if self.central?.identifier == central.identifier { reset(); onStatus("Телефон отключён", false) }
    }
    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        // CoreBluetooth requires exactly one response for the entire atomic batch.
        // The v1 client serializes individual writes; reject unsupported batches without side effects.
        guard requests.count == 1 else {
            if let first = requests.first { peripheral.respond(to: first, withResult: .requestNotSupported) }
            return
        }
        for request in requests {
            guard request.central.identifier == central?.identifier, request.characteristic.uuid == Self.writeID,
                  request.offset == 0, let value = request.value else {
                peripheral.respond(to: request, withResult: .writeNotPermitted); continue
            }
            do {
                for frame in try buffer.append(value) {
                    guard let wire else { throw WireError.invalid }
                    let packet = try wire.decode(frame)
                    if !authenticated {
                        guard packet.type == "hello" else { throw WireError.authentication }
                        authenticated = true; send(Packet(type: "hello")); onStatus("Телефон • доверенная связь", true)
                    } else { onPacket(packet) }
                }
                peripheral.respond(to: request, withResult: .success)
            } catch {
                peripheral.respond(to: request, withResult: .unlikelyError)
                onStatus("Ошибка проверки BLE-пакета", authenticated)
            }
        }
    }
    func send(_ packet: Packet) {
        guard authenticated, let wire, let data = try? wire.encode(packet) else { return }
        enqueue(data)
    }
    private func enqueue(_ data: Data) {
        guard let central else { return }
        let size = max(1, min(central.maximumUpdateValueLength, 180))
        for offset in stride(from: 0, to: data.count, by: size) { pending.append(data.subdata(in: offset..<min(offset + size, data.count))) }
        if pending.count > 512 { publish(); return }
        flush()
    }
    private func flush() {
        guard let central, let characteristic = notifyCharacteristic else { return }
        while let next = pending.first {
            guard manager.updateValue(next, for: characteristic, onSubscribedCentrals: [central]) else { return }
            pending.removeFirst()
        }
    }
    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) { flush() }
}
