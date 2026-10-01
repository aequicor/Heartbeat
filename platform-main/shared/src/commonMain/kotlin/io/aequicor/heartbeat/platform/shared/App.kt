package io.aequicor.heartbeat.platform.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.arkivanov.decompose.ComponentContext
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbWindowDragArea
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioRoute
import io.aequicor.heartbeat.feature.welcome.api.WelcomeRoute
import io.aequicor.heartbeat.platform.dibundle.HeartbeatGraph
import io.aequicor.heartbeat.platform.dibundle.root.HeartbeatRoot
import io.aequicor.heartbeat.platform.dibundle.root.RootStart
import io.aequicor.heartbeat.platform.root.RootContent
import io.aequicor.heartbeat.platform.shared.resources.Res
import io.aequicor.heartbeat.platform.shared.resources.computer_use_stop
import io.aequicor.heartbeat.platform.shared.resources.computer_use_working
import org.jetbrains.compose.resources.stringResource

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

/** Shared rendering; while the profile is restored the window shows its plain backdrop. */
@Composable
fun App(root: HeartbeatRoot, modifier: Modifier = Modifier) {
    HbTheme {
        val computerUse by root.computerUse.collectAsState()
        HbColumn(modifier.fillMaxSize(), gap = HbTheme.spacing.none) {
            if (computerUse.isActive && HbTheme.dimensions.isDesktop) {
                HbWindowDragArea(Modifier.fillMaxWidth().height(HbTheme.dimensions.headerHeight)) {
                    HbRow(
                        Modifier.fillMaxSize().background(HbTheme.surfaces.sidebar)
                            .padding(horizontal = HbTheme.spacing.m),
                        gap = HbTheme.spacing.m,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HbText(
                            stringResource(Res.string.computer_use_working),
                            modifier = Modifier.weight(1f),
                            style = HbTheme.typography.caption,
                            maxLines = 1,
                        )
                        HbButton(
                            stringResource(Res.string.computer_use_stop),
                            root::stopComputerUse,
                            Modifier.testTag("computer-use-stop-agent"),
                            style = HbButtonStyle.Danger,
                            size = HbButtonSize.Small,
                        )
                    }
                }
            }
            RootContent(root, Modifier.weight(1f), loading = {
                Box(Modifier.fillMaxSize().background(HbTheme.surfaces.backdrop)) {
                    if (HbTheme.dimensions.isDesktop) {
                        HbWindowDragArea(Modifier.fillMaxWidth().height(HbTheme.dimensions.headerHeight)) { }
                    }
                }
            })
        }
    }
}
