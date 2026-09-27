package io.aequicor.heartbeat.platform.shared

import androidx.compose.ui.window.ComposeUIViewController
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.ApplicationLifecycle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.platform.dibundle.createHeartbeatGraph

/** Application-owned host retained by SwiftUI, independent of view recreation. */
class IosHeartbeatHost {
    private val lifecycle = ApplicationLifecycle()
    private val root = run {
        Log.init(isDebug = false)
        createAppRoot(DefaultComponentContext(lifecycle), createHeartbeatGraph())
    }

    /** Creates a view for the retained application root. */
    fun viewController() = ComposeUIViewController { App(root) }

    /** Forwards an OS URL to the navigation root. */
    fun openUrl(url: String) = root.handleDeepLink(url)
}
