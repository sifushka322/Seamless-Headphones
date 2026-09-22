import Foundation
import AppKit
import Combine
import CoreAudio

final class AppModel: ObservableObject {
    @Published var theme = UserDefaults.standard.string(forKey: "theme") ?? "system" { didSet { save(theme, "theme") } }
    @Published var accent = UserDefaults.standard.string(forKey: "accent") ?? "mint" { didSet { save(accent, "accent") } }
    @Published var autoEnabled = UserDefaults.standard.object(forKey: "auto") as? Bool ?? true { didSet { save(autoEnabled, "auto"); settingsChanged() } }
    @Published var idleOnly = UserDefaults.standard.bool(forKey: "idleOnly") { didSet { save(idleOnly, "idleOnly"); settingsChanged() } }
    @Published var delay = UserDefaults.standard.object(forKey: "delay") as? Double ?? 2.0 { didSet { save(delay, "delay"); settingsChanged() } }
    @Published var cooldown = UserDefaults.standard.object(forKey: "cooldown") as? Double ?? 20.0 { didSet { save(cooldown, "cooldown") } }
    @Published var reconnect = UserDefaults.standard.object(forKey: "reconnect") as? Bool ?? true { didSet { save(reconnect, "reconnect") } }
    @Published var allowed = Set(UserDefaults.standard.stringArray(forKey: "sources") ?? Array(MediaMonitor.defaults)) { didSet { save(Array(allowed), "sources"); settingsChanged() } }
    @Published var owner = "unknown"
    @Published var stage = ""
    @Published var autoReason = "Ожидаем подключение"
    @Published var autoPaused = false
    @Published var local = ActivityState()
    @Published var peer = ActivityState()
    @Published var successful = UserDefaults.standard.integer(forKey: "transfers")
    @Published var lastDuration = "—"
    @Published var headsets: [Headset] = []
    @Published var selected: String = UserDefaults.standard.string(forKey: "headset") ?? "" {
        didSet { if !demo { UserDefaults.standard.set(selected, forKey: "headset"); if oldValue != selected { cancel("Выбраны другие наушники"); owner = "unknown"; settingsChanged() } } }
    }
    @Published var link = "Связь выключена"
    @Published var trusted = false
    @Published var enabled = false
    @Published var held = false { didSet { if held { cancel("Передача приостановлена") } } }
    @Published var headline = "Твой звук. Там, где ты."
    @Published var detail = "Выбери наушники и свяжи телефон с Mac."
    @Published var route = "—"
    @Published var busy = false
    @Published var events: [String] = []
    @Published var pairingCode = ""
    @Published var showPairing = false
    @Published var errorText = ""
    let demo: Bool
    private let ble = BLEPeripheral()
    private let audio = Headphones()
    private var transfer: Transfer?
    private var timer: Timer?
    private var heartbeat: Date = .distantPast
    private var watchdogTicks = 0
    private var observers: [NSObjectProtocol] = []
    private var policy = AutoPolicy()
    private var edges = MediaEdges()
    private var peerReceived = 0.0
    private var quietUntil = 0.0
    private var automaticTransfer = false
    private var sleeping = false
    private var lastSent: ActivityState?
    private var lastGate: Bool?
    private var now: Double { ProcessInfo.processInfo.systemUptime }
    private func save(_ value: Any, _ key: String) { if !demo { UserDefaults.standard.set(value, forKey: key) } }
    private func settingsChanged() {
        guard !demo else { return }; edges.reset(); policy.resetSession(); lastSent = nil
        if automaticTransfer && busy { cancel("Настройки автоматизации изменены") }
    }
    func resumeAuto() { policy.resume(); autoPaused = false; edges.reset(); log("Автоматизация возобновлена") }

    init(demo: Bool = false) {
        self.demo = demo
        if demo {
            headsets = [Headset(id: "00-11-22-33-44-55", name: "Мои наушники")]
            selected = headsets[0].id; route = "Мои наушники"; link = "Демонстрация интерфейса"; detail = "Bluetooth в деморежиме не используется."
            trusted = true; enabled = true; owner = "mac"; successful = 24; lastDuration = "2,4 с"
            local = ActivityState(available: true, playing: true, automation: true, source: "Spotify", connected: true)
            peer = ActivityState(available: true, automation: true)
            autoReason = "Готово · ждём новое воспроизведение"; events = ["Сейчас  Деморежим — реальные команды отключены", "12:42:18  Звук передан на Mac · 2,4 с"]
            return
        }
        refresh()
        ble.onStatus = { [weak self] status, trusted in
            guard let self else { return }; let newSession = trusted && !self.trusted; self.link = status; self.trusted = trusted
            if newSession { self.heartbeat = Date(); self.peerReceived = 0; self.policy.resetSession(); self.edges.reset(); self.lastSent = nil; self.log("Устройства подтвердили доверие") }
        }
        ble.onDisconnect = { [weak self] in
            guard let self else { return }; self.trusted = false; self.peerReceived = 0; self.peer = ActivityState(); self.policy.resetSession(); self.edges.reset()
            if self.busy { self.policy.failed(); self.cancel("BLE-связь потеряна во время передачи. Проверь аудиовыход") }
        }
        ble.onPacket = { [weak self] in self?.receive($0) }
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.tick() }
        observers.append(NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.willSleepNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self else { return }; self.sleeping = true; self.cancel("Mac переходит в сон"); self.ble.stop(); self.trusted = false
        })
        observers.append(NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self else { return }; self.sleeping = false; self.edges.reset(); self.policy.resetSession()
            if self.enabled && self.reconnect { self.startLink(reveal: false) }
        })
    }
    func refresh() { guard !demo else { return }; headsets = Headphones.paired(); route = AudioDevices.currentName }
    func log(_ text: String) {
        let stamp = DateFormatter.localizedString(from: Date(), dateStyle: .none, timeStyle: .medium)
        events.insert("\(stamp)  \(text)", at: 0); events = Array(events.prefix(60))
    }
    func enable() {
        startLink(reveal: true)
    }
    private func startLink(reveal: Bool) {
        guard !demo else { return }
        do { let secret = try KeyStore.secret(); pairingCode = secret.base64EncodedString(); enabled = true; ble.start(secret: secret); if reveal { showPairing = true } }
        catch { errorText = "Не удалось сохранить ключ доверия в Keychain: \(error.localizedDescription)" }
    }
    func disable() { cancel("Связь остановлена"); ble.stop(); enabled = false; trusted = false; pairingCode = ""; showPairing = false }
    func revoke() {
        disable()
        do { _ = try KeyStore.replace(); detail = "Старый ключ больше не действует. Свяжи телефон заново." }
        catch { errorText = error.localizedDescription }
    }
    func request(_ target: String, fromPeer: Bool = false, automatic: Bool = false) {
        func reject(_ reason: String) {
            detail = reason; log(reason)
            if fromPeer { ble.send(Packet(type: "notice", detail: reason)) }
        }
        guard !demo else { detail = "Это деморежим. Для проверки открой приложение обычным способом."; return }
        guard enabled, trusted else { reject("Сначала установи доверенную связь с телефоном"); return }
        guard !busy else { reject("Другая передача уже выполняется"); return }
        guard !held else { reject("Сними удержание на Mac"); return }
        if let reason = audio.preflight(address: selected) { reject(reason); return }
        guard target == "mac" || target == "android" else { return }
        if automatic, !autoSafe(target) { return }
        if !automatic { policy.manual(now: now) }
        let tx = Transfer(target: target); transfer = tx; busy = true; automaticTransfer = automatic; stage = "preparing"
        headline = "Готовим передачу"; detail = target == "mac" ? "Проверяем готовность Mac и телефона" : "Проверяем, сможет ли телефон принять наушники"
        log("\(automatic ? "Авто" : "Вручную") → \(target == "mac" ? "Mac" : "Android")")
        ble.send(Packet(type: "prepare", id: tx.id, target: target, detail: automatic ? "auto" : "manual", device: selected))
    }
    private func autoSafe(_ target: String) -> Bool {
        guard autoEnabled, peer.automation, local.available, peer.available, !held, !peer.held,
              !local.call, !peer.call, now - peerReceived < 7 else { return false }
        let destination = target == "mac" ? local : peer
        let source = target == "mac" ? peer : local
        return destination.playing && (!idleOnly || !source.playing)
    }
    private func receive(_ packet: Packet) {
        heartbeat = Date()
        if packet.type == "ping" { ble.send(Packet(type: "pong")); return }
        if packet.type == "pong" { return }
        if packet.type == "resumeAuto" { resumeAuto(); return }
        if packet.type == "mode", ["idle", "follow"].contains(packet.target) { idleOnly = packet.target == "idle"; ble.send(Packet(type: "notice", detail: idleOnly ? "Режим: только в паузе" : "Режим: следовать новому звуку")); return }
        if packet.type == "activity", let data = packet.detail.data(using: .utf8), data.count < 1600,
           let snapshot = try? JSONDecoder().decode(ActivityState.self, from: data), snapshot.event >= 0, snapshot.source.count < 200 {
            peer = snapshot; peerReceived = now
            if busy && (snapshot.call || snapshot.held) { cancel("Телефон включил защиту разговора или удержание") }
            return
        }
        if packet.type == "request" { request(packet.target, fromPeer: true); return }
        guard var tx = transfer, packet.id == tx.id else { return }
        // An error can arrive after the remote OS accepted a connection. Never blindly steal it back.
        if packet.type == "error" { fail(packet.detail); return }
        if packet.type == "ready" {
            guard AudioDevices.normalized(packet.device) == AudioDevices.normalized(selected) else { fail("На устройствах выбраны разные наушники"); return }
        }
        guard tx.accept(packet) else { return }
        transfer = tx; stage = tx.stage.rawValue
        switch packet.type {
        case "ready":
            guard !held, audio.preflight(address: selected) == nil, !automaticTransfer || autoSafe(tx.target) else { cancel("Условия передачи изменились"); return }
            headline = "Освобождаем наушники"; detail = "Принимающее устройство готово"
            if tx.target == "android" {
                audio.release(address: selected) { [weak self] ok, reason in
                    guard let self, self.transfer?.id == tx.id else { return }
                    if ok { self.receive(Packet(type: "released", id: tx.id)) }
                    else { self.fail(reason) }
                }
            } else { ble.send(Packet(type: "release", id: tx.id)) }
        case "released":
            headline = "Подключаем наушники"; detail = "Ждём подтверждения аудиомаршрута"
            if tx.target == "android" { ble.send(Packet(type: "acquire", id: tx.id)) }
            else {
                audio.acquire(address: selected) { [weak self] ok, reason in
                    guard let self, self.transfer?.id == tx.id else { return }
                    if ok { self.finish(reason) } else { self.fail(reason) }
                }
            }
        case "result": finish(packet.detail)
        default: break
        }
    }
    private func finish(_ reason: String) {
        guard let tx = transfer else { return }
        ble.send(Packet(type: "complete", id: tx.id, target: tx.target, detail: reason))
        headline = tx.target == "mac" ? "Звук на Mac" : "Телефон принял наушники"
        owner = tx.target; lastDuration = String(format: "%.1f с", Date().timeIntervalSince(tx.started))
        successful += 1; save(successful, "transfers"); policy.completed(now: now, cooldown: cooldown); quietUntil = now + cooldown
        detail = reason; log("\(headline) · \(lastDuration)"); transfer = nil; busy = false; automaticTransfer = false; stage = ""; refresh()
    }
    private func fail(_ reason: String) { policy.failed(); autoPaused = true; cancel(reason + "\nАвто приостановлено. Проверь подключение перед возобновлением.") }
    func cancel(_ reason: String) {
        if let tx = transfer { ble.send(Packet(type: "cancel", id: tx.id)) }
        audio.cancel(); transfer = nil; busy = false; automaticTransfer = false; stage = ""; quietUntil = now + 10
        headline = "Передача остановлена"; detail = reason; log(reason)
    }
    private func tick() {
        guard !sleeping else { return }
        route = AudioDevices.currentName; watchdogTicks += 1
        let observation = MediaMonitor.observe()
        let peerReady = now - peerReceived < 7 && peerReceived > 0 && peer.available && peer.automation && !peer.call && !peer.held
        edges.sample(observation.active.intersection(allowed), now: now, delay: delay,
                     suppress: !autoEnabled || !trusted || !peerReady || held || busy || observation.microphone || now < quietUntil || now < policy.blockedUntil || policy.suspended)
        let connected = AudioDevices.output(for: selected).map { AudioDevices.number(AudioObjectID(kAudioObjectSystemObject), kAudioHardwarePropertyDefaultOutputDevice) == $0 } ?? false
        local = ActivityState(available: observation.available, playing: !observation.active.isEmpty, call: observation.microphone,
                              held: held, automation: autoEnabled, event: edges.event,
                              source: MediaMonitor.sources.first { $0.0 == edges.source }?.1 ?? edges.source, connected: connected)
        if busy && local.call { cancel("Микрофон начал использоваться. Передача остановлена") }
        if !busy {
            if owner == "mac" && !connected { owner = "unknown" }
            if owner == "android" && !peer.connected && peerReceived > 0 { owner = "unknown" }
            if connected && owner == "unknown" { owner = "mac" }
        }
        if trusted, local != lastSent || watchdogTicks % 3 == 0, let data = try? JSONEncoder().encode(local), let json = String(data: data, encoding: .utf8) {
            ble.send(Packet(type: "activity", detail: json)); lastSent = local
        }
        let decision = policy.evaluate(mac: local, phone: peer, fresh: now - peerReceived < 7 && peerReceived > 0,
                                       linked: trusted, busy: busy, owner: owner, idleOnly: idleOnly, now: now)
        autoReason = decision.reason; autoPaused = policy.suspended
        let gate = trusted && peerReady && local.available && autoEnabled && !held && !local.call && !busy && !policy.suspended && now >= policy.blockedUntil && now >= quietUntil
        if trusted, watchdogTicks % 3 == 0 || gate != lastGate {
            ble.send(Packet(type: "autoStatus", target: policy.suspended ? "paused" : "ready", detail: decision.reason, device: gate ? "armed" : "blocked")); lastGate = gate
        }
        if let target = decision.target { request(target, automatic: true) }
        if trusted, watchdogTicks % 4 == 0 { ble.send(Packet(type: "ping")) }
        if trusted, Date().timeIntervalSince(heartbeat) > 14 {
            if busy { policy.failed(); cancel("Телефон перестал отвечать во время передачи") }
            trusted = false; ble.stop(); peerReceived = 0
            if enabled && reconnect { startLink(reveal: false) }
        }
        if let tx = transfer, tx.expired(at: Date()) { fail("Время передачи истекло. Проверь текущий аудиовыход") }
    }
    func bluetoothSettings() {
        NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.BluetoothSettings")!)
    }
    func copyDiagnostics() {
        let text = "Seamless Headphones 0.2\n\(ProcessInfo.processInfo.operatingSystemVersionString)\nBLE: \(link)\nАвто: \(autoReason)\nВыход: \(route)\n" + events.reversed().joined(separator: "\n")
        NSPasteboard.general.clearContents(); NSPasteboard.general.setString(text, forType: .string)
    }
}
