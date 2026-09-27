package io.aequicor.heartbeat.platform.android

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.arkivanov.decompose.defaultComponentContext
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.platform.dibundle.HeartbeatGraph
import io.aequicor.heartbeat.platform.dibundle.createHeartbeatGraph
import io.aequicor.heartbeat.platform.dibundle.root.HeartbeatRoot
import io.aequicor.heartbeat.platform.shared.App
import io.aequicor.heartbeat.platform.shared.createAppRoot

/** Keeps the app graph across Activity recreation without retaining an Activity. */
class HeartbeatApplication : Application() {
    lateinit var graph: HeartbeatGraph
        private set

    override fun onCreate() {
        super.onCreate()
        Log.init(isDebug = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        graph = createHeartbeatGraph(this)
    }
}

class MainActivity : ComponentActivity() {
    private var root: HeartbeatRoot? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val component = createAppRoot(defaultComponentContext(), (application as HeartbeatApplication).graph)
        root = component
        if (savedInstanceState == null) intent.data?.let { component.handleDeepLink(it.toString()) }
        setContent { App(component) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { root?.handleDeepLink(it.toString()) }
    }
}
