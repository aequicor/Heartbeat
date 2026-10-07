package io.aequicor.heartbeat.feature.harness.impl.data.services

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceTarget
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptSessions
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptSessionsFactory
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSessionReader
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSessionSender
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSessionSpawner

@ContributesBinding(ProfileScope::class)
@Inject
internal class StagedHarnessSessionsFactory(
    private val origins: HarnessCallOrigins,
    private val reader: HarnessSessionReader,
    private val sender: HarnessSessionSender,
    private val spawner: HarnessSessionSpawner,
) : HarnessScriptSessionsFactory {
    override fun create(request: HarnessActivationRequest, access: HarnessInstanceAccess): HarnessScriptSessions =
        HarnessScriptSessions(HarnessInstanceTarget(request, access), origins, reader, sender, spawner)
}
