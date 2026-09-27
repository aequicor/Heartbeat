package io.aequicor.heartbeat.platform.uikitsandbox

import androidx.compose.ui.window.ComposeUIViewController
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.catalog.UIKitSandboxApp
import platform.UIKit.UIViewController

/** Swift entry point for the standalone UIKit sandbox. */
fun sandboxViewController(): UIViewController {
    Log.init(isDebug = true)
    Log.tag("UIKitSandbox/iOS").i { "open sandbox" }
    return ComposeUIViewController { UIKitSandboxApp() }
}
