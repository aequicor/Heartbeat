package io.aequicor.heartbeat.platform.uikitsandbox

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.adaptive.detectDesktopPlatformUi
import io.aequicor.heartbeat.ds.catalog.UIKitSandboxApp
import io.aequicor.heartbeat.ds.tokens.HbDimensions

private val log = Log.tag("UIKitSandbox/Desktop")

fun main() {
    Log.init(isDebug = true)
    val platformUi = detectDesktopPlatformUi()
    log.i { "start platform=$platformUi" }
    application {
        Window(
            onCloseRequest = {
                log.i { "close window" }
                exitApplication()
            },
            title = "Aequicor · UIKit Sandbox",
            state = rememberWindowState(width = HbDimensions().windowWidth, height = HbDimensions().windowHeight),
        ) {
            UIKitSandboxApp(initialPlatformUi = platformUi)
        }
    }
}
