import Foundation
import Combine
import SwiftUI

enum UILanguage: String, CaseIterable, Identifiable {
    case system, english = "en", russian = "ru"
    var id: String { rawValue }
    var title: String { switch self { case .system: return "Как в системе"; case .english: return "English"; case .russian: return "Русский" } }
}

final class UILocalization: ObservableObject {
    static let shared = UILocalization()
    @Published var language: UILanguage {
        didSet { if persists { defaults.set(language.rawValue, forKey: "language") } }
    }
    var locale: Locale { Locale(identifier: isRussian ? "ru" : "en") }
    var isRussian: Bool { language == .russian || (language == .system && preferredLanguages().first?.lowercased().hasPrefix("ru") == true) }
    private let defaults: UserDefaults
    private let preferredLanguages: () -> [String]
    private var persists = true
    private var observer: NSObjectProtocol?
    private var exact: [String: String] = [:]
    private var templates: [Template] = []
    private var cache: [String: String] = [:]
    private let history = try! NSRegularExpression(pattern: #"^((?:.+?\[(?:Mac|Android)\]\s*)|(?:\d{1,2}:\d{2}:\d{2}(?:[.,]\d+)?(?:\s*[AP]M)?\s+))(.+)$"#)

    init(defaults: UserDefaults = .standard, preferredLanguages: @escaping () -> [String] = { Locale.preferredLanguages }) {
        self.defaults = defaults; self.preferredLanguages = preferredLanguages
        language = UILanguage(rawValue: defaults.string(forKey: "language") ?? "system") ?? .system
        loadCatalogs(allowDevelopmentFiles: false)
        observer = NotificationCenter.default.addObserver(forName: NSLocale.currentLocaleDidChangeNotification, object: nil, queue: .main) { [weak self] _ in self?.objectWillChange.send() }
    }
    deinit { if let observer { NotificationCenter.default.removeObserver(observer) } }

    func configure(demo: Bool, override: UILanguage? = nil) {
        persists = !demo
        loadCatalogs(allowDevelopmentFiles: demo)
        if demo, let override { language = override }
    }

    private func loadCatalogs(allowDevelopmentFiles: Bool) {
        exact.removeAll(); cache.removeAll()
        for name in ["backend-en", "macos-en"] {
            let bundled = Bundle.main.url(forResource: name, withExtension: "json")
            let development = URL(fileURLWithPath: FileManager.default.currentDirectoryPath).appendingPathComponent("localization/\(name).json")
            guard let url = bundled ?? (allowDevelopmentFiles ? development : nil),
                  let data = try? Data(contentsOf: url), let table = try? JSONDecoder().decode([String: String].self, from: data) else { continue }
            exact.merge(table) { _, new in new }
        }
        templates = exact.compactMap { Template(source: $0.key, target: $0.value) }.sorted { $0.specificity > $1.specificity }
    }

    func text(_ source: String) -> String {
        guard !isRussian else { return source }
        if let cached = cache[source] { return cached }
        let result = english(source, depth: 0)
        if cache.count >= 512 { cache.removeAll(keepingCapacity: true) }
        cache[source] = result
        return result
    }

    private func english(_ source: String, depth: Int) -> String {
        if let result = exact[source] { return result }
        guard depth < 8 else { return source }
        let ns = source as NSString
        if let match = history.firstMatch(in: source, range: NSRange(location: 0, length: ns.length)) {
            return ns.substring(with: match.range(at: 1)) + english(ns.substring(with: match.range(at: 2)), depth: depth + 1)
        }
        for template in templates {
            if source.contains("\n") && !template.multiline { continue }
            guard let match = template.regex.firstMatch(in: source, range: NSRange(location: 0, length: ns.length)) else { continue }
            var values: [String: String] = [:]
            for (capture, identifier) in template.identifiers.enumerated() {
                let raw = ns.substring(with: match.range(at: capture + 1))
                if template.numericDuration && raw.range(of: #"^[0-9]+(?:[.,][0-9]+)?$"#, options: .regularExpression) == nil { continue }
                let value = template.opaque ? raw : english(raw, depth: depth + 1)
                values[identifier] = value
            }
            if values.count == Set(template.identifiers).count { return template.render(values) }
        }
        if source.contains("\n") { return source.components(separatedBy: "\n").map { english($0, depth: depth + 1) }.joined(separator: "\n") }
        return source
    }

    private struct Template {
        private enum Piece { case literal(String), capture(String) }
        private let pieces: [Piece]
        let regex: NSRegularExpression
        let identifiers: [String]
        let specificity: Int
        let opaque: Bool
        let multiline: Bool
        let numericDuration: Bool
        func render(_ values: [String: String]) -> String {
            pieces.map { piece in switch piece { case .literal(let text): return text; case .capture(let id): return values[id] ?? "{\(id)}" } }.joined()
        }
        init?(source: String, target: String) {
            let marker = try! NSRegularExpression(pattern: #"\{([0-9]+)\}"#)
            let ns = source as NSString
            let matches = marker.matches(in: source, range: NSRange(location: 0, length: ns.length))
            guard !matches.isEmpty else { return nil }
            var pattern = "^", offset = 0, identifiers: [String] = [], fixedLength = 0
            for match in matches {
                let literal = ns.substring(with: NSRange(location: offset, length: match.range.location - offset))
                pattern += NSRegularExpression.escapedPattern(for: literal) + "(.*?)"
                fixedLength += literal.count; identifiers.append(ns.substring(with: match.range(at: 1)))
                offset = NSMaxRange(match.range)
            }
            let end = ns.substring(from: offset)
            pattern += NSRegularExpression.escapedPattern(for: end) + "$"; fixedLength += end.count
            guard let regex = try? NSRegularExpression(pattern: pattern, options: [.dotMatchesLineSeparators]) else { return nil }
            self.regex = regex; self.identifiers = identifiers; specificity = fixedLength
            multiline = source.contains("\n"); numericDuration = source == "{0} с"
            let targetText = target as NSString
            var pieces: [Piece] = [], targetOffset = 0
            for match in marker.matches(in: target, range: NSRange(location: 0, length: targetText.length)) {
                pieces.append(.literal(targetText.substring(with: NSRange(location: targetOffset, length: match.range.location - targetOffset))))
                pieces.append(.capture(targetText.substring(with: match.range(at: 1))))
                targetOffset = NSMaxRange(match.range)
            }
            pieces.append(.literal(targetText.substring(from: targetOffset))); self.pieces = pieces
            // These captures are device/app names, Bluetooth addresses or OS output labels.
            opaque = source.contains(" [{") || source == "Новое воспроизведение: {0}" ||
                source.hasPrefix("Выход") || source.hasPrefix("Текущий системный выход:") ||
                source.hasPrefix("После передачи наушники отключились") || source.hasPrefix("На устройствах выбраны разные наушники. Mac:")
        }
    }
}

func L(_ source: String) -> String { UILocalization.shared.text(source) }
func LT(_ source: String) -> Text { Text(verbatim: L(source)) }
func LLabel(_ title: String, systemImage: String) -> Label<Text, Image> { Label { LT(title) } icon: { Image(systemName: systemImage) } }
func LButton(_ title: String, role: ButtonRole? = nil, action: @escaping () -> Void) -> Button<Text> { Button(role: role, action: action) { LT(title) } }
func LToggle(_ title: String, isOn: Binding<Bool>) -> Toggle<Text> { Toggle(isOn: isOn) { LT(title) } }
func LPicker<Selection: Hashable, Content: View>(_ title: String, selection: Binding<Selection>, @ViewBuilder content: () -> Content) -> Picker<Text, Selection, Content> { Picker(selection: selection, content: content) { LT(title) } }
func LTextField(_ title: String, text: Binding<String>) -> TextField<Text> { TextField(L(title), text: text) }
