package app.systemresponse

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
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
            onView(withText("Два разрешения для автоматики")).check(matches(isDisplayed()))
            capture("android-automation")
            onView(withContentDescription("Устройства")).perform(click())
            onView(withText("01  /  Твои наушники")).check(matches(isDisplayed()))
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
    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
