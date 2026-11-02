package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlin.uuid.Uuid

/** Exact one-time decisions, with the legacy session grant offered only for native tools. */
internal fun koogPermissionRequest(
    turn: TurnId,
    title: String,
    description: String?,
    isSessionOptionEnabled: Boolean = false,
    operationBinding: String? = null,
): PermissionRequest = PermissionRequest(
    PermissionRequestId(Uuid.random().toString()),
    turn,
    title,
    buildList {
        add(PermissionOption(AllowOnce, "Разрешить", isOneTime = true))
        if (isSessionOptionEnabled) add(PermissionOption(AllowForSession, "Разрешить до конца сессии"))
        add(PermissionOption(Deny, "Запретить", isOneTime = true))
    },
    description = description,
    operationBinding = operationBinding,
)

internal val AllowOnce = PermissionOptionId("allow")
internal val AllowForSession = PermissionOptionId("allow_session")
internal val Deny = PermissionOptionId("deny")
