package io.aequicor.heartbeat.platform.shared

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.ComponentContext
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.ds.components.HbCinematicBackdrop
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioRoute
import io.aequicor.heartbeat.feature.welcome.api.WelcomeRoute
import io.aequicor.heartbeat.platform.dibundle.HeartbeatGraph
import io.aequicor.heartbeat.platform.dibundle.root.HeartbeatRoot
import io.aequicor.heartbeat.platform.dibundle.root.RootStart
import io.aequicor.heartbeat.platform.root.RootContent

/**
 * Creates the root once outside composition, using the platform lifecycle. A profile always starts in the studio;
 * the engine runtime toggle only picks the studio backend.
 */
fun createAppRoot(context: ComponentContext, graph: HeartbeatGraph): HeartbeatRoot = HeartbeatRoot(
    context,
    graph,
    RootStart(guest = listOf(WelcomeRoute), profile = listOf(AiStudioRoute)),
    localProfile = ProfileId("local"),
)

/** Shared rendering; the welcome feature owns its light cinematic theme. */
@Composable
fun App(root: HeartbeatRoot, modifier: Modifier = Modifier) {
    HbTheme {
        RootContent(root, modifier, loading = {
            HbTheme(darkTheme = false) { HbCinematicBackdrop({ 0f }) }
        })
    }
}
