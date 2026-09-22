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
    @Published var handoffMode = HandoffMode(rawValue: UserDefaults.standard.string(forKey: "handoffMode") ?? "receiverFirst") ?? .receiverFirst { didSet { save(handoffMode.rawValue, "handoffMode"); settingsChanged() } }
    @Published var fastDetection = UserDefaults.standard.object(forKey: "fastDetection") as? Bool ?? true { didSet { save(fastDetection, "fastDetection"); settingsChanged() } }
    private var detectionDelay: Double { fastDetection ? 0.5 : delay }
    @Published var cooldown = UserDefaults.standard.object(forKey: "cooldown") as? Double ?? 20.0 { didSet { save(cooldown, "cooldown") } }
    @Published var manualPriority = UserDefaults.standard.object(forKey: "manualPriority") as? Double ?? 15.0 { didSet { save(manualPriority, "manualPriority") } }
    @Published var observedSources = "Звук не обнаружен"
    @Published var discoveredSources: [String: String] = [:]
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
        didSet { if !demo { UserDefaults.standard.set(selected, forKey: "headset"); if oldValue != selected { trace("Selected headset=\(selected)"); cancel("Выбраны другие наушники"); owner = "unknown"; settingsChanged() } } }
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
    @Published var debugEvents: [String] = []
    @Published var debugEnabled = UserDefaults.standard.bool(forKey: "debug") {
        didSet { save(debugEnabled, "debug"); if debugEnabled { trace("Debug enabled; \(selectionSummary)") } }
    }
    @Published var peerHeadset: String? = nil
    @Published var peerHeadsetName = ""
    var selectedName: String { headsets.first { AudioDevices.normalized($0.id) == AudioDevices.normalized(selected) }?.name ?? "Не выбраны" }
    var selectionSummary: String {
        "Mac: \(selectedName) [\(selected.isEmpty ? "не выбраны" : selected)]\nAndroid: \(peerHeadsetName.isEmpty ? "ожидаем сведения" : peerHeadsetName) [\(peerHeadset ?? "неизвестно")]"
    }
    var selectionMismatch: Bool {
        guard let peerHeadset else { return false }
        return selected.isEmpty || peerHeadset.isEmpty || AudioDevices.normalized(selected) != AudioDevices.normalized(peerHeadset)
    }
    @Published var pairingCode = ""
    @Published var showPairing = false
    @Published var errorText = ""
    let demo: Bool
    private let ble = BLEPeripheral()
    private let audio = Headphones()
    private var transfer: Transfer?
    private var timer: Timer?
    private var mediaTimer: Timer?
    private var mediaWake: MediaWake?
    private var nextSnapshot = 0.0
    private var nextPing = 0.0
    private var lastTracedLocal: ActivityState?
    private var heartbeat: Date = .distantPast
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
    func resumeAuto() {
        guard !busy else { return }
        policy.resume(); quietUntil = 0; autoPaused = false; edges.reset(); lastSent = nil; lastGate = nil
        ble.send(Packet(type: "autoReset")); log("Автоматизация возобновлена · запусти музыку заново")
    }

    init(demo: Bool = false) {
        self.demo = demo
        if demo {
            headsets = [Headset(id: "00-11-22-33-44-55", name: "Мои наушники")]
            pairingCode = Data(repeating: 90, count: 32).base64EncodedString(); showPairing = true
            selected = headsets[0].id; route = "Мои наушники"; link = "Демонстрация интерфейса"; detail = "Bluetooth в деморежиме не используется."
            trusted = true; enabled = true; owner = "mac"; successful = 24; lastDuration = "2,4 с"
            local = ActivityState(available: true, playing: true, automation: true, source: "Spotify", connected: true)
            peer = ActivityState(available: true, automation: true)
            autoReason = "Готово · ждём новое воспроизведение"; events = ["Сейчас  Деморежим — реальные команды отключены", "12:42:18  Звук передан на Mac · 2,4 с"]
            return
        }
        refresh()
        mediaWake = MediaWake { [weak self] in self?.tick() }
        mediaWake?.start()
        audio.onTrace = { [weak self] in self?.trace($0) }
        ble.onTrace = { [weak self] in self?.trace($0) }
        ble.onStatus = { [weak self] status, trusted in
            guard let self else { return }; let newSession = trusted && !self.trusted; self.link = status; self.trusted = trusted; self.trace("BLE status=\(status) trusted=\(trusted)")
            if newSession { self.heartbeat = Date(); self.peerReceived = 0; self.policy.resetSession(); self.edges.reset(); self.lastSent = nil; self.log("Устройства подтвердили доверие") }
        }
        ble.onDisconnect = { [weak self] in
            guard let self else { return }; self.trusted = false; self.peerReceived = 0; self.peerHeadset = nil; self.peerHeadsetName = ""; self.peer = ActivityState(); self.policy.resetSession(); self.edges.reset()
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
    func log(_ text: String, source: String = "Mac") {
        let stamp = DateFormatter.localizedString(from: Date(), dateStyle: .none, timeStyle: .medium)
        events.insert("\(stamp) [\(source)]  \(text)", at: 0); events = Array(events.prefix(60))
    }
    func trace(_ text: String) {
        guard debugEnabled, !demo else { return }
        if ["TX type=ping ", "TX type=pong ", "RX type=ping ", "RX type=pong ", "TX type=activity ", "RX type=activity ", "TX type=autoStatus ", "RX type=autoStatus "].contains(where: { text.hasPrefix($0) }) { return }
        let formatter = DateFormatter(); formatter.dateFormat = "HH:mm:ss.SSS"
        let entry = "\(formatter.string(from: Date())) [Mac] \(text)"
        debugEvents.insert(entry, at: 0); if debugEvents.count > 1000 { debugEvents.removeLast(debugEvents.count - 1000) }
    }
    func clearLogs() { events.removeAll(); debugEvents.removeAll() }
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
        guard !busy else { trace("Ignored duplicate request target=\(target) active=\(transfer?.id ?? "")"); return }
        if selectionMismatch { reject("На устройствах выбраны разные наушники. Открой «Устройства» и сравни адреса.\n" + selectionSummary); return }
        guard !held else { reject("Сними удержание на Mac"); return }
        if let reason = audio.preflight(address: selected) { reject(reason); return }
        guard target == "mac" || target == "android" else { return }
        if automatic, !autoSafe(target) { return }
        if !automatic { policy.manual(now: now, duration: manualPriority) }
        let mode = (peer.handoffVersion ?? 0) >= 2 ? handoffMode : .sequential
        if mode != handoffMode { log("Android старой версии · используем последовательную передачу") }
        let tx = Transfer(target: target, mode: mode); trace("BEGIN tx=\(tx.id) target=\(target) automatic=\(automatic) mode=\(tx.mode.rawValue); \(selectionSummary)"); transfer = tx; busy = true; automaticTransfer = automatic; stage = "preparing"
        headline = "Готовим передачу"; detail = target == "mac" ? "Проверяем готовность Mac и телефона" : "Проверяем, сможет ли телефон принять наушники"
        log("\(automatic ? "Авто" : "Вручную") → \(target == "mac" ? "Mac" : "Android")")
        ble.send(Packet(type: "prepare", id: tx.id, target: target, detail: automatic ? "auto" : "manual", device: selected))
    }
    private func autoSafe(_ target: String) -> Bool {
        guard !selectionMismatch, autoEnabled, peer.automation, local.available, peer.available, !held, !peer.held,
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
            if peer != snapshot { trace("PEER event=\(snapshot.event) playing=\(snapshot.playing) source=\(snapshot.source) call=\(snapshot.call) guard=\(snapshot.guardReason ?? "none") connected=\(snapshot.connected)") }
            peer = snapshot; peerReceived = now
            peerHeadset = packet.device; peerHeadsetName = packet.target
            if selectionMismatch { trace("Selection mismatch; \(selectionSummary)") }
            if busy && (snapshot.call || snapshot.held) { cancel("Телефон включил защиту разговора или удержание") }
            tick() // Evaluate the new playback event immediately, not at the next one-second tick.
            return
        }
        if packet.type == "request" { request(packet.target, fromPeer: true); return }
        guard var tx = transfer, packet.id == tx.id else { return }
        // An error can arrive after the remote OS accepted a connection. Never blindly steal it back.
        if packet.type == "error", packet.target == "early", !tx.earlyActive { return }
        if packet.type == "error" { trace("Remote error tx=\(tx.id) stage=\(tx.stage.rawValue): \(packet.detail)"); fail(packet.detail, source: "Android"); return }
        if packet.type == "ready" {
            guard AudioDevices.normalized(packet.device) == AudioDevices.normalized(selected) else { fail("На устройствах выбраны разные наушники"); return }
        }
        guard tx.accept(packet) else { trace("Ignored out-of-order type=\(packet.type) tx=\(packet.id) stage=\(tx.stage.rawValue)"); return }
        trace("STAGE tx=\(tx.id) stage=\(tx.stage.rawValue) elapsed=\(tx.elapsed)")
        transfer = tx; stage = tx.stage.rawValue
        if packet.type == "ready" {
            guard !held, audio.preflight(address: selected) == nil, !automaticTransfer || autoSafe(tx.target) else { cancel("Условия передачи изменились"); return }
        }
        if packet.type == "retryable" { log("Получатель отклонил раннее подключение · отключаем источник и повторяем") }
        for action in tx.actions { perform(action, tx: tx, reason: packet.detail) }
    }
    private func perform(_ action: HandoffAction, tx: Transfer, reason: String) {
        guard transfer?.id == tx.id else { return }
        if action != .complete {
            guard !held, !peer.held, audio.preflight(address: selected) == nil, !automaticTransfer || autoSafe(tx.target) else {
                cancel("Условия передачи изменились перед следующим этапом"); return
            }
        }
        trace("ACTION tx=\(tx.id) mode=\(tx.mode.rawValue) action=\(action) elapsed=\(tx.elapsed)")
        switch action {
        case .release:
            headline = "Освобождаем источник"
            if tx.target == "android" {
                audio.release(address: selected) { [weak self] ok, reason in
                    guard let self, self.transfer?.id == tx.id else { return }
                    self.trace("AUDIO release tx=\(tx.id) ok=\(ok) elapsed=\(tx.elapsed) reason=\(reason)")
                    if ok { self.receive(Packet(type: "released", id: tx.id, detail: reason)) } else { self.fail(reason) }
                }
            } else { ble.send(Packet(type: "release", id: tx.id)) }
        case .acquire, .tryAcquire:
            let early = action == .tryAcquire
            headline = early ? "Пробуем подключить получателя" : "Подключаем наушники"
            detail = early ? "Источник остаётся подключённым до результата попытки" : "Ждём подтверждения аудиомаршрута"
            if tx.target == "android" { ble.send(Packet(type: early ? "tryAcquire" : "acquire", id: tx.id)) }
            else {
                audio.acquire(address: selected) { [weak self] ok, reason, retrySafe in
                    guard let self, self.transfer?.id == tx.id else { return }
                    self.trace("AUDIO acquire tx=\(tx.id) early=\(early) ok=\(ok) retrySafe=\(retrySafe) elapsed=\(tx.elapsed) reason=\(reason)")
                    if ok { self.receive(Packet(type: early ? "earlyResult" : "result", id: tx.id, detail: reason)) }
                    else if early && retrySafe { self.receive(Packet(type: "retryable", id: tx.id, detail: reason)) }
                    else { self.fail(reason) }
                }
            }
        case .complete: finish(tx.acquisitionDetail.isEmpty ? "Аудиомаршрут получателя подтверждён" : tx.acquisitionDetail)
        }
    }

    private func finish(_ reason: String) {
        guard let tx = transfer else { return }
        trace("FINISH tx=\(tx.id) mode=\(tx.mode.rawValue) fallback=\(tx.fallback) total=\(tx.elapsed)")
        ble.send(Packet(type: "complete", id: tx.id, target: tx.target, detail: reason))
        headline = tx.target == "mac" ? "Звук на Mac" : "Телефон принял наушники"
        owner = tx.target; lastDuration = String(format: "%.1f с", tx.elapsed)
        successful += 1; save(successful, "transfers"); policy.completed(now: now, cooldown: cooldown); quietUntil = now + cooldown
        detail = reason; log("\(headline) · \(lastDuration)"); transfer = nil; busy = false; automaticTransfer = false; stage = ""; refresh()
    }
    private func fail(_ reason: String, source: String = "Mac") { policy.failed(); autoPaused = true; cancel(reason + "\nАвто приостановлено. Проверь подключение перед возобновлением.", source: source) }
    func cancel(_ reason: String, source: String = "Mac") {
        if let tx = transfer { trace("CANCEL tx=\(tx.id) stage=\(tx.stage.rawValue) elapsed=\(tx.elapsed) reason=\(reason)"); ble.send(Packet(type: "cancel", id: tx.id)) }
        audio.cancel(); transfer = nil; busy = false; automaticTransfer = false; stage = ""; quietUntil = now + 10
        headline = "Передача остановлена"; detail = reason; log(reason, source: source)
    }
    private func tick() {
        guard !sleeping else { return }
        route = AudioDevices.currentName
        let snapshotDue = now >= nextSnapshot
        if snapshotDue { nextSnapshot = now + 3 }
        let observation = MediaMonitor.observe()
        discoveredSources.merge(observation.names) { _, new in new }
        let sources = observation.active.sorted().map { "\(observation.names[$0] ?? $0) [\($0)] · \(allowed.contains($0) ? "разрешён" : "не выбран")" }.joined(separator: "\n")
        if observedSources != sources { trace("MEDIA active=\(sources.isEmpty ? "none" : sources)"); observedSources = sources }
        let peerReady = now - peerReceived < 7 && peerReceived > 0 && peer.available && peer.automation && !peer.call && !peer.held
        edges.sample(observation.active.intersection(allowed), now: now, delay: detectionDelay,
                     suppress: !autoEnabled || !trusted || !peerReady || held || busy || observation.microphone || now < quietUntil || now < policy.blockedUntil || policy.suspended)
        mediaTimer?.invalidate(); mediaTimer = nil
        if let deadline = edges.nextDeadline(delay: detectionDelay) { mediaTimer = Timer.scheduledTimer(withTimeInterval: max(0.001, deadline - now), repeats: false) { [weak self] _ in self?.tick() } }
        let connected = AudioDevices.output(for: selected).map { AudioDevices.number(AudioObjectID(kAudioObjectSystemObject), kAudioHardwarePropertyDefaultOutputDevice) == $0 } ?? false
        local = ActivityState(available: observation.available, playing: !observation.active.isEmpty, call: observation.microphone,
                              held: held, automation: autoEnabled, event: edges.event,
                              source: MediaMonitor.sources.first { $0.0 == edges.source }?.1 ?? edges.source, connected: connected,
                              guardReason: observation.microphone ? "Используется микрофон Mac" : nil, handoffVersion: 2)
        if local != lastTracedLocal { lastTracedLocal = local; trace("LOCAL event=\(local.event) playing=\(local.playing) source=\(local.source) call=\(local.call)") }
        if busy && local.call { cancel("Микрофон начал использоваться. Передача остановлена") }
        if !busy {
            if owner == "mac" && !connected { owner = "unknown" }
            if owner == "android" && !peer.connected && peerReceived > 0 { owner = "unknown" }
            if handoffMode == .sequential && connected && owner == "unknown" { owner = "mac" }
        }
        if trusted, local != lastSent || snapshotDue, let data = try? JSONEncoder().encode(local), let json = String(data: data, encoding: .utf8) {
            ble.send(Packet(type: "activity", target: selectedName, detail: json, device: selected)); lastSent = local
        }
        let decision = policy.evaluate(mac: local, phone: peer, fresh: now - peerReceived < 7 && peerReceived > 0,
                                       linked: trusted, busy: busy, owner: owner, idleOnly: idleOnly, now: now)
        let nextReason = selectionMismatch && trusted ? "На устройствах выбраны разные наушники · открой Устройства" : decision.reason
        if autoReason != nextReason { trace("AUTO \(nextReason)") }; autoReason = nextReason; autoPaused = policy.suspended
        let gate = !selectionMismatch && trusted && peerReady && local.available && autoEnabled && !held && !local.call && !busy && !policy.suspended && now >= policy.blockedUntil && now >= quietUntil
        if trusted, snapshotDue || gate != lastGate {
            ble.send(Packet(type: "autoStatus", target: policy.suspended ? "paused" : "ready", detail: autoReason, device: gate ? "armed" : "blocked")); lastGate = gate
        }
        if !selectionMismatch, let target = decision.target { request(target, automatic: true) }
        if trusted, now >= nextPing { nextPing = now + 4; ble.send(Packet(type: "ping")) }
        if trusted, Date().timeIntervalSince(heartbeat) > 14 {
            if busy { policy.failed(); cancel("Телефон перестал отвечать во время передачи") }
            trusted = false; ble.stop(); peerReceived = 0
            if enabled && reconnect { startLink(reveal: false) }
        }
        if let tx = transfer, tx.elapsed >= 35 { fail("Время передачи истекло. Проверь текущий аудиовыход") }
    }
    func bluetoothSettings() {
        NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.BluetoothSettings")!)
    }
    func copyDiagnostics() {
        let text = "Seamless Headphones 0.4.0 · Mac\n\(ProcessInfo.processInfo.operatingSystemVersionString)\nBLE: \(link)\nАвто: \(autoReason)\nВыход: \(route)\n\(selectionSummary)\nТранзакция: \(transfer?.id ?? "нет") · \(stage)\nАудиоадаптер: \(audio.diagnostics(address: selected))\nСостояние Mac: available=\(local.available) playing=\(local.playing) call=\(local.call) held=\(held) connected=\(local.connected)\nСостояние Android: available=\(peer.available) playing=\(peer.playing) call=\(peer.call) held=\(peer.held) connected=\(peer.connected) age=\(peerReceived > 0 ? String(format: "%.1f", now - peerReceived) : "unknown")s\nАвто: enabled=\(autoEnabled) idleOnly=\(idleOnly) delay=\(delay) cooldown=\(cooldown)\nСпособ передачи: \(handoffMode.rawValue) · fastDetection=\(fastDetection) · delay=\(detectionDelay)\nПриоритет ручной команды: \(manualPriority) с\nЗащита Android: \(peer.guardReason ?? "нет сведений")\nАктивные источники Mac: \(observedSources)\nРазрешены: \(allowed.sorted().joined(separator: ", "))\nТехнические логи: \(debugEnabled)\n\nИстория этого Mac (источник в скобках):\n" + events.reversed().joined(separator: "\n") + "\n\nТехнический журнал Mac:\n" + debugEvents.reversed().joined(separator: "\n")
        NSPasteboard.general.clearContents(); NSPasteboard.general.setString(text, forType: .string)
    }
}
