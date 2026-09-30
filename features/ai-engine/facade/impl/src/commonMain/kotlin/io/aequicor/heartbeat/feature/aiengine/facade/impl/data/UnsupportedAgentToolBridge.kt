package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridge
import io.aequicor.heartbeat.feature.aiengine.facade.api.UnavailableAgentToolBridge

/** Default mobile implementation; the Desktop contribution has a higher priority. */
@Inject
@ContributesBinding(ProfileScope::class, priority = 0)
internal class UnsupportedAgentToolBridge : AgentToolBridge by UnavailableAgentToolBridge
