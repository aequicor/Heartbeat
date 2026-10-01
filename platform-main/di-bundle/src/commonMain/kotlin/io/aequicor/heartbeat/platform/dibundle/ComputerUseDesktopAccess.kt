package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.ContributesTo
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools

/** Profile tool barrier used by the desktop session's explicit stop action. */
@ContributesTo(ProfileScope::class)
interface ComputerUseDesktopAccess {
    /** Revokes pending and future hosted calls for the exact captured turn. */
    val agentTools: ProfileAgentTools
}
