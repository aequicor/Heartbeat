package io.aequicor.heartbeat.feature.harness.impl.data.delivery

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessScriptInstructionAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessOriginContext
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessSessionProofs
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessToolDispatch
import kotlinx.coroutines.withContext

/** Instruction callbacks cannot recursively send/spawn/start while the host prepares a turn. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class RuntimeHarnessInstructions(
    private val dispatch: Lazy<HarnessToolDispatch>,
    private val proofs: Lazy<HarnessSessionProofs>,
) : HarnessScriptInstructionAccess {
    override suspend fun instructions(scope: AgentToolScope): String {
        val session = scope.session ?: return ""
        proofs.value.remember(session, scope.workspace)
        return withContext(HarnessOriginContext(HarnessCallOrigin(isHookRestricted = true))) {
            dispatch.value.instructions(scope)
        }
    }
}
