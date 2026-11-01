package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionKind

/** Only verified, current-version metadata participates in host ownership checks. */
internal val StudioChatRecord.logicalSession: LogicalSessionContext?
    get() = identity?.context?.takeIf { identityIssue == null && it.id.value == id }

private data class StudioIdentityResolution(val identity: StudioSessionIdentity, val issue: String? = null) {
    val context: LogicalSessionContext? get() = identity.context.takeIf { issue == null }
}

/** A scheduling marker and a logical role are different: a full subsession can retain old helper receipts. */
internal val StudioChatRecord.isSubagent: Boolean get() = logicalSession?.kind == SessionKind.Subagent

/**
 * Idempotent, pure migration report and compatible reader. Legacy helper hosts admitted explicit human sends
 * (StudioHelperAdmission), so a proven parent link preserves that public contract as a full subsession.
 * Message roles, titles and the presence of native sessions do not decide the role. Parentless legacy helpers
 * and broken ancestry remain reachable unresolved records; they do not acquire a new owner or root authority.
 * Native work, settings and receipts are never modified. The repository persists this result under its mutex.
 */
internal fun resolvedStudioIdentities(records: List<StudioChatRecord>): List<StudioChatRecord> =
    StudioIdentityResolver(records).resolve()

private class StudioIdentityResolver(private val records: List<StudioChatRecord>) {
    private val groups = records.groupBy { it.id }
    private val resolved = mutableMapOf<String, StudioIdentityResolution>()
    private val visiting = mutableSetOf<String>()

    fun resolve(): List<StudioChatRecord> = records.map {
        val resolution = resolve(it)
        it.copy(identity = resolution.identity, identityIssue = resolution.issue)
    }

    private fun resolve(record: StudioChatRecord): StudioIdentityResolution = resolved[record.id] ?: when {
        groups[record.id]?.size != 1 -> unresolved("DuplicateIdentity", record.identity)

        !visiting.add(record.id) -> unresolved("CyclicAncestry", record.identity)

        else -> (record.identity?.let { verify(record, it) } ?: legacy(record)).also {
            visiting.remove(record.id)
            resolved[record.id] = it
        }
    }

    private fun verify(record: StudioChatRecord, existing: StudioSessionIdentity): StudioIdentityResolution {
        if (existing.version != 1) return unresolved("UnsupportedIdentityVersion", existing)
        if (existing.origin == null) return unresolved("UnknownIdentityOrigin", existing)
        if (existing.origin == StudioIdentityOrigin.Unresolved) {
            return unresolved(record.identityIssue ?: "UnresolvedIdentity", existing)
        }
        if (existing.origin == StudioIdentityOrigin.InternalExecution && existing.context == null) {
            return StudioIdentityResolution(existing)
        }
        if (existing.origin == StudioIdentityOrigin.LegacyInteractiveHelper && !existing.hasContextPayload) {
            return StudioIdentityResolution(existing, record.identityIssue ?: "UnresolvedCreator")
        }
        val context = existing.context ?: return unresolved("InvalidIdentityContext", existing)
        val issue = when {
            context.id.value != record.id -> "IdentityMismatch"
            context.kind == SessionKind.Session -> null
            else -> controllerIssue(record, context)
        }
        return StudioIdentityResolution(existing, issue)
    }

    private fun controllerIssue(record: StudioChatRecord, context: LogicalSessionContext): String? {
        val controller = if (context.kind == SessionKind.Subagent) context.owner else context.creator
        val parent = groups[controller?.value]?.singleOrNull() ?: return "UnresolvedController"
        val inherited = resolve(parent).context ?: return "UnresolvedController"
        return when {
            parent.projectId != record.projectId -> "ProjectMismatch"
            inherited.kind == SessionKind.Subagent || inherited.root != context.root -> "InvalidController"
            else -> null
        }
    }

    private fun legacy(record: StudioChatRecord): StudioIdentityResolution {
        val marker = record.helper ?: return StudioIdentityResolution(
            StudioSessionIdentity(
                StudioIdentityOrigin.UserConversation,
                LogicalSessionContext(LogicalSessionId(record.id), SessionKind.Session),
            ),
        )
        val parentId = marker.parentChatId ?: return unresolvedLegacy("LegacyParentlessExecution")
        val parent = groups[parentId]?.singleOrNull() ?: return unresolvedLegacy("MissingCreator")
        val context = resolve(parent).context
        return when {
            parent.projectId != record.projectId -> unresolvedLegacy("ProjectMismatch")

            marker.parentSession != null && !parent.owns(
                marker.parentSession,
            ) -> unresolvedLegacy("CreatorReferenceMismatch")

            context == null || context.kind == SessionKind.Subagent -> unresolvedLegacy("UnresolvedCreator")

            else -> StudioIdentityResolution(
                StudioSessionIdentity(
                    StudioIdentityOrigin.LegacyInteractiveHelper,
                    context.subsession(LogicalSessionId(record.id)),
                ),
            )
        }
    }
}

private fun unresolvedLegacy(issue: String) = unresolved(
    issue,
    StudioSessionIdentity(StudioIdentityOrigin.LegacyInteractiveHelper),
)

private fun unresolved(issue: String, original: StudioSessionIdentity? = null) = StudioIdentityResolution(
    original ?: StudioSessionIdentity(StudioIdentityOrigin.Unresolved),
    issue,
)
