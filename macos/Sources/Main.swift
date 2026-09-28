import AppKit
import SwiftUI
import Combine

final class AppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate, NSMenuItemValidation {
    var window: NSWindow!
    var item: NSStatusItem!
    var model: AppModel!
    private var appearanceSubscription: AnyCancellable?
    private var languageSubscription: AnyCancellable?
    func applicationDidFinishLaunching(_ notification: Notification) {
        let args = ProcessInfo.processInfo.arguments
        model = AppModel(demo: args.contains("--demo"))
        UILocalization.shared.configure(demo: model.demo, override: args.contains("--english") ? .english : args.contains("--russian") ? .russian : args.contains("--system-language") ? .system : nil)
        if args.contains("--dark") { model.theme = "dark" }
        if args.contains("--light") { model.theme = "light" }
        let view = NSHostingView(rootView: Dashboard(model: model, initialPage: args.contains("--automation") && model.demo ? .automation : args.contains("--devices") && model.demo ? .devices : args.contains("--settings") && model.demo ? .settings : .overview))
        let compactPreview = model.demo && args.contains("--compact")
        let visible = NSScreen.main?.visibleFrame.size ?? NSSize(width: 1080, height: 820)
        let size = NSSize(width: compactPreview ? 800 : min(1080, visible.width), height: compactPreview ? 560 : min(790, visible.height - 40))
        window = NSWindow(contentRect: NSRect(origin: .zero, size: size), styleMask: [.titled, .closable, .resizable], backing: .buffered, defer: false)
        window.title = "Sound Shift"; window.contentView = view; window.center(); window.isReleasedWhenClosed = false
        window.delegate = self
        window.collectionBehavior = .moveToActiveSpace
        // Keep AppKit title bars, menus and native controls in the same theme as SwiftUI.
        // nil restores live system appearance, including changes while the app is open.
        appearanceSubscription = model.$theme.sink { [weak self] theme in
            self?.window.appearance = theme == "dark" ? NSAppearance(named: .darkAqua) : theme == "light" ? NSAppearance(named: .aqua) : nil
        }
        item = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        item.button?.image = NSImage(systemSymbolName: "airpods.pro", accessibilityDescription: "Sound Shift")
        item.button?.toolTip = "Sound Shift"
        rebuildMenus()
        show()
        languageSubscription = UILocalization.shared.objectWillChange.receive(on: RunLoop.main).sink { [weak self] _ in self?.rebuildMenus() }
        if let index = args.firstIndex(of: "--render-preview"), args.count > index + 1, args.contains("--demo") {
            let path = args[index + 1]
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
                if let bitmap = view.bitmapImageRepForCachingDisplay(in: view.bounds) {
                    view.cacheDisplay(in: view.bounds, to: bitmap)
                    try? bitmap.representation(using: .png, properties: [:])?.write(to: URL(fileURLWithPath: path))
                }
                NSApp.terminate(nil)
            }
        }
    }
    private func rebuildMenus() {
        let mainMenu = NSMenu()
        let appMenuItem = NSMenuItem(); let appMenu = NSMenu()
        let quitItem = NSMenuItem(title: L("Завершить Sound Shift"), action: #selector(quit), keyEquivalent: "q"); quitItem.target = self
        appMenu.addItem(quitItem); appMenuItem.submenu = appMenu; mainMenu.addItem(appMenuItem)
        let editItem = NSMenuItem(); let edit = NSMenu(title: L("Правка"))
        edit.addItem(withTitle: L("Вырезать"), action: #selector(NSText.cut(_:)), keyEquivalent: "x")
        edit.addItem(withTitle: L("Копировать"), action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        edit.addItem(withTitle: L("Вставить"), action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        edit.addItem(withTitle: L("Выделить всё"), action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        editItem.submenu = edit; mainMenu.addItem(editItem)
        let windowMenuItem = NSMenuItem(); let windowMenu = NSMenu(title: L("Окно"))
        let closeItem = NSMenuItem(title: L("Закрыть окно"), action: #selector(closeWindow), keyEquivalent: "w"); closeItem.target = self
        windowMenu.addItem(closeItem); windowMenuItem.submenu = windowMenu; mainMenu.addItem(windowMenuItem)
        NSApp.mainMenu = mainMenu
        let menu = NSMenu()
        for (title, action) in [("Открыть Sound Shift", #selector(show)), ("Забрать на Mac", #selector(toMac)), ("Передать на телефон", #selector(toPhone)), ("Завершить Sound Shift", #selector(quit))] {
            if action == #selector(toMac) || action == #selector(quit) { menu.addItem(.separator()) }
            let entry = NSMenuItem(title: L(title), action: action, keyEquivalent: ""); entry.target = self; menu.addItem(entry)
        }
        item.menu = menu
    }
    func validateMenuItem(_ menuItem: NSMenuItem) -> Bool {
        if menuItem.action == #selector(toMac) || menuItem.action == #selector(toPhone) {
            menuItem.toolTip = model.manualBlockReason.map(L)
            return model.manualBlockReason == nil
        }
        return true
    }
    @objc func show() {
        if window.isMiniaturized { window.deminiaturize(nil) }
        window.makeKeyAndOrderFront(nil); NSApp.activate(ignoringOtherApps: true)
    }
    @objc func toMac() { model.request("mac") }
    @objc func toPhone() { model.request("android") }
    @objc func closeWindow() { window.performClose(nil) }
    @objc func quit() { model.disable(); NSApp.terminate(nil) }
    func windowShouldClose(_ sender: NSWindow) -> Bool {
        // Closing the dashboard keeps the menu-bar item and connection alive.
        sender.orderOut(nil)
        return false
    }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        show(); return true
    }
}

@main struct Main {
    static func main() {
        let app = NSApplication.shared
        let delegate = AppDelegate(); app.delegate = delegate; app.setActivationPolicy(.accessory)
        #if MENU_BAR_SMOKE_TEST
        MenuBarSmokeTests.schedule(delegate: delegate)
        #endif
        app.run()
        withExtendedLifetime(delegate) {}
    }
}
