package app.systemresponse

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** Android's Doze exemption does not describe additional manufacturer background restrictions. */
object BatteryAccess {
    const val SUGGESTION_SEEN = "batterySuggestionSeen"

    fun isExempt(context: Context): Boolean? = runCatching {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrNull()

    fun shouldSuggest(context: Context): Boolean = isExempt(context) == false &&
        !context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(SUGGESTION_SEEN, false)

    fun markSuggestionShown(context: Context) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean(SUGGESTION_SEEN, true).apply()
    }

    // Sound Shift's core feature is timely playback-triggered automation over an ongoing BLE link.
    // The direct request is optional and is only launched after the user's explicit Allow action.
    @SuppressLint("BatteryLife")
    fun openSettings(activity: Activity, requestExemption: Boolean): Boolean {
        val app = Uri.parse("package:${activity.packageName}")
        val intents = buildList {
            if (requestExemption && isExempt(activity) == false) {
                add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, app))
            }
            add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app))
        }
        for (intent in intents) {
            try { activity.startActivity(intent); return true }
            catch (_: ActivityNotFoundException) { /* Some manufacturers omit this settings screen. */ }
            catch (_: SecurityException) { /* Fall back to settings accessible to ordinary apps. */ }
        }
        return false
    }
}
