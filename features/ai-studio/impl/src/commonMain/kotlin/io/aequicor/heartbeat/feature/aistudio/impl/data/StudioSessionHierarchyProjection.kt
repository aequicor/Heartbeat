package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionKind
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSessionHierarchy
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSessionRole

/** Only canonical rows supply links. Human access survives broken ancestry solely for a proven full chat. */
internal fun StudioChatRecord.sessionHierarchy(): StudioSessionHierarchy {
    val verified = logicalSession
    val kind = verified?.kind ?: identity?.context?.kind.takeIf { isHumanConversation }
    val role = when {
        verified?.kind == SessionKind.Subagent -> StudioSessionRole.Subagent
        !isHumanConversation -> StudioSessionRole.Unknown
        kind == SessionKind.Session -> StudioSessionRole.Session
        kind == SessionKind.Subsession -> StudioSessionRole.Subsession
        identity?.origin == StudioIdentityOrigin.LegacyInteractiveHelper -> StudioSessionRole.LegacyInteractive
        else -> StudioSessionRole.Unknown
    }
    val links = verified.takeUnless { role == StudioSessionRole.Unknown }
    return StudioSessionHierarchy(
        role,
        rootSessionId = links?.root?.value,
        creatorSessionId = links?.creator?.value,
        ownerSessionId = links?.owner?.value,
        identityIssue = identityIssue,
    )
}
