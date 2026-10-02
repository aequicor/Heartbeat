package io.aequicor.heartbeat.feature.aiengine.facade.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/** Native context occupancy for the current model, never cumulative session billing or a text estimate. */
public data class ContextUsage(
    val usedTokens: Long,
    val capacityTokens: Long,
    val observation: Observation = Observation(),
) {
    init {
        require(usedTokens >= 0) { "Negative context usage" }
        require(capacityTokens > 0) { "Non-positive context capacity" }
    }
}

/** Optional telemetry of an active native session; null means the occupancy or capacity is unknown. */
public interface SessionContextUsage : EngineFeature {
    /** Last native observation, replaced after compaction or model changes; reading never generates text. */
    public val state: StateFlow<ContextUsage?>

    /** Typed context-usage key. */
    public companion object : EngineFeatureKey<SessionContextUsage>(
        EngineFeatureId("session.context_usage"),
        SessionContextUsage::class,
    )
}

/** One provider-defined quota bucket. Missing percentages and reset times stay unknown. */
public data class ProviderUsageWindow(
    val id: String,
    val title: String,
    val usedPercent: Double? = null,
    val resetsAt: Instant? = null,
    val windowDurationMinutes: Long? = null,
)

/** Provider-reported credits; absence of an amount is not a zero balance or unlimited allowance. */
public data class ProviderUsageCredits(
    val remaining: Double? = null,
    val total: Double? = null,
    val currency: String? = null,
    val isUnlimited: Boolean = false,
    val expiresAt: Instant? = null,
)

/** Last account observation on one credential route. Empty data means that no metrics are known. */
public data class ProviderUsageSnapshot(
    val windows: List<ProviderUsageWindow> = emptyList(),
    val credits: ProviderUsageCredits? = null,
    val planName: String? = null,
    val observation: Observation = Observation(),
)

/** Optional account telemetry bound to one runtime identity, independent of active session handles. */
public interface ReportsProviderUsage : EngineFeature {
    /** Cached data and native updates. Observing does not start a request or process. */
    public val state: StateFlow<ProviderUsageSnapshot>

    /** Requests supported native account telemetry; event-only providers return their current observation. */
    public suspend fun refresh(): ProviderUsageSnapshot

    /** Typed provider-usage key. */
    public companion object : EngineFeatureKey<ReportsProviderUsage>(
        EngineFeatureId("provider.usage"),
        ReportsProviderUsage::class,
    )
}

/** Profile-memory account observations exposed through exact engine/binding routes. */
public interface ProviderUsageCatalog {
    /**
     * Reads cached telemetry without IO; unavailable routes and unknown metrics expose an empty snapshot.
     * Collection belongs to the caller: cancelling it releases the route observation immediately.
     */
    public fun observe(engine: EngineId, binding: EngineBindingId): Flow<ProviderUsageSnapshot>

    /** Explicit route-checked refresh, deduplicated for thirty seconds per runtime identity. */
    public suspend fun refresh(engine: EngineId, binding: EngineBindingId): ProviderUsageSnapshot
}

/** Shared gate for native telemetry and the Studio context/limits UI, enabled by default. */
public val EngineUsageEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    key = "ai_studio.usage",
    description = "Индикатор заполненности контекста и лимиты провайдера",
    default = true,
)
