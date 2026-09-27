import SwiftUI
import UIKitSandbox

@main
struct SandboxApp: App {
    var body: some Scene {
        WindowGroup {
            SandboxView().ignoresSafeArea()
        }
    }
}

struct SandboxView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        SandboxViewControllerKt.sandboxViewController()
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
