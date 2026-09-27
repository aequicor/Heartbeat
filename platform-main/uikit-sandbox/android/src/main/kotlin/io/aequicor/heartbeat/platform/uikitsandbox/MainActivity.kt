package io.aequicor.heartbeat.platform.uikitsandbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.catalog.UIKitSandboxApp

/** Dedicated sandbox launcher, independent of the studio application and its graph. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.init(isDebug = true)
        Log.tag("UIKitSandbox/Android").i { "open sandbox" }
        setContent { UIKitSandboxApp() }
    }
}
