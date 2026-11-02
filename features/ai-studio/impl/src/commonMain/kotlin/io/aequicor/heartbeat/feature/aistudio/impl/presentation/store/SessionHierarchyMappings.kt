package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSessionHierarchy
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSessionRole
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SessionHierarchyUi

/** Wire strings stay local to the presentation boundary; execution uses the canonical host role. */
internal fun StudioSessionHierarchy.toUi(): SessionHierarchyUi = SessionHierarchyUi(
    kind = when (role) {
        StudioSessionRole.Session -> "session"
        StudioSessionRole.Subsession -> "subsession"
        StudioSessionRole.Subagent -> "subagent"
        StudioSessionRole.LegacyInteractive -> "legacy_interactive"
        StudioSessionRole.Unknown -> "unknown"
    },
    rootSessionId = rootSessionId,
    creatorSessionId = creatorSessionId,
    ownerSessionId = ownerSessionId,
    identityIssue = identityIssue,
)
