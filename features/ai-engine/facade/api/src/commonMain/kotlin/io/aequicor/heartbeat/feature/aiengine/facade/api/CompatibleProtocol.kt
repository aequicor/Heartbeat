package io.aequicor.heartbeat.feature.aiengine.facade.api

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId

/**
 * Non-vendor providers: any server speaking a vendor wire protocol at a user-entered origin. Multi-provider
 * engines (Koog, Pi, …) declare these methods with the same [ProviderId]s, so one key source can serve every
 * engine that accepts the protocol. The user may enter a full API base URL; see [apiBase].
 */
public enum class CompatibleProtocol(public val provider: ProviderInfo, public val suggestedOrigin: EndpointOrigin) {
    /** OpenAI Chat Completions: `/v1/chat/completions`, `/v1/models` (vLLM, LM Studio, LiteLLM, DeepSeek…). */
    OpenAI(
        ProviderInfo(ProviderId("openai-compatible"), "OpenAI-совместимый"),
        EndpointOrigin("http://localhost:8000"),
    ),

    /** Anthropic Messages: `/v1/messages`, `/v1/models`. */
    Anthropic(
        ProviderInfo(ProviderId("anthropic-compatible"), "Anthropic-совместимый"),
        EndpointOrigin("http://localhost:4000"),
    ),
    ;

    /** API-key method with an editable origin; the key is sent only to origins passing [isCompatibleOriginAllowed]. */
    public val method: ConnectionMethod.ApiKey
        get() = ConnectionMethod.ApiKey(
            ConnectionMethodId(provider.id.value),
            provider,
            suggestedOrigin,
            isOriginEditable = true,
            isPathEditable = true,
        )

    /**
     * API prefix of [scope] in the vendor SDK convention: OpenAI bases include the version (`/v1` by default,
     * `/api/v1` for OpenRouter), Anthropic bases exclude it (`/anthropic`, a trailing `/v1` is dropped).
     * Endpoints are then `<prefix>/chat/completions` and `<prefix>/v1/messages` respectively.
     */
    public fun apiBase(scope: AuthScope): String = when (this) {
        OpenAI -> scope.basePath ?: "/v1"
        Anthropic -> scope.basePath?.removeSuffix("/v1").orEmpty()
    }

    /** Lookup by source scope. */
    public companion object {
        /** Protocol of [scope], or null when the provider is not compatible or the origin is not allowed. */
        public fun of(scope: AuthScope): CompatibleProtocol? =
            entries.firstOrNull { it.provider.id == scope.provider }?.takeIf { isCompatibleOriginAllowed(scope.origin) }
    }
}

/**
 * Every non-vendor engine (all families except [EngineFamily.Vendor]) must declare the [CompatibleProtocol.method]s
 * of all protocols and accept their sources, including a custom base path; vendor CLIs are bound to their vendor.
 * The rule is checked over the whole engine graph by the `di-bundle` integration tests.
 */
public val EngineFamily.supportsCompatibleProviders: Boolean get() = this != EngineFamily.Vendor

/** Whether [descriptor] declares every compatible method as required by [supportsCompatibleProviders]. */
public fun declaresCompatibleProviders(descriptor: EngineDescriptor): Boolean =
    CompatibleProtocol.entries.all { it.method in descriptor.connectionMethods }

/**
 * Origin policy of a user-entered server: HTTPS anywhere, plain HTTP only on the loopback interface, so a
 * managed key never travels unencrypted over a network.
 */
public fun isCompatibleOriginAllowed(origin: EndpointOrigin): Boolean {
    val value = origin.value
    if (value.startsWith("https://")) return true
    val authority = value.removePrefix("http://")
    val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
    return host in LOOPBACK_HOSTS
}

private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")
