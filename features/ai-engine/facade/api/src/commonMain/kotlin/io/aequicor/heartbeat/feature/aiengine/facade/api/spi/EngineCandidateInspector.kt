package io.aequicor.heartbeat.feature.aiengine.facade.api.spi

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCandidateInspection

/**
 * Optional, restricted inspection of exactly the selected candidate. No user credentials, home, configuration,
 * project, login, model requests or fallback are allowed. A process uses a protected artifact and an isolated
 * environment and is reaped on every exit and cancellation. Null means these restrictions cannot be guaranteed.
 * The result does not authorize a later launch or transfer any process.
 */
public interface EngineCandidateInspector {
    /** Inspects the explicit, sanitized launch selection supplied by the facade. */
    public suspend fun inspectCandidate(launch: LaunchContext): EngineCandidateInspection?
}
