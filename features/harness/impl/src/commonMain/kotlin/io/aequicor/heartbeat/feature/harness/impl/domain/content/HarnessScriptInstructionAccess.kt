package io.aequicor.heartbeat.feature.harness.impl.domain.content

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope

/** Bounded instructions from admitted script generations; neither callbacks nor their content are logged. */
internal fun interface HarnessScriptInstructionAccess {
    suspend fun instructions(scope: AgentToolScope): String
}
