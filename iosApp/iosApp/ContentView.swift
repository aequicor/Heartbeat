import UIKit
import SwiftUI
import Shared

struct ComposeView: UIViewControllerRepresentable {
    let host: IosHeartbeatHost
    func makeUIViewController(context: Self.Context) -> UIViewController {
        host.viewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

struct ContentView: View {
    let host: IosHeartbeatHost
    var body: some View {
        ComposeView(host: host)
            .ignoresSafeArea()
    }
}