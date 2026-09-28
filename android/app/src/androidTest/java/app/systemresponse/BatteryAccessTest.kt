package app.systemresponse

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.intent.IntentStubberRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class BatteryAccessTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val prefs get() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private var originallyExempt = false
    private var originalSuggestion: Boolean? = null
    private var originalKey: ByteArray? = null

    @Before fun prepare() {
        originallyExempt = BatteryAccess.isExempt(context) == true
        originalSuggestion = if (prefs.contains(BatteryAccess.SUGGESTION_SEEN)) prefs.getBoolean(BatteryAccess.SUGGESTION_SEEN, false) else null
        originalKey = SecretStore(context).load()
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putString("language", "ru").commit()
        prefs.edit().remove(BatteryAccess.SUGGESTION_SEEN).commit()
        setExempt(false)
    }

    @After fun restore() {
        if (IntentStubberRegistry.isLoaded()) IntentStubberRegistry.reset()
        setExempt(originallyExempt)
        prefs.edit().apply {
            originalSuggestion?.let { putBoolean(BatteryAccess.SUGGESTION_SEEN, it) } ?: remove(BatteryAccess.SUGGESTION_SEEN)
        }.commit()
        originalKey?.let { SecretStore(context).save(Base64.getEncoder().encodeToString(it)) } ?: SecretStore(context).clear()
    }

    @Test fun statusTracksSystemExemptionAfterResumeAndRecreation() {
        assertTrue(BatteryAccess.shouldSuggest(context))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withContentDescription("Настройки")).perform(click())
            onView(withText("Оптимизация батареи включена")).perform(scrollTo()).check(matches(isDisplayed()))
            scenario.moveToState(Lifecycle.State.STARTED)
            setExempt(true)
            scenario.moveToState(Lifecycle.State.RESUMED)
            onView(withText("✓  Исключение из оптимизации батареи включено")).perform(scrollTo()).check(matches(isDisplayed()))
            assertFalse(BatteryAccess.shouldSuggest(context))
            scenario.recreate()
            onView(withText("✓  Исключение из оптимизации батареи включено")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Настройки батареи")).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }

    @Test fun firstConnectionCanBeDeferredWithoutRepeatingAfterRecreation() {
        prepareConnection()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                onView(withText("Найти Mac")).perform(scrollTo(), click())
                onView(withText("Разрешить работу Sound Shift в фоне?")).check(matches(isDisplayed()))
                onView(withText("Позже")).perform(click())
                assertTrue(prefs.getBoolean(BatteryAccess.SUGGESTION_SEEN, false))
                assertFalse(BatteryAccess.shouldSuggest(context))
                assertEquals(false, BatteryAccess.isExempt(context))
                scenario.recreate()
                onView(withText("Разрешить работу Sound Shift в фоне?")).check(doesNotExist())
                stopConnection(scenario)
                onView(withText("Найти Mac")).perform(scrollTo(), click())
                onView(withText("Разрешить работу Sound Shift в фоне?")).check(doesNotExist())
            } finally { stopConnection(scenario) }
        }
    }

    @Test fun firstConnectionAllowOpensOnlyTheSystemRequestForThisPackage() {
        prepareConnection()
        val requests = recordAndCancelSystemBatteryRequests()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                onView(withText("Найти Mac")).perform(scrollTo(), click())
                onView(withText("Разрешить работу Sound Shift в фоне?")).check(matches(isDisplayed()))
                assertTrue("The system exemption request requires an explicit choice", requests.isEmpty())
                onView(withText("Разрешить")).perform(click())
                assertEquals(1, requests.size)
                assertEquals("package:${context.packageName}", requests.single().dataString)
                assertEquals(false, BatteryAccess.isExempt(context))
                assertFalse(BatteryAccess.shouldSuggest(context))
                scenario.recreate()
                onView(withText("Разрешить работу Sound Shift в фоне?")).check(doesNotExist())
            } finally { stopConnection(scenario) }
        }
    }

    @Test fun settingsCanRequestExemptionAfterDeferralAndHandleCancellation() {
        BatteryAccess.markSuggestionShown(context)
        val requests = recordAndCancelSystemBatteryRequests()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withContentDescription("Настройки")).perform(click())
            onView(withText("Разрешить работу без оптимизации")).perform(scrollTo(), click())
            assertEquals(1, requests.size)
            assertEquals("package:${context.packageName}", requests.single().dataString)
            // Android's request returns no permission result. A cancelled request must never
            // be treated as approval; the next resume reads PowerManager again.
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            onView(withText("Оптимизация батареи включена")).perform(scrollTo()).check(matches(isDisplayed()))
            assertEquals(false, BatteryAccess.isExempt(context))
        }
    }

    private fun recordAndCancelSystemBatteryRequests(): CopyOnWriteArrayList<Intent> {
        val requests = CopyOnWriteArrayList<Intent>()
        IntentStubberRegistry.load { intent ->
            if (intent.action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) {
                requests.add(Intent(intent)); Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            } else null
        }
        return requests
    }

    private fun prepareConnection() {
        listOf("BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "POST_NOTIFICATIONS").forEach {
            if (it != "POST_NOTIFICATIONS" || android.os.Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.$it")
        }
        SecretStore(context).save(Base64.getEncoder().encodeToString(ByteArray(32) { 19 }))
    }

    private fun stopConnection(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.startService(Intent(it, ResponseService::class.java).setAction("stop")) }
        instrumentation.waitForIdleSync()
    }

    private fun setExempt(exempt: Boolean) {
        shell("cmd deviceidle whitelist ${if (exempt) "+" else "-"}${context.packageName}")
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        while (BatteryAccess.isExempt(context) != exempt && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertEquals("Could not set battery exemption on the test device", exempt, BatteryAccess.isExempt(context))
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
}
