package io.aequicor.heartbeat.feature.harness.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef

/** The harness library and its tools; authorization uses the shared profile security mode. */
public val HarnessEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    key = "harness.enabled",
    description = "Харнессы: инструкции, инструменты и workflow агента",
    default = true,
)

/**
 * Pure activation from explicit session attachments and verified helper affiliation.
 * Stored legacy scopes do not activate a harness; attachments never bypass isEnabled.
 * A harness both attached and helper-affiliated is selected once. Overflow is deterministic: immutable name order,
 * at most eight.
 * The cap bounds contributions only (content, instructions, script tools, events, native On switches);
 * restrictions use the uncapped [applicableHarnesses].
 * Suspension immediately removes all effective contributions while the committed library stays intact.
 */
public fun activeHarnesses(
    harnesses: List<Harness>,
    attachments: Map<SessionRef, Set<HarnessId>>,
    session: SessionRef,
    helperOf: HarnessId? = null,
    isSuspended: Boolean = false,
): List<Harness> = applicableHarnesses(harnesses, attachments, session, helperOf, isSuspended)
    .take(HarnessLimits.ACTIVE_PER_SESSION)

/**
 * Every enabled harness connected to the session, in name order and without the cap of [activeHarnesses].
 * Restrictions (Off and hosted_off switches, Deny/Ask tool hooks) come from this whole list, so that harnesses
 * sorting earlier by name can never push a restricting harness out of a session.
 */
public fun applicableHarnesses(
    harnesses: List<Harness>,
    attachments: Map<SessionRef, Set<HarnessId>>,
    session: SessionRef,
    helperOf: HarnessId? = null,
    isSuspended: Boolean = false,
): List<Harness> {
    if (isSuspended) return emptyList()
    val attached = attachments[session].orEmpty()
    return harnesses.asSequence().filter { harness ->
        harness.isEnabled && (harness.id in attached || harness.id == helperOf)
    }.distinctBy { it.id }.sortedBy { it.name.value }.toList()
}
