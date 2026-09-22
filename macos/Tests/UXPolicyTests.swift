import Foundation

@main struct UXPolicyTests {
    static func main() throws {
        var count = 0
        func check(_ ok: Bool, _ message: String) { precondition(ok, message); count += 1; print("PASS \(message)") }
        var policy = AutoPolicy()
        var mac = ActivityState(available: true, automation: true)
        var phone = ActivityState(available: true, automation: true)
        func evaluate(_ time: Double, linked: Bool = true) -> AutoDecision {
            policy.evaluate(mac: mac, phone: phone, fresh: true, linked: linked, busy: false, owner: "unknown", idleOnly: false, now: time, quietUntil: 10)
        }
        _ = evaluate(0)
        phone.playing = true; phone.event = 1
        check(evaluate(1).target == nil, "cancel quiet period blocks new handoff")
        check(evaluate(2).reason.contains("8 с"), "blocked interval has visible countdown")
        check(evaluate(3, linked: false).reason.contains("связь"), "connection failure takes priority over cooldown")
        mac.held = true
        check(evaluate(4).reason.contains("запрет переключений"), "hold is not hidden by cooldown")
        mac.held = false; mac.automation = false
        check(evaluate(5).reason.contains("Включи"), "disabled automation is not reported ready")
        mac.automation = true
        check(evaluate(10).target == nil, "playback during quiet period is not replayed")
        phone.event += 1
        check(evaluate(11).target == "android", "new playback after quiet period works")
        check(!MediaSourcePolicy.canTrigger("com.apple.WebKit.GPU"), "ambiguous WebKit GPU cannot initiate handoff")
        check(!MediaSourcePolicy.canTrigger("unknown.42"), "unidentified process cannot initiate handoff")
        check(MediaSourcePolicy.canTrigger("com.apple.Safari"), "identified Safari remains available")
        check(MediaSourcePolicy.canTrigger("com.google.Chrome"), "identified Chrome remains available")
        let state = ActivityState(handoffVersion: 3, idleOnly: true, owner: "mac")
        check(try JSONDecoder().decode(ActivityState.self, from: JSONEncoder().encode(state)) == state, "confirmed mode and owner round trip")
        let legacy = "{\"available\":true,\"playing\":false,\"call\":false,\"held\":false,\"automation\":true,\"event\":0,\"source\":\"\",\"connected\":false}"
        let decoded = try JSONDecoder().decode(ActivityState.self, from: Data(legacy.utf8))
        check(decoded.idleOnly == nil && decoded.owner == nil, "legacy peers do not imply a confirmed mode or route")
        print("\(count) UX policy checks passed")
    }
}
