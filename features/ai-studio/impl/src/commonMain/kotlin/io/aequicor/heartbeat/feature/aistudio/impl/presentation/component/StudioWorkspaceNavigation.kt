package io.aequicor.heartbeat.feature.aistudio.impl.presentation.component

import io.aequicor.heartbeat.core.navigation.LaunchMode
import io.aequicor.heartbeat.core.navigation.NavOptions
import io.aequicor.heartbeat.core.navigation.NavTarget
import io.aequicor.heartbeat.core.navigation.NavTransition
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatRoute

/**
 * Chat area navigation inside the studio workspace stack, whose base entry is [StudioChatRoute]. Research opens
 * above the studio's chat panes; returning to the chats replaces the whole stack with the base entry, so a closed
 * research entry (with its retained graph and machine) never stays alive underneath or comes back on "back".
 */
internal class StudioWorkspaceNavigation(private val workspace: Navigator) {
    /** Shows research for [target] above the chat panes; an equal research entry on top is kept. */
    fun openResearch(target: EngineTarget) = workspace.navigate(
        ResearchChatRoute(target),
        NavOptions(LaunchMode.SingleTop, NavTarget.Nearest, NavTransition.Fade),
    )

    /** Returns to the chat panes, destroying every entry above them. */
    fun closeChatArea() = workspace.navigate(
        StudioChatRoute,
        NavOptions(LaunchMode.ReplaceAll, NavTarget.Nearest, NavTransition.Fade),
    )
}
