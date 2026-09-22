package app.systemresponse

/** Pure decision logic. Android mode values are stable platform constants (0..6). */
data class VoiceSignals(val telephony: Int?, val mode: Int, val recording: Boolean?, val voicePlayback: Boolean?, val media: Boolean)
data class VoiceDecision(val known: Boolean, val busy: Boolean, val reason: String)
/** Keep affirmative media-only evidence across Pause, so stale mode does not block the reverse handoff. */
class VoiceGuardTracker {
    private var mediaOnlyCommunication = false
    fun evaluate(signals: VoiceSignals): VoiceDecision {
        if (signals.mode != 3 || signals.telephony != 0 || signals.recording != false || signals.voicePlayback != false) {
            mediaOnlyCommunication = false
        } else if (signals.media) {
            mediaOnlyCommunication = true
        }
        val decision = VoiceGuardPolicy.evaluate(signals.copy(media = signals.media || mediaOnlyCommunication))
        return if (mediaOnlyCommunication && !signals.media && !decision.busy) decision.copy(reason = "Режим общения остался после музыки; голосовых потоков нет") else decision
    }
}
object VoiceGuardPolicy {
    fun evaluate(s: VoiceSignals): VoiceDecision {
        if (s.telephony != null && s.telephony != 0) return VoiceDecision(true, true, if (s.telephony == 1) "Входящий телефонный вызов" else "Телефонный разговор")
        if (s.recording == true) return VoiceDecision(s.telephony != null, true, "На Android используется микрофон")
        if (s.voicePlayback == true) return VoiceDecision(s.telephony != null, true, "Активен голосовой аудиопоток Android")
        if (s.telephony == null) return VoiceDecision(false, s.mode != 0, "Нет доступа к состоянию телефонных вызовов")
        if (s.mode == 0) return VoiceDecision(true, false, "Разговор не обнаружен")
        // A communication mode can remain set after a messenger closes its audio streams.
        // Ignore only with affirmative evidence: idle telephony, no recording/voice streams,
        // and active media. Missing observations never authorize this compatibility case.
        if (s.mode == 3 && s.recording == false && s.voicePlayback == false && s.media) {
            return VoiceDecision(true, false, "Режим общения без голосовых потоков; играет музыка")
        }
        return VoiceDecision(true, true, when (s.mode) {
            1 -> "Android сообщает режим звонка"
            2 -> "Android сообщает телефонный разговор"
            3 -> "Android сообщает голосовой режим"
            4 -> "Android проверяет входящий вызов"
            5, 6 -> "Android перенаправляет разговор"
            else -> "Неизвестный аудиорежим Android: ${s.mode}"
        })
    }
}
