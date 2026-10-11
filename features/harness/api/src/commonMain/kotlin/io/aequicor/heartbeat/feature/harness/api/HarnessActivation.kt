package io.aequicor.heartbeat.feature.harness.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef

/** The whole feature is opt-in. Native enabling additionally uses facade's HarnessNativeTools toggle. */
public val HarnessEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    key = "harness.enabled",
    description = "Харнессы: инструкции, инструменты и workflow агента",
)

/**
 * Pure activation using the host-resolved source project and verified helper affiliation.
 * Attachments and a helper's originating harness extend permanent scope; they never bypass isEnabled.
 * Overlapping scopes select a harness once. Overflow is deterministic: immutable name order, at most eight.
 * The cap bounds contributions only (content, instructions, script tools, events, native On switches);
 * restrictions use the uncapped [applicableHarnesses].
 * Suspension immediately removes all effective contributions while the committed library stays intact.
 */
public fun activeHarnesses(
    harnesses: List<Harness>,
    attachments: Map<SessionRef, Set<HarnessId>>,
    session: SessionRef,
    sourceProject: WorkspaceRef?,
    helperOf: HarnessId? = null,
    isSuspended: Boolean = false,
): List<Harness> = applicableHarnesses(harnesses, attachments, session, sourceProject, helperOf, isSuspended)
    .take(HarnessLimits.ACTIVE_PER_SESSION)

/**
 * Every enabled harness whose scope covers the session, in name order and without the cap of [activeHarnesses].
 * Restrictions (Off and hosted_off switches, Deny/Ask tool hooks) come from this whole list, so that harnesses
 * sorting earlier by name can never push a restricting harness out of a session.
 */
public fun applicableHarnesses(
    harnesses: List<Harness>,
    attachments: Map<SessionRef, Set<HarnessId>>,
    session: SessionRef,
    sourceProject: WorkspaceRef?,
    helperOf: HarnessId? = null,
    isSuspended: Boolean = false,
): List<Harness> {
    if (isSuspended) return emptyList()
    val attached = attachments[session].orEmpty()
    return harnesses.asSequence().filter { harness ->
        harness.isEnabled && (
            harness.id in attached || harness.id == helperOf || when (val scope = harness.scope) {
                HarnessScope.Attached -> false
                HarnessScope.Profile -> true
                is HarnessScope.Projects -> sourceProject in scope.projects
            }
        )
    }.distinctBy { it.id }.sortedBy { it.name.value }.toList()
}
