package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

/** Binds hosted execution to the accepted native turn and its coroutine lifetime. */
internal suspend fun koogHostedContext(
    session: SessionRef,
    workspace: WorkspaceRef?,
    turn: Turn,
    trust: TrustLevel,
    approve: suspend (AgentToolApproval) -> Boolean,
): AgentToolContext = AgentToolContext(
    session,
    workspace,
    turn.id,
    turn.request,
    trust,
    AgentToolPermissions(approve),
    lifetime = currentCoroutineContext()[Job],
)
