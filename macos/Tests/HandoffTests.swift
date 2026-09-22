import Foundation

@main struct HandoffTests {
    static func main() {
        var count = 0
        func check(_ condition: @autoclosure () -> Bool, _ title: String) { precondition(condition(), title); count += 1; print("PASS \(title)") }
        for target in ["mac", "android"] {
            for order in [["released", "result"], ["result", "released"]] {
                var tx = Transfer(target: target, id: "t", mode: .parallel)
                check(!tx.accept(Packet(type: "result", id: "t")), "no result before command")
                check(tx.accept(Packet(type: "ready", id: "t")) && tx.actions == [.release, .acquire], "parallel issues both exactly once")
                check(!tx.accept(Packet(type: "ready", id: "t")), "duplicate readiness has no effects")
                check(tx.accept(Packet(type: order[0], id: "t")) && tx.actions.isEmpty, "first parallel reply waits for second")
                check(!tx.accept(Packet(type: order[0], id: "t")), "duplicate parallel reply ignored")
                check(tx.accept(Packet(type: order[1], id: "t")) && tx.actions == [.complete], "both reply orders finish exactly once")
                check(!tx.accept(Packet(type: order[1], id: "t")), "completed transaction cannot act twice")
            }
            var tx = Transfer(target: target, id: "t", mode: .receiverFirst)
            check(tx.accept(Packet(type: "ready", id: "t")) && tx.actions == [.tryAcquire], "receiver first leaves source connected")
            check(!tx.accept(Packet(type: "released", id: "t")), "unsolicited release cannot start normal acquisition")
            check(!tx.accept(Packet(type: "result", id: "t")), "normal result cannot satisfy early attempt")
            check(tx.accept(Packet(type: "earlyResult", id: "t")) && tx.actions == [.complete], "early route success needs no release")
            check(!tx.releaseStarted, "early success never schedules source disconnect")
            check(!tx.accept(Packet(type: "retryable", id: "t")), "late rejection after success cannot disconnect source")
            var fallback = Transfer(target: target, id: "f", mode: .receiverFirst)
            _ = fallback.accept(Packet(type: "ready", id: "f"))
            check(!fallback.accept(Packet(type: "retryable", id: "old")), "old transaction cannot initiate fallback")
            check(fallback.accept(Packet(type: "retryable", id: "f")) && fallback.actions == [.release], "terminal refusal starts fallback release")
            check(!fallback.accept(Packet(type: "retryable", id: "f")), "only one fallback permitted")
            check(!fallback.accept(Packet(type: "earlyResult", id: "f")), "late early result cannot finish fallback")
            check(fallback.accept(Packet(type: "released", id: "f")) && fallback.actions == [.acquire], "retry occurs only after release confirmation")
            check(fallback.accept(Packet(type: "result", id: "f")) && fallback.actions == [.complete], "fallback finishes after normal route verification")
            check(fallback.fallback, "timing report identifies fallback")
        }
        var edge = MediaEdges()
        edge.sample([], now: 0, delay: 0.5, suppress: false)
        edge.sample(["player"], now: 0.1, delay: 0.5, suppress: false)
        check(edge.nextDeadline(delay: 0.5) == 0.6, "callback schedules exact debounce deadline")
        edge.sample(["player"], now: 0.59, delay: 0.5, suppress: false)
        check(edge.event == 0, "fast mode still rejects premature playback")
        edge.sample(["player"], now: 0.61, delay: 0.5, suppress: false)
        check(edge.event == 1 && edge.nextDeadline(delay: 0.5) == nil, "deadline emits once and timer is disarmed")
        edge.sample(["other"], now: 1, delay: 0.5, suppress: true)
        check(edge.nextDeadline(delay: 0.5) == nil, "suppression clears scheduled takeover")
        print("\(count) handoff checks passed")
    }
}
