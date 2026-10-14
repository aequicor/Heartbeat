package io.aequicor.heartbeat.feature.aiengine.facade.api

/** Hosted declarations that the native session cannot remove or replace between turns. */
public interface FrozenHostedTools : EngineFeature {
    /**
     * Names actually declared to the native session, including tools the host no longer serves. An empty set
     * means no hosted declaration was retained. Engines rebuilding declarations each turn omit this capability.
     */
    public val names: Set<String>

    /** Read-only native declaration key. */
    public companion object : EngineFeatureKey<FrozenHostedTools>(
        EngineFeatureId("session.frozen_hosted_tools"),
        FrozenHostedTools::class,
    )
}
