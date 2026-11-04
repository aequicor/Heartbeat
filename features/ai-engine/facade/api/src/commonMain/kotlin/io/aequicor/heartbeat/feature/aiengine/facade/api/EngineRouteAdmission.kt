package io.aequicor.heartbeat.feature.aiengine.facade.api

/**
 * Explicit admission assessment of one saved route, for a step that must know whether the route could create a
 * session before the user commits to it. Assessment runs one real restricted acquisition for the exact saved
 * binding and source revision, assesses the observed evidence against the registered connector's researched
 * policy, and always closes the candidate. It never promotes, reuses or starts an ordinary runtime, never reads
 * credentials, user/project configuration, tools or models, and never writes launch settings or model choices.
 * Its result is evidence only for the route observed at that moment: a later launch must still bind admission to
 * its actual artifact.
 */
public interface EngineRouteAdmission {
    /**
     * Acquires one restricted candidate for [binding]'s saved route and returns its assessment. The candidate is
     * closed whatever the outcome, including cancellation or a failed probe.
     *
     * [RouteAdmission.Unavailable] means the connector publishes no researched policy or no restricted candidate:
     * nothing was admitted. It is never an allow. Errors of the route or the probe propagate as [EngineException].
     */
    public suspend fun assess(engine: EngineId, binding: EngineBindingId): RouteAdmission =
        throw EngineException(EngineFailure.Access(AccessFailureReason.OperationNotAllowed))
}

/** Outcome of [EngineRouteAdmission]; only an [Assessed] decision with a baseline allow passes. */
public sealed interface RouteAdmission {
    /** No researched policy or no restricted candidate exists; this result never permits a launch. */
    public data object Unavailable : RouteAdmission

    /** The exact observed candidate was assessed; [decision] may still be Unknown or Incompatible. */
    public data class Assessed(val decision: EngineCompatibilityDecision) : RouteAdmission
}

/** Admission contract of a host without the facade implementation; every assessment stays unavailable. */
public class UnavailableEngineRouteAdmission : EngineRouteAdmission {
    override suspend fun assess(engine: EngineId, binding: EngineBindingId): RouteAdmission = RouteAdmission.Unavailable
}
