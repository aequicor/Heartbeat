package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioHarnessAttach
import io.aequicor.heartbeat.feature.aistudio.impl.domain.ToolChoices
import io.aequicor.heartbeat.feature.harness.api.HarnessTools

/** Durable carried choices reach native declarations on every retried create, before attachment is possible. */
internal fun StudioChatRecord.withCarriedHarnesses(chosen: ToolChoices): ToolChoices = pendingHarnesses?.let {
    chosen + (HarnessTools.GROUP to (chosen[HarnessTools.GROUP].orEmpty() + it))
} ?: chosen

/** The durable receipt stays pending after any failed or interrupted attachment, including across app restarts. */
internal suspend fun StudioChatRecord.restoreCarriedHarnesses(
    session: SessionRef,
    harnesses: StudioHarnessAttach,
    commit: suspend (Set<String>) -> Unit,
) {
    val pending = pendingHarnesses ?: return
    check(ref == session) { "Carried harnesses belong to another native session" }
    if (harnesses.attached(pendingHarnessSource ?: session) == null) return
    harnesses.restore(pending, session)
    commit(pending)
}

/** Marks the created context only when its preselection actually had enabled carried harness choices. */
internal fun StudioChatRecord.withNativeReference(
    target: EngineTarget,
    session: SessionRef,
    hasCarriedHarnesses: Boolean,
): StudioChatRecord = copy(
    ref = session,
    target = target,
    pendingHarnessContext = if (hasCarriedHarnesses) session else pendingHarnessContext,
)

/** Globally disabled choices remain deferred and never trigger a fresh native context on every retry. */
internal suspend fun StudioChatRecord.isHarnessCarryEnabled(harnesses: StudioHarnessAttach): Boolean =
    pendingHarnesses != null && harnesses.attached(checkNotNull(pendingHarnessSource ?: ref)) != null

/** A deferred carry needs new frozen declarations only until an enabled create has received its nonempty choices. */
internal suspend fun StudioChatRecord.needsHarnessDeclarations(
    session: SessionRef,
    harnesses: StudioHarnessAttach,
): Boolean {
    check(ref == session) { "Carried harnesses belong to another native session" }
    return !pendingHarnesses.isNullOrEmpty() && pendingHarnessContext != session && isHarnessCarryEnabled(harnesses)
}
