package app.systemresponse

import org.junit.Assert.*
import org.junit.Test

class AutomationTest {
    @Test fun baselineAndShortSoundsNeverTrigger() {
        val edge = MediaEdges()
        edge.sample(setOf("music"), 0, 2000, false)
        edge.sample(setOf("music"), 10_000, 2000, false)
        assertEquals(0, edge.event)
        edge.sample(emptySet(), 11_000, 2000, false)
        edge.sample(setOf("music"), 12_000, 2000, false)
        edge.sample(emptySet(), 13_000, 2000, false)
        assertEquals(0, edge.event)
    }
    @Test fun stableEdgeEmitsOnceAndSuppressionConsumesStarts() {
        val edge = MediaEdges()
        edge.sample(emptySet(), 0, 2000, false)
        edge.sample(setOf("music"), 1000, 2000, false)
        edge.sample(setOf("music"), 3000, 2000, false)
        assertEquals(1, edge.event)
        edge.sample(setOf("music"), 10_000, 2000, false)
        assertEquals(1, edge.event)
        edge.sample(setOf("video"), 11_000, 2000, true)
        edge.sample(setOf("video"), 14_000, 2000, false)
        assertEquals(1, edge.event)
        edge.reset(); edge.sample(setOf("video"), 30_000, 2000, false)
        assertEquals(1, edge.event)
    }
    @Test fun activitySchemaRoundTripsAndRejectsInvalidData() {
        val value = ActivityState(true, true, false, true, true, 10, "Музыка", true)
        assertEquals(value, ActivityState.parse(value.json()))
        assertThrows(Exception::class.java) { ActivityState.parse("{}") }
        assertThrows(IllegalArgumentException::class.java) { ActivityState.parse(value.copy(event = -1).json()) }
        assertThrows(IllegalArgumentException::class.java) { ActivityState.parse(value.copy(source = "x".repeat(201)).json()) }
    }
}
