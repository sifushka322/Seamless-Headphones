import Foundation

struct ActivityState: Codable, Equatable {
    var available = false
    var playing = false
    var call = false
    var held = false
    var automation = false
    var event = 0
    var source = ""
    var connected = false
    var guardReason: String? = nil
}

/// Debounces starts, not continuous playback. Suppression consumes starts instead of postponing them.
struct MediaEdges {
    private var previous: Set<String>?
    private var pending: [String: Double] = [:]
    private(set) var event = 0
    private(set) var source = ""
    mutating func sample(_ active: Set<String>, now: Double, delay: Double, suppress: Bool) {
        defer { previous = active }
        guard let previous, !suppress else { pending.removeAll(); return }
        for key in active.subtracting(previous) { pending[key] = now }
        pending = pending.filter { active.contains($0.key) }
        let matured = pending.filter { now - $0.value >= delay }.keys.sorted()
        if let first = matured.first {
            event += 1; source = first
            for key in matured { pending.removeValue(forKey: key) }
        }
    }
    mutating func reset() { previous = nil; pending.removeAll() }
}

struct AutoDecision: Equatable {
    var target: String? = nil
    var reason: String
}

struct AutoPolicy {
    private var macEvent: Int?
    private var phoneEvent: Int?
    private(set) var blockedUntil: Double = 0
    private(set) var suspended = false
    mutating func resetSession() { macEvent = nil; phoneEvent = nil }
    mutating func manual(now: Double, duration: Double = 15) { blockedUntil = now + max(0, duration) }
    mutating func completed(now: Double, cooldown: Double) { blockedUntil = max(blockedUntil, now + cooldown) }
    mutating func failed() { suspended = true }
    mutating func resume() { suspended = false; blockedUntil = 0; resetSession() }
    mutating func evaluate(mac: ActivityState, phone: ActivityState, fresh: Bool, linked: Bool,
                           busy: Bool, owner: String, idleOnly: Bool, now: Double) -> AutoDecision {
        let macStarted = macEvent != nil && mac.event > macEvent!
        let phoneStarted = phoneEvent != nil && phone.event > phoneEvent!
        macEvent = mac.event; phoneEvent = phone.event
        if !linked { return AutoDecision(reason: "Ожидаем связь с телефоном") }
        if !fresh { return AutoDecision(reason: "Ожидаем актуальное состояние телефона") }
        if !mac.automation || !phone.automation { return AutoDecision(reason: "Включи автоматизацию на обоих устройствах") }
        if suspended { return AutoDecision(reason: "Авто на паузе после ошибки · требуется возобновление") }
        if !mac.available || !phone.available { return AutoDecision(reason: "Настрой доступ к воспроизведению и защите звонков") }
        if mac.call { return AutoDecision(reason: "Mac: " + (mac.guardReason ?? "используется микрофон")) }
        if phone.call { return AutoDecision(reason: "Android: " + (phone.guardReason ?? "сигнал разговора или микрофона")) }
        if mac.held || phone.held { return AutoDecision(reason: "На одном из устройств включено удержание") }
        if busy { return AutoDecision(reason: "Передача уже выполняется") }
        if now < blockedUntil { return AutoDecision(reason: "Пауза после переключения · \(Int(ceil(blockedUntil - now))) с") }
        // If starts coincide in one evaluation, the coordinator uses a stable Mac-first tie break.
        for (target, started, destination, source) in [("mac", macStarted, mac, phone), ("android", phoneStarted, phone, mac)] {
            guard started, destination.playing, owner != target else { continue }
            if idleOnly && source.playing { return AutoDecision(reason: "Источник ещё играет · ждём новое воспроизведение после паузы") }
            return AutoDecision(target: target, reason: "Новое воспроизведение: \(destination.source)")
        }
        return AutoDecision(reason: "Готово · запусти музыку заново в разрешённом приложении")
    }
}
