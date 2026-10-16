package io.aequicor.heartbeat.feature.harness.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolSwitch
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/** Library availability in project pickers; activation always requires explicit chat connections. */
@Serializable
public sealed interface HarnessScope {
    /** Shared library entry, used when created without a project; it never activates chats automatically. */
    @Serializable
    @SerialName("attached")
    public data object Attached : HarnessScope

    /** Legacy value: no longer activates chats automatically. */
    @Serializable
    @SerialName("profile")
    public data object Profile : HarnessScope

    /** Available in the listed source projects, including their worktrees; does not activate chats automatically. */
    @Serializable
    @SerialName("projects")
    public data class Projects(val projects: Set<WorkspaceRef>) : HarnessScope {
        init {
            require(projects.isNotEmpty())
        }
    }
}

/** Stored tool switches by engine catalog name. Hosted tools can only be disabled. */
@Serializable
public data class ToolPolicySpec(
    val hostedOff: Set<String> = emptySet(),
    val native: Map<String, Map<String, ToolSwitch>> = emptyMap(),
) {
    init {
        require(hostedOff.none(String::isBlank))
        require(native.keys.none(String::isBlank))
        require(native.values.all { tools -> tools.keys.none(String::isBlank) })
    }
}

/** Legacy persisted setting retained for compatibility; agent edits always use automatic safety review. */
@Serializable
public enum class HarnessApproval {
    @SerialName("ask")
    Ask,

    @SerialName("by_trust")
    ByTrust,

    @SerialName("accept_all")
    AcceptAll,
}

/**
 * A committed library revision. Attachments are stored separately and do not change [revision].
 *
 * [isDefaultForNewSessions] is the profile preference to preselect this harness in new chats where it is
 * available. The host still attaches it explicitly; changing the preference never changes existing connections.
 */
@Serializable
public data class Harness(
    val id: HarnessId,
    val name: HarnessName,
    val title: String,
    val description: String,
    val scope: HarnessScope,
    val isEnabled: Boolean,
    val items: List<HarnessItem>,
    val tools: ToolPolicySpec,
    val author: SessionRef?,
    val revision: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val isDefaultForNewSessions: Boolean = false,
) {
    init {
        require(revision >= 0)
        require(updatedAt >= createdAt)
        require(items.size <= HarnessLimits.ITEMS)
        require(items.map { it.id }.distinct().size == items.size)
        require(items.map { it.name }.distinct().size == items.size)
    }
    override fun toString(): String = "Harness(id=$id, name=$name, revision=$revision, ***)"
}

/** Encoded UTF-8 size, including all persisted fields. No source or content is logged. */
public fun Harness.storageBytes(): Int = Json.encodeToString(this).encodeToByteArray().size

/** Checks library-wide identity, slug and storage quotas before accepting a mutation or restored snapshot. */
public fun isHarnessLibraryWithinLimits(harnesses: List<Harness>): Boolean {
    if (harnesses.size > HarnessLimits.HARNESSES) return false
    if (harnesses.map { it.id }.distinct().size != harnesses.size) return false
    if (harnesses.map { it.name }.distinct().size != harnesses.size) return false
    val sizes = harnesses.map { it.storageBytes() }
    return sizes.all { it <= HarnessLimits.BYTES_PER_HARNESS } && sizes.sumOf(
        Int::toLong,
    ) <= HarnessLimits.BYTES_PER_PROFILE
}
