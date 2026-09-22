package app.systemresponse

import org.junit.Assert.*
import org.junit.Test

class ControlRequestTest {
    @Test fun waitsForItsOwnAcknowledgement() {
        val request = ControlRequest()
        assertTrue(request.begin("mode-1", "mode", 100))
        assertFalse(request.accept("older", "mode"))
        assertFalse(request.accept("mode-1", "resume"))
        assertEquals("mode", request.kind)
        assertTrue(request.accept("mode-1", "mode"))
        assertNull(request.id)
        assertFalse(request.accept("mode-1", "mode"))
    }
    @Test fun noOverlappingControlAndBoundedWait() {
        val request = ControlRequest()
        request.begin("first", "mode", 100)
        assertFalse(request.begin("second", "resume", 200))
        assertFalse(request.expire(10_099))
        assertTrue(request.expire(10_100))
        assertFalse(request.accept("first", "mode"))
        assertTrue(request.begin("second", "resume", 10_101))
    }
    @Test fun disconnectInvalidatesPendingCommand() {
        val request = ControlRequest()
        request.begin("a", "mode", 0); request.clear()
        assertNull(request.kind)
        assertFalse(request.accept("a", "mode"))
    }
    @Test fun snapshotSupportsConfirmedModeAndRouteWithoutBreakingLegacy() {
        val state = ActivityState(handoffVersion = 3, idleOnly = false, owner = "android")
        assertEquals(state, ActivityState.parse(state.json()))
        val legacy = """{"available":true,"playing":false,"call":false,"held":false,"automation":true,"event":0,"source":"","connected":false}"""
        assertNull(ActivityState.parse(legacy).idleOnly)
        assertNull(ActivityState.parse(legacy).owner)
    }
}
