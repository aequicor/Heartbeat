package io.aequicor.heartbeat.feature.harness.impl.data.ownership

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.harness.impl.domain.decision.ProcessDecisionHost
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessDataStorage
import kotlin.time.Clock

/** Profile-owned native facilities shared by activation-scoped ownership adapters. */
@SingleIn(ProfileScope::class)
@Inject
internal data class OwnershipRuntimeHost(
    val data: HarnessDataStorage,
    val runtime: ProfileOwnershipRuntime,
    val workspace: OwnershipWorkspaceVerifier,
    val decisions: ProcessDecisionHost,
    val clock: Clock,
) {
    override fun toString(): String = "OwnershipRuntimeHost(***)"
}
