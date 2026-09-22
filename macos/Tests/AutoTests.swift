import Foundation

@main struct AutoTests {
    static func main() {
        var count = 0
        func check(_ test: @autoclosure () -> Bool, _ title: String) { guard test() else { fatalError(title) }; count += 1; print("PASS \(title)") }
        var mac = ActivityState(available: true, automation: true)
        var phone = mac
        var p = AutoPolicy()
        func decision(_ time: Double = 100, fresh: Bool = true, idle: Bool = false, owner: String = "unknown", busy: Bool = false) -> AutoDecision {
            p.evaluate(mac: mac, phone: phone, fresh: fresh, linked: true, busy: busy, owner: owner, idleOnly: idle, now: time)
        }
        check(decision().target == nil, "initial state is a baseline")
        phone.playing = true; phone.event = 1
        check(decision().target == "android", "new phone playback triggers transfer")
        check(decision().target == nil, "continuous playback cannot retrigger")
        mac.playing = true; mac.event = 1
        check(decision(idle: true).target == nil, "idle-only mode protects playing source")
        check(decision(idle: false).target == nil, "blocked event does not execute after changing mode")
        mac.event += 1; mac.call = true
        check(decision().target == nil, "Mac microphone protects both directions")
        mac.call = false; phone.call = true; phone.event += 1
        check(decision().target == nil, "phone call protects both directions")
        phone.call = false; phone.held = true; mac.event += 1
        check(decision().target == nil, "remote hold protects source")
        phone.held = false; mac.event += 1
        check(decision(fresh: false).target == nil, "stale peer cannot authorize transfer")
        check(decision().target == nil, "freshness restoration does not replay an old start")
        p.manual(now: 100); phone.event += 1
        check(decision(101).target == nil, "manual override blocks automation")
        check(decision(221).target == nil, "manual timeout does not replay a start")
        phone.event += 1
        check(decision(222).target == "android", "new start after manual timeout works")
        p.completed(now: 222, cooldown: 20); mac.event += 1
        check(decision(225).target == nil, "cooldown prevents ping pong")
        p.failed(); phone.event += 1
        check(decision(300).target == nil, "adapter failure opens safety circuit")
        p.resume(); check(decision(301).target == nil, "resume baselines running media")
        phone.event += 1; mac.event += 1
        check(decision(302).target == "mac", "simultaneous events have deterministic order")
        phone.event += 1
        check(decision(303, owner: "android").target == nil, "destination already owns audio")
        mac.available = false; mac.event += 1
        check(decision(304).target == nil, "unavailable observation fails closed")
        mac.available = true; phone.automation = false; mac.event += 1
        check(decision(305).target == nil, "both devices must enable automation")
        phone.automation = true; mac.event += 1
        check(decision(306, busy: true).target == nil, "busy transaction cannot start another")
        var edge = MediaEdges()
        edge.sample(["Spotify"], now: 0, delay: 2, suppress: false)
        edge.sample(["Spotify"], now: 10, delay: 2, suppress: false)
        check(edge.event == 0, "startup media is ignored")
        edge.sample([], now: 11, delay: 2, suppress: false)
        edge.sample(["Spotify"], now: 12, delay: 2, suppress: false)
        edge.sample([], now: 13, delay: 2, suppress: false)
        check(edge.event == 0, "short playback pulse is ignored")
        edge.sample(["Spotify"], now: 15, delay: 2, suppress: false)
        edge.sample(["Spotify"], now: 17, delay: 2, suppress: false)
        check(edge.event == 1, "stable playback start matures at delay")
        edge.sample(["Spotify"], now: 30, delay: 2, suppress: false)
        check(edge.event == 1, "mature start emits once")
        edge.sample(["VLC"], now: 31, delay: 2, suppress: true)
        edge.sample(["VLC"], now: 34, delay: 2, suppress: false)
        check(edge.event == 1, "suppressed starts never become delayed takeovers")
        edge.reset(); edge.sample(["VLC"], now: 40, delay: 2, suppress: false)
        check(edge.event == 1, "reconnection resets baseline without resetting counter")
        mac.available = true; phone.automation = true
        p.manual(now: 400)
        check(p.blockedUntil == 415, "manual priority defaults to 15 seconds, not two minutes")
        p.completed(now: 402, cooldown: 20)
        p.resume()
        check(p.blockedUntil == 0, "explicit resume clears cooldown and manual priority")
        check(decision(403).target == nil, "reset never replays current playback")
        phone.event += 1
        check(decision(404).target == "android", "new playback works immediately after reset")
        phone.call = true; phone.guardReason = "Активен голосовой аудиопоток Android"; mac.event += 1
        check(decision(405).reason.contains("голосовой аудиопоток"), "precise remote call reason is visible")
        check(decision(406).target == nil, "reset does not bypass a call")
        let legacy = "{\"available\":true,\"playing\":false,\"call\":false,\"held\":false,\"automation\":true,\"event\":0,\"source\":\"\",\"connected\":false}"
        check((try? JSONDecoder().decode(ActivityState.self, from: Data(legacy.utf8)))?.guardReason == nil, "legacy activity schema remains readable")
        print("\(count) automation checks passed")
    }
}
