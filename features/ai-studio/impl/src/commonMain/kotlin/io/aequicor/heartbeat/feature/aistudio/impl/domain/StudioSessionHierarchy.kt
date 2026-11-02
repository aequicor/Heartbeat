package io.aequicor.heartbeat.feature.aistudio.impl.domain

/** Logical role is independent of historic scheduling metadata and native execution ancestry. */
enum class StudioSessionRole { Session, Subsession, Subagent, LegacyInteractive, Unknown }

/** Verified conversation presentation authority; missing links never imply a new controller. */
data class StudioSessionHierarchy(
    val role: StudioSessionRole,
    val rootSessionId: String? = null,
    val creatorSessionId: String? = null,
    val ownerSessionId: String? = null,
    val identityIssue: String? = null,
)
