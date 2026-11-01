package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlin.uuid.Uuid

/** Hosted approvals affect one exact invocation and cannot grant future native permissions. */
internal fun codexHostedPermission(turn: TurnId, approval: AgentToolApproval): PermissionRequest = PermissionRequest(
    PermissionRequestId(Uuid.random().toString()),
    turn,
    approval.title,
    listOf(
        PermissionOption(CodexHostedAllow, "Разрешить", isOneTime = true),
        PermissionOption(PermissionOptionId("hosted.deny"), "Запретить", isOneTime = true),
    ),
    description = approval.description,
)

internal val CodexHostedAllow: PermissionOptionId = PermissionOptionId("hosted.allow")
