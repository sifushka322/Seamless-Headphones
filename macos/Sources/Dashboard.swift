import SwiftUI
import AppKit

enum Page: String, CaseIterable {
    case overview = "Обзор", automation = "Автоматизация", devices = "Устройства", settings = "Настройки"
    var symbol: String { switch self { case .overview: return "square.grid.2x2"; case .automation: return "sparkles"; case .devices: return "hifispeaker.and.homepod"; case .settings: return "slider.horizontal.3" } }
    var subtitle: String { switch self { case .overview: return "Музыка продолжается. Устройства меняются."; case .automation: return "Твои правила. Без неожиданных переключений."; case .devices: return "Одна пара наушников. Два устройства."; case .settings: return "Сделай Seamless своим." } }
}

struct Dashboard: View {
    @ObservedObject var model: AppModel
    @Environment(\.colorScheme) var scheme
    @Environment(\.accessibilityReduceMotion) var reduceMotion
    @State private var page: Page
    init(model: AppModel, initialPage: Page = .overview) {
        self.model = model; _page = State(initialValue: initialPage)
    }
    @State private var search = ""
    @State private var revoke = false
    private var dark: Bool { scheme == .dark }
    private var accent: Color {
        switch model.accent { case "cobalt": return Color(red: 0.35, green: 0.52, blue: 0.98); case "iris": return Color(red: 0.65, green: 0.43, blue: 0.91); default: return dark ? Color(red: 0.43, green: 0.88, blue: 0.72) : Color(red: 0.08, green: 0.48, blue: 0.36) }
    }
    private var canvas: Color { dark ? Color(red: 0.065, green: 0.077, blue: 0.09) : Color(red: 0.965, green: 0.975, blue: 0.969) }
    private var surface: Color { dark ? Color(red: 0.105, green: 0.12, blue: 0.135) : .white }
    private var line: Color { Color.primary.opacity(dark ? 0.09 : 0.06) }
    var body: some View {
        HStack(spacing: 0) {
            sidebar
            Rectangle().fill(line).frame(width: 1)
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    header
                    switch page { case .overview: overview; case .automation: automation; case .devices: devices; case .settings: settings }
                    if !model.errorText.isEmpty { Label(model.errorText, systemImage: "exclamationmark.triangle").foregroundStyle(.orange).font(.callout) }
                    HStack { Text("SEAMLESS HEADPHONES").tracking(1.7); Spacer(); Text(model.demo ? "ДЕМО · БЕЗ КОМАНД УСТРОЙСТВАМ" : "0.4.0 · ЛОКАЛЬНО ПО BLUETOOTH") }
                        .font(.system(size: 9, weight: .medium, design: .monospaced)).foregroundStyle(.tertiary).padding(.top, 6)
                }.padding(32).frame(maxWidth: 1100)
            }
        }.background(canvas).tint(accent).frame(minWidth: 960, minHeight: 720)
            .preferredColorScheme(model.theme == "system" ? nil : model.theme == "dark" ? .dark : .light)
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: page)
            .alert("Отозвать ключ связи?", isPresented: $revoke) {
                Button("Отмена", role: .cancel) {}
                Button("Отозвать", role: .destructive) { model.revoke() }
            } message: { Text("Телефон потеряет доступ. Для подключения потребуется новый ключ с этого Mac.") }
    }
    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 28) {
            HStack(spacing: 10) {
                Image(systemName: "airpods.pro").font(.system(size: 21, weight: .semibold)).foregroundStyle(accent).frame(width: 42, height: 42).background(accent.opacity(0.12), in: RoundedRectangle(cornerRadius: 13))
                VStack(alignment: .leading, spacing: 1) { Text("Seamless").font(.system(size: 20, weight: .bold, design: .rounded)); Text("HEADPHONES").font(.system(size: 9, weight: .semibold)).tracking(2.3).foregroundStyle(.secondary) }
            }.padding(.top, 16)
            VStack(spacing: 6) {
                ForEach(Page.allCases, id: \.self) { destination in
                    Button { page = destination } label: {
                        HStack(spacing: 12) { Image(systemName: destination.symbol).font(.system(size: 15)).frame(width: 20); Text(destination.rawValue).font(.system(size: 13, weight: page == destination ? .semibold : .regular)).lineLimit(1); Spacer() }
                            .padding(.horizontal, 13).padding(.vertical, 12).foregroundStyle(page == destination ? accent : .secondary)
                            .background(page == destination ? accent.opacity(0.11) : .clear, in: RoundedRectangle(cornerRadius: 11))
                    }.buttonStyle(.plain)
                }
            }
            Spacer()
            VStack(alignment: .leading, spacing: 10) {
                Image(systemName: "antenna.radiowaves.left.and.right").foregroundStyle(accent)
                Text("Всегда рядом").font(.system(size: 13, weight: .semibold))
                Text("Без облака, аккаунтов\nи общей сети Wi-Fi.").font(.system(size: 11)).foregroundStyle(.secondary).lineSpacing(3)
            }.padding(16).frame(maxWidth: .infinity, alignment: .leading).background(accent.opacity(0.06), in: RoundedRectangle(cornerRadius: 15))
            HStack(spacing: 8) { Circle().fill(model.trusted ? accent : .orange).frame(width: 6, height: 6); Text(model.demo ? "Деморежим" : model.trusted ? "Устройства связаны" : "Телефон не подключён").font(.system(size: 11)); Spacer() }
                .foregroundStyle(.secondary)
        }.padding(20).frame(width: 224).background(surface.opacity(0.45))
    }
    private var header: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 7) { Text(page.rawValue).font(.system(size: 29, weight: .bold, design: .rounded)); Text(page.subtitle).font(.system(size: 13)).foregroundStyle(.secondary) }
            Spacer()
            pill(model.held ? "Удержание" : model.autoEnabled ? "Автопереключение" : "Ручной режим", icon: model.held ? "lock.fill" : "sparkles", color: model.held ? .orange : accent)
        }
    }
    private var overview: some View {
        VStack(spacing: 20) {
            if !model.trusted || model.selected.isEmpty {
                HStack(spacing: 14) {
                    Image(systemName: "link.badge.plus").font(.title2).foregroundStyle(accent)
                    VStack(alignment: .leading, spacing: 4) { Text("Начнём с твоих устройств").font(.headline); Text("Выбери наушники, затем свяжи телефон с Mac.").font(.caption).foregroundStyle(.secondary) }
                    Spacer(); Button("Настроить") { page = .devices }.buttonStyle(.borderedProminent)
                }.padding(18).background(accent.opacity(0.08), in: RoundedRectangle(cornerRadius: 16))
            }
            HStack(spacing: 24) {
                VStack(alignment: .leading, spacing: 14) {
                    pill(model.owner == "mac" ? "СЕЙЧАС НА MAC" : model.owner == "android" ? "СЕЙЧАС НА ANDROID" : "ГОТОВИМСЯ К ПОДКЛЮЧЕНИЮ", icon: "waveform", color: accent)
                    Text(model.headsets.first { $0.id == model.selected }?.name ?? "Твои наушники")
                        .font(.system(size: 28, weight: .semibold, design: .rounded)).lineLimit(2)
                    Text(model.busy ? model.headline : "Слушай там, где удобно.")
                        .font(.system(size: 14)).foregroundStyle(.secondary)
                    HStack(spacing: 7) { Circle().fill(model.trusted ? accent : .orange).frame(width: 6, height: 6); Text(model.link).font(.system(size: 11)).foregroundStyle(.secondary) }
                }.frame(maxWidth: .infinity, alignment: .leading)
                ZStack {
                    Circle().stroke(accent.opacity(0.12), lineWidth: 1).frame(width: 156, height: 156)
                    Circle().fill(accent.opacity(0.08)).frame(width: 130, height: 130)
                    Image(systemName: "airpods.pro").font(.system(size: 70, weight: .ultraLight)).foregroundStyle(accent)
                    Image(systemName: model.busy ? "arrow.triangle.2.circlepath" : "checkmark").font(.system(size: 13, weight: .semibold)).padding(10).background(surface, in: Circle()).offset(x: 54, y: 52).foregroundStyle(accent)
                }.accessibilityHidden(true)
            }.padding(28).frame(maxWidth: .infinity).background(LinearGradient(colors: [accent.opacity(0.09), surface], startPoint: .topLeading, endPoint: .bottomTrailing), in: RoundedRectangle(cornerRadius: 24)).overlay(RoundedRectangle(cornerRadius: 24).stroke(line))
            HStack(spacing: 14) {
                deviceCard("Mac", icon: "laptopcomputer", subtitle: model.local.playing ? "Воспроизведение активно" : "Готов к воспроизведению", target: "mac")
                deviceCard("Android", icon: "iphone", subtitle: model.peer.playing ? "Воспроизведение активно" : model.trusted ? "Рядом и подключён" : "Ожидаем подключения", target: "android")
            }
            if model.busy { transferProgress }
            HStack(alignment: .top, spacing: 14) {
                Image(systemName: model.autoPaused ? "pause.circle" : "sparkles").font(.title2).foregroundStyle(model.autoPaused ? .orange : accent)
                VStack(alignment: .leading, spacing: 5) {
                    Text(model.autoEnabled ? "Автоматизация" : "Управляешь ты").font(.headline)
                    Text(model.autoReason).font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                }
                Spacer()
                if model.autoPaused { Button("Возобновить", action: model.resumeAuto).disabled(model.busy) }
                else { Button { page = .automation } label: { Image(systemName: "arrow.up.right") }.buttonStyle(.borderless).accessibilityLabel("Настройки автоматизации") }
            }.padding(20).card(surface, line)
            HStack(spacing: 14) {
                metric("Передач", value: "\(model.successful)", icon: "arrow.left.arrow.right")
                metric("Последняя", value: model.lastDuration, icon: "timer")
                metric("Соединение", value: "Локальное", icon: "network.slash")
            }
            if !model.detail.isEmpty { Text(model.detail).font(.caption).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading).textSelection(.enabled) }
        }
    }
    private func deviceCard(_ title: String, icon: String, subtitle: String, target: String) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack { Image(systemName: icon).font(.system(size: 26, weight: .light)).foregroundStyle(accent); Spacer(); if model.owner == target { pill("Звук здесь", icon: "speaker.wave.2", color: accent) } }
            Text(title).font(.system(size: 17, weight: .semibold)); Text(subtitle).font(.system(size: 11)).foregroundStyle(.secondary)
            Button { model.request(target) } label: { HStack { Text(target == "mac" ? "Забрать на Mac" : "Передать на телефон"); Spacer(); Image(systemName: "arrow.right") }.padding(.vertical, 6).frame(maxWidth: .infinity) }
                .buttonStyle(.bordered).disabled(!model.trusted || model.busy || model.held || model.selected.isEmpty)
        }.padding(20).frame(maxWidth: .infinity, alignment: .leading).card(surface, model.owner == target ? accent.opacity(0.4) : line)
    }
    private var transferProgress: some View {
        HStack(spacing: 16) {
            ForEach(Array([("preparing", "Готовность"), ("releasing", "Освобождение"), ("acquiring", "Подключение")].enumerated()), id: \.offset) { i, step in
                HStack(spacing: 7) { Text("\(i + 1)").font(.caption.bold()).frame(width: 23, height: 23).background(model.stage == step.0 ? accent.opacity(0.2) : line, in: Circle()); Text(step.1).font(.caption) }.foregroundStyle(model.stage == step.0 ? accent : .secondary)
            }
            Spacer(); Button("Отменить") { model.cancel("Ожидание остановлено. Системное подключение могло уже начаться.") }.controlSize(.small)
        }.padding(16).card(surface, line)
    }
    private var automation: some View {
        VStack(alignment: .leading, spacing: 20) {
            VStack(alignment: .leading, spacing: 18) {
                Toggle(isOn: $model.autoEnabled) { rowLabel("Автопереключение", "Новое воспроизведение передаёт наушники нужному устройству.", icon: "sparkles") }.toggleStyle(.switch)
                Divider()
                Text(model.autoReason).font(.callout).foregroundStyle(model.autoPaused ? .orange : .secondary)
                Button("Вернуть автоматику сейчас", action: model.resumeAuto).disabled(model.busy || !model.trusted)
                Text("После сброса поставь музыку на паузу и запусти заново. Уже играющий звук не вызывает передачу.").font(.caption).foregroundStyle(.secondary)
                Text("Сейчас на Mac: " + (model.observedSources.isEmpty ? "звук не обнаружен" : model.observedSources)).font(.caption).textSelection(.enabled)
            }.padding(22).card(surface, line)
            VStack(alignment: .leading, spacing: 18) {
                Text("Как переключать").font(.headline)
                Picker("Подключение", selection: $model.handoffMode) {
                    ForEach(HandoffMode.allCases, id: \.self) { Text($0.title).tag($0) }
                }.disabled(model.busy)
                Text("Получатель первым: пробуем подключиться без разрыва источника; при явном отказе ОС отключаем источник и повторяем. При зависшей попытке повтор не запускается. Для сравнения доступны обычный и параллельный режимы.").font(.caption).foregroundStyle(.secondary)
                Picker("Режим", selection: $model.idleOnly) { Text("Следовать новому звуку").tag(false); Text("Только в паузе").tag(true) }.pickerStyle(.segmented)
                Text(model.idleOnly ? "Если источник ещё играет, наушники останутся у него. После паузы нужно новое начало воспроизведения." : "Начни музыку в выбранном приложении на другом устройстве — Seamless передаст наушники после короткой проверки.").font(.callout).foregroundStyle(.secondary)
                Divider()
                Toggle(isOn: $model.held) { rowLabel("Удерживать на текущем устройстве", "Блокирует и автоматическую, и ручную передачу.", icon: "lock.shield") }.toggleStyle(.switch)
                Text("Длительность приоритета ручной команды настраивается ниже. Во время разговора или активного микрофона автоматизация приостановлена.").font(.caption).foregroundStyle(.secondary)
            }.padding(22).card(surface, line)
            VStack(alignment: .leading, spacing: 16) {
                Text("Приложения на Mac").font(.headline)
                Text("Только эти приложения могут инициировать передачу на Mac. Браузеры выключены по умолчанию: видео и реклама тоже могут воспроизводить звук.").font(.callout).foregroundStyle(.secondary)
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], alignment: .leading, spacing: 15) {
                    ForEach((MediaMonitor.sources + model.discoveredSources.filter { key, _ in !MediaMonitor.sources.contains { $0.0 == key } }.sorted { $0.key < $1.key }.map { ($0.key, $0.value) }), id: \.0) { id, name in
                        Toggle(name, isOn: Binding(get: { model.allowed.contains(id) }, set: { if $0 { model.allowed.insert(id) } else { model.allowed.remove(id) } })).toggleStyle(.checkbox)
                    }
                }
                Divider()
                Toggle("Быстрое обнаружение · 0,5 с", isOn: $model.fastDetection)
                Text("Быстрый режим реагирует и на короткое видео в разрешённом браузере. Отключи его, чтобы использовать задержку ниже.").font(.caption).foregroundStyle(.secondary)
                HStack { Text("Проверка нового звука"); Spacer(); Text("\(Int(model.delay)) с").monospacedDigit().foregroundStyle(accent) }.font(.callout)
                Slider(value: $model.delay, in: 2...5, step: 1).disabled(model.fastDetection).accessibilityLabel("Задержка автопереключения")
                HStack { Text("Пауза между передачами"); Spacer(); Text("\(Int(model.cooldown)) с").monospacedDigit().foregroundStyle(accent) }.font(.callout)
                Slider(value: $model.cooldown, in: 5...60, step: 5).accessibilityLabel("Пауза между автоматическими передачами")
                HStack { Text("Приоритет ручной команды"); Spacer(); Text("\(Int(model.manualPriority)) с").monospacedDigit().foregroundStyle(accent) }.font(.callout)
                Slider(value: $model.manualPriority, in: 0...120, step: 5).accessibilityLabel("Приоритет ручной команды")
            }.padding(22).card(surface, line)
        }
    }
    private var devices: some View {
        VStack(alignment: .leading, spacing: 20) {
            VStack(alignment: .leading, spacing: 18) {
                rowLabel("1. Выбери наушники", "Сначала сопряги их с обеими ОС через настройки Bluetooth.", icon: "headphones")
                Picker("Наушники", selection: $model.selected) { Text("Выбрать устройство").tag(""); ForEach(model.headsets) { Text("\($0.name) · \($0.id)").tag($0.id) } }.disabled(model.busy)
                HStack { Button("Обновить список", action: model.refresh); Button("Настройки Bluetooth", action: model.bluetoothSettings) }
                Text(model.selectionSummary).font(.system(size: 12, design: .monospaced)).foregroundStyle(model.selectionMismatch ? .orange : .secondary).textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
                if model.selectionMismatch { Label("Выбери на обоих устройствах наушники с одинаковым адресом", systemImage: "exclamationmark.triangle").font(.caption).foregroundStyle(.orange) }
                Text("Текущий системный выход: \(model.route)").font(.caption).foregroundStyle(.secondary)
            }.padding(22).card(surface, line)
            VStack(alignment: .leading, spacing: 18) {
                rowLabel("2. Свяжи Android с Mac", "Открой Seamless Headphones на телефоне и добавь ключ этого Mac.", icon: "link")
                HStack { pill(model.link, icon: model.trusted ? "checkmark.shield" : "antenna.radiowaves.left.and.right", color: model.trusted ? accent : .orange); Spacer() }
                HStack {
                    if model.enabled { Button(model.showPairing ? "Скрыть QR и ключ" : "Показать QR и ключ") { model.showPairing.toggle() }; Button("Остановить связь", action: model.disable) }
                    else { Button("Связать телефон", action: model.enable).buttonStyle(.borderedProminent) }
                    Spacer(); if model.enabled { Button("Отозвать доступ", role: .destructive) { revoke = true } }
                }
                if model.showPairing && !model.pairingCode.isEmpty {
                    HStack(alignment: .center, spacing: 22) {
                        PairingQR(key: model.pairingCode, device: model.selected, name: model.selectedName)
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Наведи камеру телефона").font(.headline)
                            Text("На Android открой «Устройства» → «Сканировать QR с Mac». Подтверди сохранение ключа и нажми «Найти Mac».").font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                            Text("QR содержит ключ доступа. Показывай его только своему телефону.").font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                        }.frame(maxWidth: .infinity, alignment: .leading)
                    }
                    Text(model.pairingCode).font(.system(size: 13, design: .monospaced)).textSelection(.enabled).padding(14).frame(maxWidth: .infinity, alignment: .leading).background(canvas, in: RoundedRectangle(cornerRadius: 12))
                    HStack { Text("Ключ даёт управление твоими наушниками.").font(.caption).foregroundStyle(.secondary); Spacer(); Button("Скопировать") { NSPasteboard.general.clearContents(); NSPasteboard.general.setString(model.pairingCode, forType: .string) } }
                }
            }.padding(22).card(surface, line)
            VStack(alignment: .leading, spacing: 14) {
                rowLabel("3. Разреши автоматизацию на телефоне", "Доступ к медиасессиям распознаёт Play/Pause. Состояние вызовов защищает разговоры. Настрой эти разрешения в приложении Android.", icon: "checklist")
                HStack { pill(model.peer.available ? "Сигналы телефона доступны" : "Ожидаем настройку Android", icon: model.peer.available ? "checkmark" : "clock", color: model.peer.available ? accent : .orange) }
                Text("Подключение A2DP зависит от прошивки Android. При системном запрете Seamless покажет ошибку и приостановит автоматику.").font(.caption).foregroundStyle(.secondary)
            }.padding(22).card(surface, line)
        }
    }
    private var settings: some View {
        VStack(alignment: .leading, spacing: 20) {
            VStack(alignment: .leading, spacing: 18) {
                Text("Оформление").font(.headline)
                HStack(spacing: 12) {
                    ForEach([("system", "Системная", "circle.lefthalf.filled"), ("light", "Светлая", "sun.max"), ("dark", "Тёмная", "moon")], id: \.0) { value, title, icon in
                        Button { model.theme = value } label: {
                            VStack(spacing: 12) { Image(systemName: icon).font(.system(size: 26, weight: .light)); Text(title).font(.callout) }.frame(maxWidth: .infinity).padding(.vertical, 22)
                                .background(model.theme == value ? accent.opacity(0.12) : canvas, in: RoundedRectangle(cornerRadius: 14)).overlay(RoundedRectangle(cornerRadius: 14).stroke(model.theme == value ? accent : line))
                        }.buttonStyle(.plain).foregroundStyle(model.theme == value ? accent : .primary)
                    }
                }
                HStack(spacing: 15) {
                    Text("Цвет акцента").font(.callout); Spacer()
                    ForEach([("mint", "Мята", Color(red: 0.17, green: 0.63, blue: 0.47)), ("cobalt", "Кобальт", .blue), ("iris", "Ирис", .purple)], id: \.0) { value, title, color in
                        Button { model.accent = value } label: { Circle().fill(color).frame(width: 28, height: 28).overlay(Image(systemName: model.accent == value ? "checkmark" : "").font(.caption.bold()).foregroundStyle(.white)) }.buttonStyle(.plain).accessibilityLabel(title).help(title)
                    }
                }
            }.padding(22).card(surface, line)
            Toggle(isOn: $model.reconnect) { rowLabel("Восстанавливать связь", "Возвращаться к связи после сна Mac и временных обрывов.", icon: "arrow.triangle.2.circlepath") }.toggleStyle(.switch).padding(22).card(surface, line)
            VStack(alignment: .leading, spacing: 14) {
                HStack { Text("История Mac").font(.headline); Spacer(); Button("Скопировать", action: model.copyDiagnostics); Button("Очистить", action: model.clearLogs) }
                Text("События этого Mac. Ответы телефона отмечены [Android].").font(.caption).foregroundStyle(.secondary)
                TextField("Поиск в событиях", text: $search).textFieldStyle(.roundedBorder)
                if model.events.isEmpty { Label("События появятся после подключения", systemImage: "clock").font(.caption).foregroundStyle(.secondary) }
                ForEach(Array(model.events.filter { search.isEmpty || $0.localizedCaseInsensitiveContains(search) }.prefix(15).enumerated()), id: \.offset) { _, event in Text(event).font(.system(size: 11, design: .monospaced)).foregroundStyle(.secondary).textSelection(.enabled); Divider() }
                Divider()
                Toggle("Технические логи", isOn: $model.debugEnabled).toggleStyle(.switch)
                Text("Включи на обоих устройствах, повтори одну передачу и скопируй диагностику с каждого. Журнал содержит адреса, этапы, задержки и очередь BLE; последние 1000 записей остаются в памяти приложения.").font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                if model.debugEnabled {
                    ScrollView {
                        Text(model.debugEvents.prefix(80).joined(separator: "\n")).font(.system(size: 11, design: .monospaced)).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
                    }.frame(height: 220).padding(12).background(canvas, in: RoundedRectangle(cornerRadius: 12))
                }
                Text("Ключи не попадают в журнал. Приложение не записывает звук и не отправляет телеметрию.").font(.caption).foregroundStyle(.secondary)
            }.padding(22).card(surface, line)
        }
    }
    private func rowLabel(_ title: String, _ subtitle: String, icon: String) -> some View {
        HStack(alignment: .top, spacing: 13) { Image(systemName: icon).font(.system(size: 21, weight: .light)).foregroundStyle(accent).frame(width: 28); VStack(alignment: .leading, spacing: 6) { Text(title).font(.system(size: 15, weight: .semibold)); Text(subtitle).font(.system(size: 12)).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true) } }
    }
    private func metric(_ title: String, value: String, icon: String) -> some View {
        VStack(alignment: .leading, spacing: 9) { HStack { Text(title).font(.system(size: 11)); Spacer(); Image(systemName: icon) }.foregroundStyle(.secondary); Text(value).font(.system(size: 20, weight: .semibold, design: .rounded)) }.padding(17).frame(maxWidth: .infinity, alignment: .leading).card(surface, line)
    }
    private func pill(_ title: String, icon: String, color: Color) -> some View {
        Label(title, systemImage: icon).font(.system(size: 10, weight: .semibold)).foregroundStyle(color).padding(.horizontal, 10).padding(.vertical, 6).background(color.opacity(0.10), in: Capsule())
    }
}

private extension View {
    func card(_ fill: Color, _ border: Color) -> some View { frame(maxWidth: .infinity, alignment: .leading).background(fill, in: RoundedRectangle(cornerRadius: 18)).overlay(RoundedRectangle(cornerRadius: 18).stroke(border)) }
}

private struct PairingQR: View {
    let key: String
    let device: String
    let name: String
    @State private var image: NSImage?
    var body: some View {
        Group {
            if let image { Image(nsImage: image).interpolation(.none).resizable().scaledToFit() }
            else { Text("QR недоступен").foregroundStyle(.black) }
        }.frame(width: 184, height: 184).padding(20).background(.white, in: RoundedRectangle(cornerRadius: 12))
            .accessibilityLabel("QR-код связи с Mac")
            .task(id: key + device + name) { image = PairingCode.image(key: key, device: device, name: name) }
    }
}
