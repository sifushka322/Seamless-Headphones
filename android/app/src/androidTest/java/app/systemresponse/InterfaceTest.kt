package app.systemresponse

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class InterfaceTest {
    @Test fun coldLaunchNavigationAndThemeSurviveRecreation() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putString("theme", "light").commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText("Начнём с подключения")).check(matches(isDisplayed()))
            capture("android-light")
            onView(withContentDescription("Авто")).perform(click())
            onView(withText("Два разрешения для автоматики")).perform(scrollTo()).check(matches(isDisplayed()))
            capture("android-automation")
            onView(withContentDescription("Устройства")).perform(click())
            onView(withText("01  /  Твои наушники")).check(matches(isDisplayed()))
            capture("android-devices")
            onView(withText("Сканировать QR с Mac")).perform(scrollTo()).check(matches(isDisplayed()))
            capture("android-pairing")
            onView(withContentDescription("Настройки")).perform(click())
            onView(withText("☾  Тёмная")).perform(click())
            onView(withText("✓  ☾  Тёмная")).check(matches(isDisplayed()))
            scenario.recreate()
            onView(withText("✓  ☾  Тёмная")).check(matches(isDisplayed()))
            onView(withContentDescription("Обзор")).perform(click())
            capture("android-dark")
            onView(withText("Начнём с подключения")).check(matches(isDisplayed()))
        }
    }
    @Test fun diagnosticsButtonCopiesAReportFromOverview() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText("Скопировать диагностику")).perform(scrollTo(), click())
            scenario.onActivity { activity ->
                val clipboard = activity.getSystemService(android.content.ClipboardManager::class.java)
                val report = clipboard.primaryClip!!.getItemAt(0).coerceToText(activity).toString()
                org.junit.Assert.assertTrue(report.contains("Seamless Headphones"))
                org.junit.Assert.assertTrue(report.contains("Android"))
            }
            // Android's clipboard preview overlays the bottom navigation outside Espresso's view tree.
            // Let that system overlay dismiss before the next test taps a navigation item.
            Thread.sleep(6000)
        }
    }
    @Test fun scannerOpensAndCanBeCancelled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("pm grant ${instrumentation.targetContext.packageName} android.permission.CAMERA")).use { it.readBytes() }
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withContentDescription("Устройства")).perform(click())
            onView(withText("Сканировать QR с Mac")).perform(scrollTo(), click())
            onView(withText("Наведи камеру на QR-код в Seamless на Mac")).check(matches(isDisplayed()))
            capture("android-scanner")
            pressBack()
            onView(withText("Сканировать QR с Mac")).check(matches(isDisplayed()))
        }
    }
    @Test fun fastDetectionCanBeDisabledAndSurvivesRecreation() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("fastDetection", true).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withContentDescription("Авто")).perform(click())
            onView(withContentDescription("Быстрое обнаружение · 0,5 с")).perform(scrollTo(), click())
            scenario.recreate()
            onView(withContentDescription("Быстрое обнаружение · 0,5 с")).perform(scrollTo()).check(matches(isNotChecked()))
            capture("android-fast-detection")
            onView(withContentDescription("Быстрое обнаружение · 0,5 с")).perform(click()).check(matches(isChecked()))
        }
    }
    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Let the compositor finish page/scroll/window transitions before capturing.
        Thread.sleep(500)
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
