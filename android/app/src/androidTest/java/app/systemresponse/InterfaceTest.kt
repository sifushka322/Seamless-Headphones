package app.systemresponse

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
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
import org.junit.Before
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class InterfaceTest {
    @Before fun startInRussian() {
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putString("language", "ru").commit()
    }
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
            onView(withText("☾  Тёмная")).perform(scrollTo(), click())
            onView(withText("✓  ☾  Тёмная")).perform(scrollTo()).check(matches(isDisplayed()))
            scenario.recreate()
            onView(withText("✓  ☾  Тёмная")).perform(scrollTo()).check(matches(isDisplayed()))
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
            onView(withContentDescription("Задержка автопереключения")).perform(scrollTo()).check(matches(isEnabled()))
            scenario.recreate()
            onView(withContentDescription("Быстрое обнаружение · 0,5 с")).perform(scrollTo()).check(matches(isNotChecked()))
            capture("android-fast-detection")
            onView(withContentDescription("Быстрое обнаружение · 0,5 с")).perform(click()).check(matches(isChecked()))
            onView(withContentDescription("Задержка автопереключения")).perform(scrollTo()).check(matches(org.hamcrest.Matchers.not(isEnabled())))
        }
    }
    @Test fun disconnectedCommandsHaveReasonsAndDoNotPretendToChangeMode() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("Забрать на Android")).perform(scrollTo()).check(matches(org.hamcrest.Matchers.not(isEnabled())))
            onView(withText("Передать на Mac")).check(matches(org.hamcrest.Matchers.not(isEnabled())))
            onView(withContentDescription("Авто")).perform(click())
            onView(withText("Снять паузу автоматики")).perform(scrollTo()).check(matches(org.hamcrest.Matchers.not(isEnabled())))
            onView(withText("Следовать новому воспроизведению")).perform(scrollTo()).check(matches(org.hamcrest.Matchers.not(isEnabled())))
            onView(withText("Только когда источник на паузе")).perform(scrollTo()).check(matches(org.hamcrest.Matchers.not(isEnabled())))
            onView(withText(org.hamcrest.Matchers.containsString("Правило Mac ещё не получено"))).perform(scrollTo()).check(matches(isDisplayed()))
            capture("android-policy-offline")
        }
    }
    @Test fun explicitThemesOverrideSystemAndSystemThemeFollowsIt() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val originalNight = ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        try {
            setSystemNight(true)
            ctx.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putString("theme", "light").commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                assertPalette(scenario, "#F4F8F5")
                capture("android-light-system-dark")
                onView(withContentDescription("Настройки")).perform(click())
                onView(withText("◐  Как в системе")).perform(scrollTo(), click())
                onView(withText("✓  ◐  Как в системе")).perform(scrollTo()).check(matches(isDisplayed()))
                assertPalette(scenario, "#101517")
                capture("android-system-dark")
                listOf("Обзор", "Авто", "Устройства", "Настройки").forEachIndexed { index, page ->
                    onView(withContentDescription(page)).perform(click())
                    capture("android-system-dark-page-$index")
                }
                setSystemNight(false)
                onView(withText("✓  ◐  Как в системе")).perform(scrollTo()).check(matches(isDisplayed()))
                assertPalette(scenario, "#F4F8F5")
                capture("android-system-live-light")
                setSystemNight(true)
                onView(withText("✓  ◐  Как в системе")).perform(scrollTo()).check(matches(isDisplayed()))
                assertPalette(scenario, "#101517")
                capture("android-system-live-dark")
            }
            setSystemNight(false)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                assertPalette(scenario, "#F4F8F5")
                listOf("Обзор", "Авто", "Устройства", "Настройки").forEachIndexed { index, page ->
                    onView(withContentDescription(page)).perform(click())
                    capture("android-system-light-page-$index")
                }
                onView(withContentDescription("Настройки")).perform(click())
                onView(withText("☾  Тёмная")).perform(scrollTo(), click())
                onView(withText("✓  ☾  Тёмная")).perform(scrollTo()).check(matches(isDisplayed()))
                assertPalette(scenario, "#101517")
                onView(withText("Кобальт")).perform(scrollTo(), click())
                onView(withText("✓  Кобальт")).perform(scrollTo()).check(matches(isDisplayed()))
                scenario.recreate()
                onView(withText("✓  Кобальт")).perform(scrollTo()).check(matches(isDisplayed()))
                capture("android-dark-cobalt-system-light")
            }
        } finally {
            setSystemNight(originalNight)
            ctx.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putString("theme", "system").putString("accent", "mint").commit()
        }
    }
    @Test fun englishCoversAllPagesDialogsScannerAndPersists() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        listOf("CAMERA", "BLUETOOTH_CONNECT", "BLUETOOTH_SCAN").forEach { permission ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("pm grant ${instrumentation.targetContext.packageName} android.permission.$permission")).use { it.readBytes() }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withContentDescription("Настройки")).perform(click())
            onView(withContentDescription("language-en")).perform(scrollTo(), click())
            onView(withText("✓  English")).perform(scrollTo()).check(matches(isDisplayed()))
            scenario.recreate()
            onView(withText("✓  English")).perform(scrollTo()).check(matches(isDisplayed()))
            listOf("Overview", "Auto", "Devices", "Settings").forEachIndexed { index, page ->
                onView(withContentDescription(page)).perform(click())
                assertEnglishPresentation(scenario)
                capture("android-english-page-$index")
            }
            onView(withContentDescription("Auto")).perform(click())
            onView(withText("Follow new playback")).perform(scrollTo()).check(matches(org.hamcrest.Matchers.not(isEnabled())))
            capture("android-english-rules")
            onView(withContentDescription("Fast detection · 0.5 s")).perform(scrollTo()).check(matches(isDisplayed()))
            assertEnglishPresentation(scenario)
            onView(withContentDescription("Devices")).perform(click())
            onView(withText("Forget Mac")).perform(scrollTo(), click())
            onView(withText("Delete Mac pairing key?")).check(matches(isDisplayed()))
            onView(withText("Cancel")).perform(click())
            onView(withText("Scan QR code from Mac")).perform(scrollTo(), click())
            onView(withText("Point the camera at the QR code in Seamless on your Mac")).check(matches(isDisplayed()))
            onView(withText("Close scanner")).perform(click())
            onView(withContentDescription("Settings")).perform(click())
            onView(withContentDescription("language-system")).perform(scrollTo(), click())
            scenario.onActivity { activity ->
                org.junit.Assert.assertEquals(android.content.res.Resources.getSystem().configuration.locales[0].language != "ru", L.english(activity))
            }
            onView(withContentDescription("language-ru")).perform(scrollTo(), click())
            onView(withText("✓  Русский")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withContentDescription("Обзор")).perform(click())
            onView(withText("Начнём с подключения")).check(matches(isDisplayed()))
        }
    }
    @Test fun englishDynamicMessagesKeepNamesAndTranslateHistory() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putString("language", "en").commit()
        org.junit.Assert.assertEquals("17:10:00 [Mac] Audio is routed to Mac · 2.4 s", L.text(ctx, "17:10:00 [Mac] Звук на Mac · 2.4 с"))
        org.junit.Assert.assertEquals("New playback: Музыка", L.text(ctx, "Новое воспроизведение: Музыка"))
        org.junit.Assert.assertEquals("Мои наушники [app.player] · allowed", L.text(ctx, "Мои наушники [app.player] · разрешён"))
        org.junit.Assert.assertEquals("Music: Музыка [app.one] · allowed, Видео [app.two] · not selected", L.text(ctx, "Музыка: Музыка [app.one] · разрешён, Видео [app.two] · не выбран"))
        org.junit.Assert.assertEquals("Mac: Музыка [AA:BB:CC:DD:EE:FF]\nAndroid: Мои наушники [AA:BB:CC:DD:EE:FF]", L.text(ctx, "Mac: Музыка [AA:BB:CC:DD:EE:FF]\nAndroid: Мои наушники [AA:BB:CC:DD:EE:FF]"))
        val noSelection = L.text(ctx, "Android: Не выбраны []\nMac: ожидаем сведения [неизвестно]")
        org.junit.Assert.assertFalse(noSelection, Regex("[А-Яа-яЁё]").containsMatchIn(noSelection))
        org.junit.Assert.assertEquals("Waiting after transfer · 11 s", L.text(ctx, "Пауза после переключения · 11 с"))
        org.junit.Assert.assertEquals("17:10:00 [Android] TX type=activity tx= id=123", L.text(ctx, "17:10:00 [Android] TX type=activity tx= id=123"))
        val report = L.text(ctx, "Seamless Headphones\nАвто: Пауза после переключения · 11 с\n\n17:10:00 [Mac] Звук на Mac · 2.4 с")
        org.junit.Assert.assertFalse(report, Regex("[А-Яа-яЁё]").containsMatchIn(report))
        val disconnectedReport = L.text(ctx, "Seamless Headphones\nBLE: Связь выключена\nAndroid: Не выбраны [не выбраны]\nMac: ожидаем сведения [неизвестно]\nАвто: Ожидаем связь с Mac")
        org.junit.Assert.assertFalse(disconnectedReport, Regex("[А-Яа-яЁё]").containsMatchIn(disconnectedReport))
        val namedReport = L.text(ctx, "Seamless Headphones\nBLE: Связь выключена\nMac: Музыка [AA:BB:CC:DD:EE:FF]\nAndroid: Мои наушники [AA:BB:CC:DD:EE:FF]")
        org.junit.Assert.assertTrue(namedReport, namedReport.contains("Mac: Музыка [AA:BB:CC:DD:EE:FF]"))
        org.junit.Assert.assertTrue(namedReport, namedReport.contains("Android: Мои наушники [AA:BB:CC:DD:EE:FF]"))
        ctx.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putString("language", "ru").commit()
        org.junit.Assert.assertEquals("Пауза после переключения · 11 с", L.text(ctx, "Пауза после переключения · 11 с"))
    }
    private fun assertEnglishPresentation(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            fun inspect(view: android.view.View) {
                if (view is android.widget.TextView && view.tag != "user-content" && view.tag != "raw-log") {
                    val value = view.text.toString().removePrefix("✓  ")
                    if (value != "Русский") org.junit.Assert.assertFalse("Untranslated UI text: $value", Regex("[А-Яа-яЁё]").containsMatchIn(value))
                }
                if (view is ViewGroup) for (index in 0 until view.childCount) inspect(view.getChildAt(index))
            }
            inspect(activity.findViewById(android.R.id.content))
        }
    }
    private fun assertPalette(scenario: ActivityScenario<MainActivity>, expected: String) {
        scenario.onActivity { activity ->
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            org.junit.Assert.assertEquals(Color.parseColor(expected), (root.background as ColorDrawable).color)
            val flags = activity.obtainStyledAttributes(intArrayOf(android.R.attr.forceDarkAllowed))
            try { org.junit.Assert.assertFalse("System force-dark must not override the selected palette", flags.getBoolean(0, true)) } finally { flags.recycle() }
        }
    }
    private fun setSystemNight(dark: Boolean) {
        val command = "cmd uimode night ${if (dark) "yes" else "no"}"
        android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val isDark = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
            if (isDark == dark) return
            Thread.sleep(100)
        }
        org.junit.Assert.fail("System theme did not change")
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
