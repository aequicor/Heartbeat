import SwiftUI
import Shared

class AppDelegate: NSObject, UIApplicationDelegate {
    let host = IosHeartbeatHost()
}

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate
    var body: some Scene {
        WindowGroup {
            ContentView(host: delegate.host)
                .onOpenURL { delegate.host.openUrl(url: $0.absoluteString) }
        }
    }
}