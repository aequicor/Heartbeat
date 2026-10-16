package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityLevel
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityState
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSecurityMachine
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSecurityPolicy
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Reads only accepted policy; a pending save never prematurely expands the next turn's trust. */
@Inject
@ContributesBinding(ProfileScope::class)
internal class MachineStudioSecurityPolicy(machine: StudioSecurityMachine) : StudioSecurityPolicy {
    override val levels = machine.state.map { it.effectiveLevel() }.filterNotNull().distinctUntilChanged()

    override suspend fun level(): StudioSecurityLevel = levels.first()
}

private fun StudioSecurityState.effectiveLevel(): StudioSecurityLevel? = when (this) {
    StudioSecurityState.Idle, StudioSecurityState.Loading -> null
    StudioSecurityState.LoadError -> StudioSecurityLevel.Ask
    is StudioSecurityState.Ready -> level
    is StudioSecurityState.Saving -> level
}
