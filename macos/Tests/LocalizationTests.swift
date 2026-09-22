import Foundation

@main struct LocalizationTests {
    static func main() {
        var checks = 0
        func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
            checks += 1
            if !condition() { fatalError(message) }
        }
        let suite = "Seamless.LocalizationTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let localizer = UILocalization(defaults: defaults, preferredLanguages: { ["fr-FR", "ru-RU"] })
        expect(!localizer.isRussian, "A non-Russian system language must use English")
        localizer.language = .russian
        expect(UILocalization(defaults: defaults).language == .russian, "Language selection must persist")
        localizer.configure(demo: true, override: .english)
        expect(defaults.string(forKey: "language") == "ru", "Demo language must not change saved preferences")
        expect(localizer.text("Настройки") == "Settings", "Static UI catalog must load")
        expect(localizer.text("Не выбраны") == "Not selected", "Empty device selection must be translated")
        expect(localizer.text("Пауза после переключения · 11 с") == "Waiting after transfer · 11 s", "Countdown must translate")
        let progress = localizer.text("Android · источник отключён, получатель подключается · 3 с")
        expect(!progress.contains("источник") && !progress.contains("подключается") && progress.contains("Android"), "Progress and captured state fragments must translate")
        expect(localizer.text("Mac: Используется микрофон Mac") == "Mac: Mac microphone is in use", "Nested status wrappers must translate")
        expect(localizer.text("Новое воспроизведение: Мои наушники") == "New playback: Мои наушники", "Playback source names must remain verbatim")
        expect(localizer.text("Выход Mac: Мои наушники {0}") == "Mac output: Мои наушники {0}", "Output labels and literal placeholders must remain verbatim")
        let device = localizer.text("Mac: Наушники {1} [AA:BB:CC:DD:EE:FF]")
        expect(device == "Mac: Наушники {1} [AA:BB:CC:DD:EE:FF]", "Inserted placeholders must never be interpreted as template slots")
        let pair = "Mac: Mac name [AA]\nAndroid: Buds {0} [BB]"
        expect(localizer.text(pair) == pair, "Opaque captures in a multi-device template must not replace each other")
        let currentHistory = localizer.text("5:10:00 PM [Mac] Звук на Mac · 2.4 с")
        expect(currentHistory.hasPrefix("5:10:00 PM [Mac] ") && !currentHistory.contains("Звук"), "History must preserve timestamps and translate event bodies")
        let demoHistory = localizer.text("12:42:18  Звук передан на Mac · 2,4 с")
        expect(demoHistory.hasPrefix("12:42:18  ") && !demoHistory.contains("Звук"), "History without a source marker must translate")
        let multiline = localizer.text("Настройки\nПауза после переключения · 9 с")
        expect(multiline == "Settings\nWaiting after transfer · 9 s", "Generic duration templates must not consume multiline reports")
        expect(localizer.text("Custom source с") == "Custom source с", "Duration templates must only accept numbers")
        expect(localizer.text("No translation needed") == "No translation needed", "Unknown strings must pass through")
        for index in 0..<600 { _ = localizer.text("Пауза после переключения · \(index) с") }
        expect(localizer.text("Настройки") == "Settings", "Bounded cache eviction must retain translations")
        localizer.language = .russian
        expect(localizer.text("Настройки") == "Настройки", "Russian must remain unchanged")
        expect(localizer.text("Пауза после переключения · 11 с") == "Пауза после переключения · 11 с", "Language changes must not reuse English cache for Russian")
        let russianDefaults = UserDefaults(suiteName: suite + ".system")!
        defer { russianDefaults.removePersistentDomain(forName: suite + ".system") }
        let russianSystem = UILocalization(defaults: russianDefaults, preferredLanguages: { ["ru-RU", "en-US"] })
        expect(russianSystem.isRussian, "Russian system language must use Russian")
        print("Localization: \(checks) checks passed")
    }
}
