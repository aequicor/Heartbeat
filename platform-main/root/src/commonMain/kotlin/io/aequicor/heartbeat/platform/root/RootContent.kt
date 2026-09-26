package io.aequicor.heartbeat.platform.root

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import io.aequicor.heartbeat.core.navigation.RootHost
import io.aequicor.heartbeat.core.navigation.compose.NavSharedTransitionLayout
import io.aequicor.heartbeat.core.navigation.compose.NavStack
import io.aequicor.heartbeat.platform.dibundle.root.HeartbeatRoot
import io.aequicor.heartbeat.platform.dibundle.root.RootChild

/**
 * Renders the [root]: the guest tree or the tree of the active profile, cross-fading on sign-in / sign-out.
 * Shared element transitions (`NavTransition.Expand`) work across every host below.
 * [loading] is shown while the persisted profile is being restored (a splash of the platform).
 *
 * ```
 * setContent { HbTheme { RootContent(root) } }
 * ```
 */
@Composable
fun RootContent(root: HeartbeatRoot, modifier: Modifier = Modifier, loading: @Composable () -> Unit = {}) {
    val slot by root.slot.subscribeAsState()
    NavSharedTransitionLayout(modifier) {
        Crossfade(targetState = slot.child?.instance, label = "root") { child ->
            val host: RootHost? = when (child) {
                is RootChild.Guest -> child.host
                is RootChild.Profile -> child.host.collectAsState().value
                null -> null
            }
            if (host == null) loading() else NavStack(host, Modifier.fillMaxSize())
        }
    }
}
