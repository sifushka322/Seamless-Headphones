package app.systemresponse

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.telephony.TelephonyManager

// No notification content is read. The permission grants access to platform media sessions.
class MediaListener : NotificationListenerService()

data class CallState(val known: Boolean, val busy: Boolean, val reason: String, val diagnostics: String)
object CallGuard {
    private val tracker = VoiceGuardTracker()
    fun read(context: Context): CallState {
        val audio = context.getSystemService(AudioManager::class.java)
        val mode = runCatching { audio.mode }.getOrDefault(-1)
        val phone = when {
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) -> TelephonyManager.CALL_STATE_IDLE
            context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED -> null
            else -> try { context.getSystemService(TelephonyManager::class.java).callState } catch (_: Exception) { null }
        }
        val recording = runCatching { audio.activeRecordingConfigurations.isNotEmpty() }.getOrNull()
        val voice = runCatching { audio.activePlaybackConfigurations.any {
            it.audioAttributes.usage == android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION ||
                it.audioAttributes.usage == android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING
        } }.getOrNull()
        val media = runCatching { audio.isMusicActive }.getOrDefault(false)
        val decision = tracker.evaluate(VoiceSignals(phone, mode, recording, voice, media))
        return CallState(decision.known, decision.busy, decision.reason,
            "phone=$phone mode=$mode recording=$recording voicePlayback=$voice musicActive=$media")
    }
}

data class MediaObservation(val available: Boolean, val playing: Set<String>, val discovered: Set<String>)
class MediaMonitor(private val context: Context) {
    companion object {
        val known = linkedMapOf("com.spotify.music" to "Spotify", "com.google.android.apps.youtube.music" to "YouTube Music",
            "com.apple.android.music" to "Apple Music", "org.videolan.vlc" to "VLC", "com.maxmpz.audioplayer" to "Poweramp",
            "com.google.android.youtube" to "YouTube", "ru.yandex.music" to "Яндекс Музыка")
    }
    fun sample(): MediaObservation = try {
        val sessions = context.getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(context, MediaListener::class.java))
        MediaObservation(true, sessions.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }.map { it.packageName }.toSet(), sessions.map { it.packageName }.toSet())
    } catch (_: SecurityException) { MediaObservation(false, emptySet(), emptySet()) }
    catch (_: RuntimeException) { MediaObservation(false, emptySet(), emptySet()) }
    fun name(pkg: String): String = known[pkg] ?: runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
}
