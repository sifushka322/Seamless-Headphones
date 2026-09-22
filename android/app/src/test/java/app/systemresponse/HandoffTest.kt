package app.systemresponse

import org.junit.Assert.*
import org.junit.Test

class HandoffTest {
    @Test fun normalCommandsRunOnceAndOnlyOnCorrectDevice() {
        val phone = HandoffCommands("android")
        assertFalse(phone.canComplete()); assertFalse(phone.accept("release")); assertTrue(phone.accept("acquire"))
        assertFalse(phone.accept("acquire")); assertFalse(phone.accept("tryAcquire")); assertFalse(phone.canComplete())
        assertEquals("result", phone.result("acquire", true, false)); assertTrue(phone.canComplete())
        assertFalse(phone.accept("acquire"))
        val source = HandoffCommands("mac")
        assertTrue(source.canComplete()) // Receiver-first Mac may finish without asking phone to release.
        assertFalse(source.accept("acquire")); assertTrue(source.accept("release")); assertFalse(source.canComplete())
        assertEquals("released", source.result("release", true, false)); assertTrue(source.canComplete())
    }
    @Test fun terminalRejectionAllowsExactlyOneNormalRetry() {
        val gate = HandoffCommands("android")
        assertTrue(gate.accept("tryAcquire")); assertEquals("retryable", gate.result("tryAcquire", false, true))
        assertFalse(gate.canComplete()); assertFalse(gate.accept("tryAcquire")); assertTrue(gate.accept("acquire"))
        assertEquals("error", gate.result("acquire", false, true)); assertFalse(gate.accept("acquire"))
    }
    @Test fun uncertainFailureNeverAuthorizesRetry() {
        val gate = HandoffCommands("android")
        assertTrue(gate.accept("tryAcquire")); assertEquals("error", gate.result("tryAcquire", false, false))
        assertFalse(gate.accept("acquire")); assertFalse(gate.canComplete())
    }
    @Test fun earlySuccessCannotBeFollowedByAnotherPhysicalOperation() {
        val gate = HandoffCommands("android")
        assertTrue(gate.accept("tryAcquire")); assertEquals("earlyResult", gate.result("tryAcquire", true, false))
        assertTrue(gate.canComplete()); assertFalse(gate.accept("acquire")); assertFalse(gate.accept("release"))
    }
    @Test fun callbackDebounceDeadlineAndCapabilityRoundTrip() {
        val edges = MediaEdges()
        edges.sample(emptySet(), 0, 500, false); edges.sample(setOf("music"), 100, 500, false)
        assertEquals(600L, edges.nextDeadline(500))
        edges.sample(setOf("music"), 599, 500, false); assertEquals(0, edges.event)
        edges.sample(setOf("music"), 600, 500, false); assertEquals(1, edges.event); assertNull(edges.nextDeadline(500))
        val state = ActivityState(available = true, handoffVersion = 2)
        assertEquals(state, ActivityState.parse(state.json()))
        assertNull(ActivityState.parse(org.json.JSONObject(state.json()).apply { remove("handoffVersion") }.toString()).handoffVersion)
    }
}
