package app.systemresponse

import org.junit.Assert.*
import org.junit.Test

class VoiceGuardTest {
    private val music = VoiceSignals(0, 3, false, false, true)
    @Test fun mediaOnlyCommunicationModeDoesNotBlockMusic() {
        val result = VoiceGuardPolicy.evaluate(music)
        assertTrue(result.known); assertFalse(result.busy)
    }
    @Test fun actualCallsAndRecordingStillBlockInEveryMode() {
        for (mode in 0..6) {
            assertTrue(VoiceGuardPolicy.evaluate(music.copy(mode = mode, telephony = 1)).busy)
            assertTrue(VoiceGuardPolicy.evaluate(music.copy(mode = mode, telephony = 2)).busy)
            assertTrue(VoiceGuardPolicy.evaluate(music.copy(mode = mode, recording = true)).busy)
            assertTrue(VoiceGuardPolicy.evaluate(music.copy(mode = mode, voicePlayback = true)).busy)
        }
    }
    @Test fun missingObservationsNeverRelaxVoiceMode() {
        assertTrue(VoiceGuardPolicy.evaluate(music.copy(recording = null)).busy)
        assertTrue(VoiceGuardPolicy.evaluate(music.copy(voicePlayback = null)).busy)
        assertTrue(VoiceGuardPolicy.evaluate(music.copy(telephony = null)).busy)
        assertFalse(VoiceGuardPolicy.evaluate(music.copy(telephony = null)).known)
    }
    @Test fun otherVoiceModesAndIdleCommunicationRemainProtected() {
        for (mode in listOf(-1, 1, 2, 4, 5, 6)) assertTrue(VoiceGuardPolicy.evaluate(music.copy(mode = mode)).busy)
        assertTrue(VoiceGuardPolicy.evaluate(music.copy(media = false)).busy)
        assertFalse(VoiceGuardPolicy.evaluate(music.copy(mode = 0, media = false)).busy)
    }
    @Test fun guardReasonSurvivesWireAndLegacyStatesRemainReadable() {
        val current = ActivityState(true, true, false, false, true, 2, "Music", false, "Режим общения без голосовых потоков")
        assertEquals(current, ActivityState.parse(current.json()))
        val legacy = org.json.JSONObject(current.json()).apply { remove("guardReason") }.toString()
        assertNull(ActivityState.parse(legacy).guardReason)
    }
    @Test fun staleModeEvidenceSurvivesPauseButNeverARealCallOrMissingObservation() {
        val tracker = VoiceGuardTracker()
        assertTrue(tracker.evaluate(music.copy(media = false)).busy)
        assertFalse(tracker.evaluate(music).busy)
        assertFalse(tracker.evaluate(music.copy(media = false)).busy)
        assertTrue(tracker.evaluate(music.copy(recording = true, media = false)).busy)
        assertTrue(tracker.evaluate(music.copy(media = false)).busy)
        assertFalse(tracker.evaluate(music).busy)
        assertTrue(tracker.evaluate(music.copy(voicePlayback = null, media = false)).busy)
        assertTrue(tracker.evaluate(music.copy(media = false)).busy)
        assertFalse(tracker.evaluate(music).busy)
        assertFalse(tracker.evaluate(music.copy(mode = 0, media = false)).busy)
        assertTrue(tracker.evaluate(music.copy(media = false)).busy)
    }
}
