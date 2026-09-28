import AppKit

/// Compiled only with -D MENU_BAR_SMOKE_TEST alongside the real app entry point.
/// Run with --demo so the lifecycle checks cannot touch Bluetooth or saved settings.
enum MenuBarSmokeTests {
    static func schedule(delegate: AppDelegate) {
        precondition(ProcessInfo.processInfo.arguments.contains("--demo"), "Lifecycle smoke must run in demo mode")
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
            func check(_ condition: @autoclosure () -> Bool, _ name: String) {
                precondition(condition(), name)
                print("PASS \(name)")
            }
            check(delegate.model.demo, "smoke uses an isolated demo model")
            check(NSApp.activationPolicy() == .accessory, "app starts without a Dock icon")
            check(delegate.window.isVisible, "dashboard is visible on launch")
            check(delegate.item.isVisible, "menu-bar item is installed")
            let menu = delegate.item.menu!
            let openIndex = menu.items.firstIndex { $0.action == #selector(AppDelegate.show) }!
            let quitIndex = menu.items.firstIndex { $0.action == #selector(AppDelegate.quit) }!
            check(delegate.validateMenuItem(menu.items[openIndex]), "Open is always available")
            check(delegate.validateMenuItem(menu.items[quitIndex]), "Quit is always available")

            delegate.window.performClose(nil)
            check(!delegate.window.isVisible, "closing hides the dashboard")
            check(NSApp.isRunning && delegate.item.isVisible, "closing keeps the app and menu-bar item alive")
            check(delegate.model.enabled && delegate.model.trusted, "closing preserves the connection state")
            check(!delegate.applicationShouldTerminateAfterLastWindowClosed(NSApp), "last window does not terminate the app")

            menu.performActionForItem(at: openIndex)
            check(delegate.window.isVisible, "menu Open reopens the same dashboard")
            check(NSApp.activationPolicy() == .accessory, "reopening keeps the app out of Dock")

            let commandW = NSEvent.keyEvent(with: .keyDown, location: .zero, modifierFlags: .command,
                                           timestamp: 0, windowNumber: delegate.window.windowNumber,
                                           context: nil, characters: "w", charactersIgnoringModifiers: "w",
                                           isARepeat: false, keyCode: 13)!
            check(NSApp.mainMenu!.performKeyEquivalent(with: commandW), "Command-W invokes Close Window")
            check(!delegate.window.isVisible && delegate.item.isVisible, "Command-W leaves the menu-bar app running")
            check(delegate.applicationShouldHandleReopen(NSApp, hasVisibleWindows: false), "launching the app again is handled")
            check(delegate.window.isVisible && NSApp.activationPolicy() == .accessory, "relaunch reopens without adding a Dock icon")

            // Test the real Quit target only after recording successful lifecycle checks.
            _ = NotificationCenter.default.addObserver(forName: NSApplication.willTerminateNotification, object: nil, queue: .main) { _ in
                check(!delegate.model.enabled, "Quit stops the connection before exiting")
                print("Menu bar lifecycle: 17 checks passed")
            }
            menu.performActionForItem(at: quitIndex)
        }
    }
}
