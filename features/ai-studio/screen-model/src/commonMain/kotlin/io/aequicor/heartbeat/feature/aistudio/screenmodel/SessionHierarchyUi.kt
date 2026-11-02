package io.aequicor.heartbeat.feature.aistudio.screenmodel

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Known presentation roles; unrecognized wire values retain readable history without control privileges. */
enum class SessionRoleUi { Session, Subsession, Subagent, LegacyInteractive, Unknown }

/**
 * Versioned wire envelope. Raw [kind] keeps future roles deserializable; only [role] decides current behavior.
 * A null envelope on SessionUi means an older interactive DTO, never an explicit current Unknown.
 */
@Serializable
@SerialName("SessionHierarchyUi")
@Immutable
data class SessionHierarchyUi(
    val version: Int = 1,
    val kind: String = "unknown",
    val rootSessionId: String? = null,
    val creatorSessionId: String? = null,
    val ownerSessionId: String? = null,
    val identityIssue: String? = null,
) {
    val role: SessionRoleUi get() = if (version != 1) {
        SessionRoleUi.Unknown
    } else {
        when (kind) {
            "session" -> SessionRoleUi.Session
            "subsession" -> SessionRoleUi.Subsession
            "subagent" -> SessionRoleUi.Subagent
            "legacy_interactive" -> SessionRoleUi.LegacyInteractive
            else -> SessionRoleUi.Unknown
        }
    }

    val isHumanInteractive: Boolean get() = role in setOf(
        SessionRoleUi.Session,
        SessionRoleUi.Subsession,
        SessionRoleUi.LegacyInteractive,
    )
}
