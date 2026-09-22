import AppKit
import SwiftUI

final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuItemValidation {
    var window: NSWindow!
    var item: NSStatusItem!
    var model: AppModel!
    func applicationDidFinishLaunching(_ notification: Notification) {
        let args = ProcessInfo.processInfo.arguments
        model = AppModel(demo: args.contains("--demo"))
        if args.contains("--dark") { model.theme = "dark" }
        if args.contains("--light") { model.theme = "light" }
        let mainMenu = NSMenu()
        let appMenuItem = NSMenuItem(); let appMenu = NSMenu()
        let quitItem = NSMenuItem(title: "Завершить Seamless Headphones", action: #selector(quit), keyEquivalent: "q"); quitItem.target = self
        appMenu.addItem(quitItem); appMenuItem.submenu = appMenu; mainMenu.addItem(appMenuItem)
        let editItem = NSMenuItem(); let edit = NSMenu(title: "Правка")
        edit.addItem(withTitle: "Копировать", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        edit.addItem(withTitle: "Выделить всё", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        editItem.submenu = edit; mainMenu.addItem(editItem); NSApp.mainMenu = mainMenu
        let view = NSHostingView(rootView: Dashboard(model: model, initialPage: args.contains("--automation") && model.demo ? .automation : args.contains("--devices") && model.demo ? .devices : args.contains("--settings") && model.demo ? .settings : .overview))
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1080, height: 790), styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
        window.title = "Seamless Headphones"; window.contentView = view; window.center(); window.isReleasedWhenClosed = false
        window.makeKeyAndOrderFront(nil); NSApp.activate(ignoringOtherApps: true)
        item = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        item.button?.image = NSImage(systemSymbolName: "airpods.pro", accessibilityDescription: "Seamless Headphones")
        let menu = NSMenu()
        for (title, action) in [("Открыть Seamless Headphones", #selector(show)), ("Забрать на Mac", #selector(toMac)), ("Передать на телефон", #selector(toPhone)), ("Завершить", #selector(quit))] {
            let entry = NSMenuItem(title: title, action: action, keyEquivalent: ""); entry.target = self; menu.addItem(entry)
        }
        item.menu = menu
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
    func validateMenuItem(_ menuItem: NSMenuItem) -> Bool {
        if menuItem.action == #selector(toMac) || menuItem.action == #selector(toPhone) {
            return model.trusted && !model.busy && !model.held && !model.selected.isEmpty && !model.selectionMismatch
        }
        return true
    }
    @objc func show() { window.makeKeyAndOrderFront(nil); NSApp.activate(ignoringOtherApps: true) }
    @objc func toMac() { model.request("mac") }
    @objc func toPhone() { model.request("android") }
    @objc func quit() { model.disable(); NSApp.terminate(nil) }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }
}

@main struct Main {
    static func main() {
        let app = NSApplication.shared
        let delegate = AppDelegate(); app.delegate = delegate; app.setActivationPolicy(.regular); app.run()
        withExtendedLifetime(delegate) {}
    }
}
