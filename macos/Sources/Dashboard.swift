import SwiftUI
import AppKit

enum Page: String, CaseIterable {
    case overview = "Обзор", automation = "Автоматизация", devices = "Устройства", settings = "Настройки"
    var symbol: String { switch self { case .overview: return "square.grid.2x2"; case .automation: return "sparkles"; case .devices: return "hifispeaker.and.homepod"; case .settings: return "slider.horizontal.3" } }
    var subtitle: String { switch self { case .overview: return "Музыка продолжается. Устройства меняются."; case .automation: return "Твои правила. Без неожиданных переключений."; case .devices: return "Одна пара наушников. Два устройства."; case .settings: return "Сделай Seamless своим." } }
}

struct Dashboard: View {
    @ObservedObject var model: AppModel
    @ObservedObject private var localization = UILocalization.shared
    @Environment(\.colorScheme) var scheme
    @Environment(\.accessibilityReduceMotion) var reduceMotion
    @State private var page: Page
    init(model: AppModel, initialPage: Page = .overview) {
        self.model = model; _page = State(initialValue: initialPage)
    }
    @State private var search = ""
    @State private var revoke = false
    @State private var notice = ""
    private var effectiveScheme: ColorScheme { model.theme == "dark" ? .dark : model.theme == "light" ? .light : scheme }
    private var dark: Bool { effectiveScheme == .dark }
    private var accent: Color {
        switch model.accent { case "cobalt": return dark ? Color(red: 0.49, green: 0.67, blue: 1) : Color(red: 0.16, green: 0.36, blue: 0.75); case "iris": return dark ? Color(red: 0.72, green: 0.56, blue: 0.96) : Color(red: 0.48, green: 0.25, blue: 0.72); default: return dark ? Color(red: 0.43, green: 0.88, blue: 0.72) : Color(red: 0.08, green: 0.48, blue: 0.36) }
    }
    private var canvas: Color { dark ? Color(red: 0.065, green: 0.077, blue: 0.09) : Color(red: 0.965, green: 0.975, blue: 0.969) }
    private var surface: Color { dark ? Color(red: 0.105, green: 0.12, blue: 0.135) : .white }
    private var secondary: Color { dark ? Color(red: 0.66, green: 0.70, blue: 0.69) : Color(red: 0.36, green: 0.40, blue: 0.39) }
    private var warning: Color { dark ? Color(red: 1, green: 0.66, blue: 0.28) : Color(red: 0.66, green: 0.31, blue: 0.04) }
    private var line: Color { Color.primary.opacity(dark ? 0.09 : 0.06) }
    private var selectableSources: [(String, String)] {
        (MediaMonitor.sources + model.discoveredSources.filter { key, _ in !MediaMonitor.sources.contains { $0.0 == key } }
            .sorted { $0.key < $1.key }.map { ($0.key, $0.value) }).filter { MediaSourcePolicy.canTrigger($0.0) }
    }
    private var localizedSelectionSummary: String {
        let name = model.headsets.first { AudioDevices.normalized($0.id) == AudioDevices.normalized(model.selected) }?.name ?? L("Не выбраны")
        let address = model.selected.isEmpty ? L("не выбраны") : model.selected
        let phoneName = model.peerHeadsetName.isEmpty ? L("ожидаем сведения") : model.peerHeadsetName
        let phoneAddress = model.peerHeadset.map { $0.isEmpty ? L("не выбраны") : $0 } ?? L("неизвестно")
        return "Mac: \(name) [\(address)]\nAndroid: \(phoneName) [\(phoneAddress)]"
    }
    var body: some View {
        HStack(spacing: 0) {
            sidebar
            Rectangle().fill(line).frame(width: 1)
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    header
                    if !model.errorText.isEmpty {
                        LLabel(model.errorText, systemImage: "exclamationmark.triangle.fill")
                            .font(.callout).foregroundStyle(warning).padding(16).frame(maxWidth: .infinity, alignment: .leading)
                            .background(warning.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
                    }
                    switch page { case .overview: overview; case .automation: automation; case .devices: devices; case .settings: settings }
                    HStack { LT("SEAMLESS HEADPHONES").tracking(1.7); Spacer(); LT(model.demo ? "ДЕМО · БЕЗ КОМАНД УСТРОЙСТВАМ" : "0.5.0 · STABLE · ЛОКАЛЬНО ПО BLUETOOTH") }
                        .font(.system(size: 9, weight: .medium, design: .monospaced)).foregroundStyle(.tertiary).padding(.top, 6)
                }.padding(32).frame(maxWidth: 1100)
            }
        }.background(canvas).tint(accent).frame(minWidth: 960, minHeight: 720)
            .environment(\.colorScheme, effectiveScheme)
            .environment(\.locale, localization.locale)
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: page)
            .overlay(alignment: .bottom) {
                if !notice.isEmpty {
                    LT(notice).font(.callout).padding(.horizontal, 20).padding(.vertical, 13)
                        .background(surface, in: Capsule()).overlay(Capsule().stroke(accent.opacity(0.4)))
                        .shadow(color: .black.opacity(0.12), radius: 14, y: 5).padding(24).allowsHitTesting(false)
                        .accessibilityLabel(L(notice))
                }
            }
            .alert(L("Отозвать ключ связи?"), isPresented: $revoke) {
                LButton("Отмена", role: .cancel) {}
                LButton("Отозвать", role: .destructive) { model.revoke() }
            } message: { LT("Телефон потеряет доступ. Для подключения потребуется новый ключ с этого Mac.") }
    }
    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 28) {
            HStack(spacing: 10) {
                Image(systemName: "airpods.pro").font(.system(size: 21, weight: .semibold)).foregroundStyle(accent).frame(width: 42, height: 42).background(accent.opacity(0.12), in: RoundedRectangle(cornerRadius: 13))
                VStack(alignment: .leading, spacing: 1) { LT("Seamless").font(.system(size: 20, weight: .bold, design: .rounded)); LT("HEADPHONES").font(.system(size: 9, weight: .semibold)).tracking(2.3).foregroundStyle(secondary) }
            }.padding(.top, 16)
            VStack(spacing: 6) {
                ForEach(Page.allCases, id: \.self) { destination in
                    Button { page = destination } label: {
                        HStack(spacing: 12) { Image(systemName: destination.symbol).font(.system(size: 15)).frame(width: 20); LT(destination.rawValue).font(.system(size: 13, weight: page == destination ? .semibold : .regular)).lineLimit(1); Spacer() }
                            .padding(.horizontal, 13).padding(.vertical, 12).foregroundStyle(page == destination ? accent : secondary)
                            .background(page == destination ? accent.opacity(0.11) : .clear, in: RoundedRectangle(cornerRadius: 11))
                    }.buttonStyle(.plain)
                }
            }
            Spacer()
            VStack(alignment: .leading, spacing: 10) {
                Image(systemName: "antenna.radiowaves.left.and.right").foregroundStyle(accent)
                LT("Всегда рядом").font(.system(size: 13, weight: .semibold))
                LT("Без облака, аккаунтов\nи общей сети Wi-Fi.").font(.system(size: 11)).foregroundStyle(secondary).lineSpacing(3)
            }.padding(16).frame(maxWidth: .infinity, alignment: .leading).background(accent.opacity(0.06), in: RoundedRectangle(cornerRadius: 15))
            HStack(spacing: 8) { Circle().fill(model.trusted ? accent : warning).frame(width: 6, height: 6); LT(model.demo ? "Деморежим" : model.trusted ? "Устройства связаны" : "Телефон не подключён").font(.system(size: 11)); Spacer() }
                .foregroundStyle(secondary)
        }.padding(20).frame(width: 224).background(surface.opacity(0.45))
    }
    private var header: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 7) { LT(page.rawValue).font(.system(size: 29, weight: .bold, design: .rounded)); LT(page.subtitle).font(.system(size: 13)).foregroundStyle(secondary) }
            Spacer()
            pill(model.automationSummary, icon: model.held || model.peer.held ? "lock.fill" : model.autoPaused ? "pause.circle" : model.autoEnabled ? "sparkles" : "hand.tap", color: model.held || model.peer.held || model.autoPaused ? warning : accent)
        }
    }
    private var overview: some View {
        VStack(spacing: 20) {
            if !model.trusted || model.selected.isEmpty {
                HStack(spacing: 14) {
                    Image(systemName: "link.badge.plus").font(.title2).foregroundStyle(accent)
                    VStack(alignment: .leading, spacing: 4) { LT("Начнём с твоих устройств").font(.headline); LT("Выбери наушники, затем свяжи телефон с Mac.").font(.caption).foregroundStyle(secondary) }
                    Spacer(); LButton("Настроить") { page = .devices }.buttonStyle(.borderedProminent)
                }.padding(18).background(accent.opacity(0.08), in: RoundedRectangle(cornerRadius: 16))
            }
            HStack(spacing: 24) {
                VStack(alignment: .leading, spacing: 14) {
                    pill(model.busy ? "ПЕРЕДАЧА ВЫПОЛНЯЕТСЯ" : model.owner == "mac" ? "МАРШРУТ MAC ПОДТВЕРЖДЁН" : model.owner == "android" ? "МАРШРУТ ANDROID ПОДТВЕРЖДЁН" : "МАРШРУТ НЕ ПОДТВЕРЖДЁН", icon: "waveform", color: accent)
                    Text(verbatim: model.headsets.first { $0.id == model.selected }?.name ?? L("Твои наушники"))
                        .font(.system(size: 28, weight: .semibold, design: .rounded)).lineLimit(2)
                    LT(model.busy ? model.headline : "Выход Mac: \(model.route)")
                        .font(.system(size: 14)).foregroundStyle(secondary)
                    HStack(spacing: 7) { Circle().fill(model.trusted ? accent : warning).frame(width: 6, height: 6); LT(model.link).font(.system(size: 11)).foregroundStyle(secondary) }
                }.frame(maxWidth: .infinity, alignment: .leading)
                ZStack {
                    Circle().stroke(accent.opacity(0.12), lineWidth: 1).frame(width: 156, height: 156)
                    Circle().fill(accent.opacity(0.08)).frame(width: 130, height: 130)
                    Image(systemName: "airpods.pro").font(.system(size: 70, weight: .ultraLight)).foregroundStyle(accent)
                    Image(systemName: model.busy ? "arrow.triangle.2.circlepath" : model.owner == "unknown" ? "questionmark" : "checkmark").font(.system(size: 13, weight: .semibold)).padding(10).background(surface, in: Circle()).offset(x: 54, y: 52).foregroundStyle(accent)
                }.accessibilityHidden(true)
            }.padding(28).frame(maxWidth: .infinity).background(LinearGradient(colors: [accent.opacity(0.09), surface], startPoint: .topLeading, endPoint: .bottomTrailing), in: RoundedRectangle(cornerRadius: 24)).overlay(RoundedRectangle(cornerRadius: 24).stroke(line))
            HStack(spacing: 14) {
                deviceCard("Mac", icon: "laptopcomputer", subtitle: model.local.playing ? "Воспроизведение активно" : "Звук не воспроизводится", target: "mac")
                deviceCard("Android", icon: "iphone", subtitle: model.peer.playing ? "Воспроизведение активно" : model.trusted ? "Управление по Bluetooth доступно" : "Ожидаем связь с телефоном", target: "android")
            }
            if let reason = model.manualBlockReason, !model.busy {
                LLabel(reason, systemImage: "info.circle").font(.callout).foregroundStyle(secondary).frame(maxWidth: .infinity, alignment: .leading)
            }
            if model.busy { transferProgress }
            HStack(alignment: .top, spacing: 14) {
                Image(systemName: model.autoPaused ? "pause.circle" : "sparkles").font(.title2).foregroundStyle(model.autoPaused ? warning : accent)
                VStack(alignment: .leading, spacing: 5) {
                    LT(model.automationSummary).font(.headline)
                    LT(model.autoReason).font(.callout).foregroundStyle(secondary).fixedSize(horizontal: false, vertical: true)
                }
                Spacer()
                if model.hasAutoWait && model.canResumeAuto { LButton(model.resumeAutoLabel, action: resumeAutomation) }
                else { Button { page = .automation } label: { Image(systemName: "arrow.up.right") }.buttonStyle(.borderless).accessibilityLabel(L("Настройки автоматизации")) }
            }.padding(20).card(surface, line)
            HStack(spacing: 14) {
                metric("Передач", value: "\(model.successful)", icon: "arrow.left.arrow.right")
                metric("Последняя", value: model.lastDuration, icon: "timer")
                metric("Связь с телефоном", value: model.trusted ? "Установлена" : "Нет связи", icon: "antenna.radiowaves.left.and.right")
            }
            if !model.detail.isEmpty { LT(model.detail).font(.caption).foregroundStyle(secondary).frame(maxWidth: .infinity, alignment: .leading).textSelection(.enabled) }
        }
    }
    private func deviceCard(_ title: String, icon: String, subtitle: String, target: String) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack { Image(systemName: icon).font(.system(size: 26, weight: .light)).foregroundStyle(accent); Spacer(); if model.owner == target && !model.busy { pill("Маршрут подтверждён", icon: "speaker.wave.2", color: accent) } }
            LT(title).font(.system(size: 17, weight: .semibold)); LT(subtitle).font(.system(size: 11)).foregroundStyle(secondary)
            Button { model.request(target) } label: { HStack { LT(target == "mac" ? "Подключить к Mac" : "Подключить к телефону"); Spacer(); Image(systemName: "arrow.right") }.padding(.vertical, 6).frame(maxWidth: .infinity) }
                .buttonStyle(.bordered).disabled(model.manualBlockReason != nil).help(L(model.manualBlockReason ?? "Начать передачу наушников"))
        }.padding(20).frame(maxWidth: .infinity, alignment: .leading).card(surface, model.owner == target && !model.busy ? accent.opacity(0.4) : line)
    }
    private var transferProgress: some View {
        HStack(alignment: .top, spacing: 16) {
            ProgressView().controlSize(.small).padding(.top, 2)
            VStack(alignment: .leading, spacing: 6) {
                LT(model.headline).font(.callout.weight(.semibold))
                LT(model.transferProgress).font(.caption).foregroundStyle(secondary).fixedSize(horizontal: false, vertical: true)
                LT("Остановка ожидания не отменяет уже начатое системное подключение.").font(.caption).foregroundStyle(secondary).fixedSize(horizontal: false, vertical: true)
            }
            Spacer(); LButton("Остановить ожидание") { model.cancel("Ожидание остановлено. Системное подключение могло уже начаться.") }.controlSize(.small)
        }.padding(16).card(surface, line)
    }
    private var automation: some View {
        VStack(alignment: .leading, spacing: 20) {
            VStack(alignment: .leading, spacing: 18) {
                Toggle(isOn: $model.autoEnabled) { rowLabel("Автопереключение на Mac", "Для автоматической передачи включи этот переключатель и автоматизацию на телефоне.", icon: "sparkles") }.toggleStyle(.switch)
                Divider()
                LT(model.automationSummary).font(.headline)
                LT(model.autoReason).font(.callout).foregroundStyle(model.autoPaused ? warning : secondary)
                if model.hasAutoWait {
                    LButton(model.resumeAutoLabel, action: resumeAutomation).disabled(!model.canResumeAuto)
                    if let reason = model.resumeBlockReason { LT(reason).font(.caption).foregroundStyle(secondary) }
                }
                LT("Для передачи поставь музыку на паузу и запусти заново на нужном устройстве. Уже играющая музыка и запуск во время ожидания не вызывают передачу позже.").font(.caption).foregroundStyle(secondary).fixedSize(horizontal: false, vertical: true)
                LT("Сейчас на Mac: " + (model.observedSources.isEmpty ? "звук не обнаружен" : model.observedSources)).font(.caption).textSelection(.enabled)
                if model.observedSources.contains("com.apple.WebKit.") {
                    LT("Служебные процессы WebKit не запускают передачу: приложение-источник не определено.").font(.caption).foregroundStyle(secondary)
                }
            }.padding(22).card(surface, line)
            VStack(alignment: .leading, spacing: 18) {
                rowLabel("Параллельное подключение", "Отключение источника и подключение получателя начинаются одновременно. Передача завершена, когда обе стороны подтвердили результат.", icon: "arrow.left.arrow.right")
                if model.trusted && (model.peer.handoffVersion ?? 0) < 2 && !model.demo {
                    LLabel("Для параллельной передачи обнови Android. Пока используется последовательное подключение.", systemImage: "info.circle").font(.caption).foregroundStyle(warning)
                }
                Divider()
                LT("Когда передавать автоматически").font(.headline)
                LPicker("Правило автоматической передачи", selection: $model.idleOnly) { LT("При новом звуке").tag(false); LT("Когда источник на паузе").tag(true) }.pickerStyle(.segmented).labelsHidden().disabled(model.busy)
                LT(model.idleOnly ? "Сначала останови музыку на текущем устройстве, затем запусти на другом. Если оба играют, передачи не будет." : "Запусти музыку на другом устройстве — наушники перейдут к нему, даже если текущий источник ещё играет.").font(.callout).foregroundStyle(secondary).fixedSize(horizontal: false, vertical: true)
                LT("Правило выбирается здесь или с телефона и действует на оба направления. Оно применяется при следующем запуске музыки; само изменение правила не переключает наушники.").font(.caption).foregroundStyle(secondary).fixedSize(horizontal: false, vertical: true)
                Divider()
                Toggle(isOn: $model.held) { rowLabel("Запретить переключения", "Блокирует ручные и автоматические передачи с обоих устройств. Сними запрет здесь, чтобы продолжить.", icon: "lock.shield") }.toggleStyle(.switch)
                if model.peer.held { LLabel("Запрет переключений включён на Android. Отключи его на телефоне, чтобы разрешить передачи.", systemImage: "lock.fill").font(.callout).foregroundStyle(warning) }
                LT("Во время разговора или активного микрофона передачи приостановлены, чтобы не прервать звук.").font(.caption).foregroundStyle(secondary)
            }.padding(22).card(surface, line)
            VStack(alignment: .leading, spacing: 16) {
                LT("Приложения на Mac").font(.headline)
                LT("Только выбранные приложения запускают передачу на Mac. В браузере это может быть и реклама. Неоднозначные служебные процессы Apple не запускают передачу.").font(.callout).foregroundStyle(secondary)
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], alignment: .leading, spacing: 15) {
                    ForEach(selectableSources, id: \.0) { id, name in
                        Toggle(name, isOn: Binding(get: { model.allowed.contains(id) }, set: { if $0 { model.allowed.insert(id) } else { model.allowed.remove(id) } })).toggleStyle(.checkbox)
                    }
                }
                Divider()
                LToggle("Быстрое обнаружение · 0,5 с", isOn: $model.fastDetection)
                LT("Быстрый режим реагирует и на короткое видео в разрешённом браузере. Отключи его, чтобы использовать задержку ниже.").font(.caption).foregroundStyle(secondary)
                HStack { LT("Проверка нового звука"); Spacer(); LT(model.fastDetection ? "0,5 с" : "\(Int(model.delay)) с").monospacedDigit().foregroundStyle(accent) }.font(.callout)
                if !model.fastDetection { Slider(value: $model.delay, in: 2...5, step: 1).accessibilityLabel(L("Задержка автопереключения")) }
                HStack { LT("Пауза между передачами"); Spacer(); LT("\(Int(model.cooldown)) с").monospacedDigit().foregroundStyle(accent) }.font(.callout)
                Slider(value: $model.cooldown, in: 5...60, step: 5).accessibilityLabel(L("Пауза между автоматическими передачами"))
                LT("Защищает от переключения туда-обратно. После отсчёта нужно новое нажатие Play; ручные кнопки доступны сразу.").font(.caption).foregroundStyle(secondary)
                HStack { LT("Приоритет ручной команды"); Spacer(); LT("\(Int(model.manualPriority)) с").monospacedDigit().foregroundStyle(accent) }.font(.callout)
                Slider(value: $model.manualPriority, in: 0...120, step: 5).accessibilityLabel(L("Приоритет ручной команды"))
                LT("После ручной команды автоматика ждёт этот срок или паузу между передачами — что закончится позже.").font(.caption).foregroundStyle(secondary)
            }.padding(22).card(surface, line)
        }
    }
    private var devices: some View {
        VStack(alignment: .leading, spacing: 20) {
            VStack(alignment: .leading, spacing: 18) {
                rowLabel("1. Выбери наушники", "Сначала сопряги их с обеими ОС через настройки Bluetooth.", icon: "headphones")
                LPicker("Наушники", selection: $model.selected) { LT("Выбрать устройство").tag(""); ForEach(model.headsets) { Text(verbatim: "\($0.name) · \($0.id)").tag($0.id) } }.disabled(model.busy)
                HStack { LButton("Обновить список") { model.refresh(); showNotice("Список обновлён: \(model.headsets.count) устройств") }; LButton("Настройки Bluetooth", action: model.bluetoothSettings) }
                Text(verbatim: localizedSelectionSummary).font(.system(size: 12, design: .monospaced)).foregroundStyle(model.selectionMismatch ? warning : secondary).textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
                if model.selectionMismatch { LLabel("Выбери на обоих устройствах наушники с одинаковым адресом", systemImage: "exclamationmark.triangle").font(.caption).foregroundStyle(warning) }
                LT("Текущий системный выход: \(model.route)").font(.caption).foregroundStyle(secondary)
            }.padding(22).card(surface, line)
            VStack(alignment: .leading, spacing: 18) {
                rowLabel("2. Свяжи Android с Mac", "Открой Seamless Headphones на телефоне и добавь ключ этого Mac.", icon: "link")
                HStack { pill(model.link, icon: model.trusted ? "checkmark.shield" : "antenna.radiowaves.left.and.right", color: model.trusted ? accent : warning); Spacer() }
                HStack {
                    if model.enabled { LButton(model.showPairing ? "Скрыть QR и ключ" : "Показать QR и ключ") { model.showPairing.toggle() }; LButton("Остановить связь", action: model.disable) }
                    else { LButton("Связать телефон", action: model.enable).buttonStyle(.borderedProminent) }
                    Spacer(); if model.enabled { LButton("Отозвать доступ", role: .destructive) { revoke = true } }
                }
                if model.showPairing && !model.pairingCode.isEmpty {
                    HStack(alignment: .center, spacing: 22) {
                        PairingQR(key: model.pairingCode, device: model.selected, name: model.selectedName)
                        VStack(alignment: .leading, spacing: 10) {
                            LT("Наведи камеру телефона").font(.headline)
                            LT("На Android открой «Устройства» → «Сканировать QR с Mac». Подтверди сохранение ключа и нажми «Найти Mac».").font(.callout).foregroundStyle(secondary).fixedSize(horizontal: false, vertical: true)
                            LT("QR содержит ключ доступа. Показывай его только своему телефону.").font(.caption).foregroundStyle(secondary).fixedSize(horizontal: false, vertical: true)
                        }.frame(maxWidth: .infinity, alignment: .leading)
                    }
                    Text(verbatim: model.pairingCode).font(.system(size: 13, design: .monospaced)).textSelection(.enabled).padding(14).frame(maxWidth: .infinity, alignment: .leading).background(canvas, in: RoundedRectangle(cornerRadius: 12))
                    HStack { LT("Ключ даёт управление твоими наушниками.").font(.caption).foregroundStyle(secondary); Spacer(); LButton("Скопировать ключ") { NSPasteboard.general.clearContents(); showNotice(NSPasteboard.general.setString(model.pairingCode, forType: .string) ? "Ключ скопирован" : "Не удалось скопировать ключ") } }
                }
            }.padding(22).card(surface, line)
            VStack(alignment: .leading, spacing: 14) {
                rowLabel("3. Разреши автоматизацию на телефоне", "Доступ к медиасессиям распознаёт Play/Pause. Состояние вызовов защищает разговоры. Настрой эти разрешения в приложении Android.", icon: "checklist")
                HStack { pill(model.peer.available ? "Сигналы телефона доступны" : "Ожидаем настройку Android", icon: model.peer.available ? "checkmark" : "clock", color: model.peer.available ? accent : warning) }
                LT("Подключение A2DP зависит от прошивки Android. При системном запрете Seamless покажет ошибку и приостановит автоматику.").font(.caption).foregroundStyle(secondary)
            }.padding(22).card(surface, line)
        }
    }
    private var settings: some View {
        VStack(alignment: .leading, spacing: 20) {
            VStack(alignment: .leading, spacing: 16) {
                LT("Язык").font(.headline)
                LPicker("Язык приложения", selection: $localization.language) {
                    ForEach(UILanguage.allCases) { language in
                        Text(verbatim: language == .system ? L(language.title) : language.title).tag(language)
                    }
                }.pickerStyle(.segmented).labelsHidden()
            }.padding(22).card(surface, line)
            VStack(alignment: .leading, spacing: 18) {
                LT("Оформление").font(.headline)
                HStack(spacing: 12) {
                    ForEach([("system", "Системная", "circle.lefthalf.filled"), ("light", "Светлая", "sun.max"), ("dark", "Тёмная", "moon")], id: \.0) { value, title, icon in
                        Button { model.theme = value } label: {
                            VStack(spacing: 12) { Image(systemName: icon).font(.system(size: 26, weight: .light)); LT(title).font(.callout) }.frame(maxWidth: .infinity).padding(.vertical, 22)
                                .background(model.theme == value ? accent.opacity(0.12) : canvas, in: RoundedRectangle(cornerRadius: 14)).overlay(RoundedRectangle(cornerRadius: 14).stroke(model.theme == value ? accent : line))
                        }.buttonStyle(.plain).foregroundStyle(model.theme == value ? accent : .primary).accessibilityValue(L(model.theme == value ? "Выбрано" : "Не выбрано"))
                    }
                }
                HStack(spacing: 15) {
                    LT("Цвет акцента").font(.callout); Spacer()
                    ForEach([("mint", "Мята", Color(red: 0.17, green: 0.63, blue: 0.47)), ("cobalt", "Кобальт", .blue), ("iris", "Ирис", .purple)], id: \.0) { value, title, color in
                        Button { model.accent = value } label: { Circle().fill(color).frame(width: 28, height: 28).overlay { if model.accent == value { Image(systemName: "checkmark").font(.caption.bold()).foregroundStyle(.white) } } }.buttonStyle(.plain).accessibilityLabel(L(title)).accessibilityValue(L(model.accent == value ? "Выбрано" : "Не выбрано")).help(L(title))
                    }
                }
            }.padding(22).card(surface, line)
            Toggle(isOn: $model.reconnect) { rowLabel("Восстанавливать связь", "Возвращаться к связи после сна Mac и временных обрывов.", icon: "arrow.triangle.2.circlepath") }.toggleStyle(.switch).padding(22).card(surface, line)
            VStack(alignment: .leading, spacing: 14) {
                HStack { LT("История Mac").font(.headline); Spacer(); LButton("Скопировать весь отчёт") { showNotice(model.copyDiagnostics() ? "Полный отчёт скопирован" : "Не удалось скопировать отчёт") }; LButton("Очистить") { model.clearLogs(); showNotice("История и технический журнал очищены") }.disabled(model.events.isEmpty && model.debugEvents.isEmpty) }
                LT("События этого Mac. Ответы телефона отмечены [Android].").font(.caption).foregroundStyle(secondary)
                LTextField("Поиск в событиях", text: $search).textFieldStyle(.roundedBorder)
                if model.events.isEmpty { LLabel("События появятся после подключения", systemImage: "clock").font(.caption).foregroundStyle(secondary) }
                else if !search.isEmpty && !model.events.contains(where: { L($0).localizedCaseInsensitiveContains(search) }) { LT("По запросу ничего не найдено").font(.caption).foregroundStyle(secondary) }
                ForEach(Array(model.events.filter { search.isEmpty || L($0).localizedCaseInsensitiveContains(search) }.prefix(15).enumerated()), id: \.offset) { _, event in LT(event).font(.system(size: 11, design: .monospaced)).foregroundStyle(secondary).textSelection(.enabled); Divider() }
                Divider()
                LToggle("Технические логи", isOn: $model.debugEnabled).toggleStyle(.switch)
                LT("Включи на обоих устройствах, повтори одну передачу и скопируй диагностику с каждого. Журнал содержит адреса, этапы, задержки и очередь BLE; последние 1000 записей остаются в памяти приложения.").font(.caption).foregroundStyle(secondary).fixedSize(horizontal: false, vertical: true)
                if model.debugEnabled {
                    LT("Ниже последние 80 записей. «Скопировать весь отчёт» включает до 1000 записей и состояние устройств.").font(.caption).foregroundStyle(secondary)
                    ScrollView {
                        Text(verbatim: model.debugEvents.isEmpty ? L("Журнал пуст. Повтори передачу, чтобы записать её этапы.") : model.debugEvents.prefix(80).joined(separator: "\n")).font(.system(size: 11, design: .monospaced)).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
                    }.frame(height: 220).padding(12).background(canvas, in: RoundedRectangle(cornerRadius: 12))
                }
                LT("Ключи не попадают в журнал. Приложение не записывает звук и не отправляет телеметрию.").font(.caption).foregroundStyle(secondary)
            }.padding(22).card(surface, line)
        }
    }
    private func rowLabel(_ title: String, _ subtitle: String, icon: String) -> some View {
        HStack(alignment: .top, spacing: 13) { Image(systemName: icon).font(.system(size: 21, weight: .light)).foregroundStyle(accent).frame(width: 28); VStack(alignment: .leading, spacing: 6) { LT(title).font(.system(size: 15, weight: .semibold)); LT(subtitle).font(.system(size: 12)).foregroundStyle(secondary).fixedSize(horizontal: false, vertical: true) } }
    }
    private func showNotice(_ text: String) {
        notice = text
        DispatchQueue.main.asyncAfter(deadline: .now() + 4) { if notice == text { notice = "" } }
    }
    private func resumeAutomation() {
        showNotice(model.resumeAuto() ? "Пауза сброшена. Запусти музыку заново." : model.resumeBlockReason ?? "Условия изменились. Проверь состояние автоматизации.")
    }
    private func metric(_ title: String, value: String, icon: String) -> some View {
        VStack(alignment: .leading, spacing: 9) { HStack { LT(title).font(.system(size: 11)); Spacer(); Image(systemName: icon) }.foregroundStyle(secondary); LT(value).font(.system(size: 20, weight: .semibold, design: .rounded)) }.padding(17).frame(maxWidth: .infinity, alignment: .leading).card(surface, line)
    }
    private func pill(_ title: String, icon: String, color: Color) -> some View {
        LLabel(title, systemImage: icon).font(.system(size: 10, weight: .semibold)).foregroundStyle(color).padding(.horizontal, 10).padding(.vertical, 6).background(color.opacity(0.10), in: Capsule())
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
            else { LT("QR недоступен").foregroundStyle(.black) }
        }.frame(width: 184, height: 184).padding(20).background(.white, in: RoundedRectangle(cornerRadius: 12))
            .accessibilityLabel(L("QR-код связи с Mac"))
            .task(id: key + device + name) { image = PairingCode.image(key: key, device: device, name: name) }
    }
}
