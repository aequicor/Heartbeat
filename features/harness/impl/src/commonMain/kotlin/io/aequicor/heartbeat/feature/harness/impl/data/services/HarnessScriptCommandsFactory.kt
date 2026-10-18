package io.aequicor.heartbeat.feature.harness.impl.data.services

import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessCreationScope
import io.aequicor.heartbeat.feature.harness.impl.domain.decision.HarnessProcessCommandHost
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessCommandHost
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptCommands
import io.aequicor.heartbeat.feature.scheduler.api.LocalCommands

/** Builds the command service of each script activation over profile-wide host services. */
@SingleIn(ProfileScope::class)
internal class HarnessScriptCommandsFactory(
    commands: LocalCommands,
    scopes: HarnessCreationScope,
    slots: HarnessCommandSlots,
    origins: HarnessCallOrigins,
    toggles: FeatureToggles,
    processes: HarnessProcessCommandHost? = null,
) {
    private val host = HarnessCommandHost(
        commands,
        scopes,
        slots,
        origins,
        processes,
    ) { toggles.get(HarnessCommandsEnabled) }

    fun create(request: HarnessActivationRequest, access: HarnessInstanceAccess): HarnessScriptCommands =
        HarnessScriptCommands(request, access, host)
}
