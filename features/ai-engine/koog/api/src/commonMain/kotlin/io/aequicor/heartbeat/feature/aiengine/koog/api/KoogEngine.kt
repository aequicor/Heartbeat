package io.aequicor.heartbeat.feature.aiengine.koog.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthOwnerId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import kotlinx.serialization.Serializable

/** Stable registration identity shared by Koog providers. */
public val KoogEngineId: EngineId = EngineId("koog")

/** Exclusive owner namespace; CLI credentials are never consumed by this adapter. */
public val KoogAuthOwner: AuthOwnerId = AuthOwnerId("koog")

/** Experimental text generation, disabled until explicitly enabled. */
public val KoogEngineEnabled: FeatureToggle.Flag = FeatureToggle.Flag("ai.koog", "Движок Koog")

/**
 * Allows reading the public models.dev catalog to learn which OpenAI and Alibaba models accept reasoning effort;
 * off, unknown models fall back to a guess by model family.
 */
public val KoogReasoningCatalogEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "ai.koog.reasoning_catalog",
    "Koog: каталог models.dev для уровней effort",
    default = false,
)

/** Supported routes. Fixed origins prevent forwarding managed credentials to an arbitrary server. */
@Serializable
public enum class KoogProvider(public val id: ProviderId, public val origin: EndpointOrigin) {
    OpenAI(ProviderId("openai"), EndpointOrigin("https://api.openai.com")),
    Anthropic(ProviderId("anthropic"), EndpointOrigin("https://api.anthropic.com")),

    /** Alibaba Token Plan uses its own credentials and the `/compatible-mode/v1` API prefix. */
    AlibabaQwen(
        ProviderId("alibaba-qwen-token-plan"),
        EndpointOrigin("https://token-plan.ap-southeast-1.maas.aliyuncs.com"),
    ),

    Ollama(ProviderId("ollama"), EndpointOrigin("http://localhost:11434")),
}

/**
 * Non-secret profile configuration. Managed keys refer to a SecretStore key with the same opaque id.
 * Cloud providers accept managed keys only; local Ollama accepts NoAuth only. External keys, helpers
 * and CLI logins are deliberately unsupported. Updating source metadata must advance its known revision.
 */
@Serializable
public data class KoogConnection(val binding: EngineBinding, val source: AuthSource) {
    init {
        require(binding.engine == KoogEngineId && binding.authSource == source.info.id)
    }
}

/**
 * Profile-persistent connection metadata, used until the common authenticator supplies a route repository.
 * Callers own key provisioning and source revisions. No method reads environment variables or CLI logins.
 */
public interface KoogConnections {
    /** Snapshot of configured routes. */
    public suspend fun list(): List<KoogConnection>

    /**
     * Saves a route and atomically updates shared source metadata; changed metadata requires a new revision.
     *
     * @throws IllegalArgumentException when [koogProvider] rejects the source or its metadata changed under the
     *   same revision.
     * @throws IllegalStateException when the stored routes are unreadable; they are left untouched.
     */
    public suspend fun put(connection: KoogConnection)

    /**
     * Removes a binding without deleting credentials. New turns on its existing handles are rejected.
     *
     * @throws IllegalStateException when the stored routes are unreadable; they are left untouched.
     */
    public suspend fun remove(binding: EngineBindingId)
}

/** Pure compatibility check; no credential resolution or network access. */
public fun koogProvider(source: AuthSource): KoogProvider? = KoogProvider.entries.firstOrNull { provider ->
    source.scope.provider == provider.id && source.scope.origin == provider.origin &&
        when (provider) {
            KoogProvider.OpenAI, KoogProvider.Anthropic, KoogProvider.AlibabaQwen -> source is AuthSource.ManagedKey
            KoogProvider.Ollama -> source is AuthSource.NoAuth
        }
}
