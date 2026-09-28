package io.aequicor.heartbeat.feature.aiengine.facade.api

import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthLocationId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthOwnerId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceDraft
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId

/** Stable method identity within one engine descriptor. Never contains credentials or account names. */
public data class ConnectionMethodId(val value: String) {
    init {
        require(value.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))) { "Invalid ConnectionMethodId" }
    }
}

/**
 * Presentation metadata of a model provider. [id] and the method origin are the security-relevant parts;
 * [credentialsPage] is a public https page opened only on explicit user action and never receives credentials.
 */
public data class ProviderInfo(val id: ProviderId, val title: String, val credentialsPage: String? = null) {
    init {
        require(title.isNotBlank()) { "Empty provider title" }
        require(credentialsPage == null || credentialsPage.startsWith("https://")) { "Expected an https page" }
    }
}

/**
 * A way to create a credential source for an engine, declared statically in [EngineDescriptor.connectionMethods].
 * Reading a method performs no IO. It only describes what the connection UI may offer: the adapter factory's
 * `accepts` check remains authoritative and is applied again when the binding is created.
 */
public sealed interface ConnectionMethod {
    /** Identity within the engine. */
    public val id: ConnectionMethodId

    /** Provider the created source is scoped to. */
    public val provider: ProviderInfo

    /** Default endpoint origin. */
    public val origin: EndpointOrigin

    /** Whether the user may enter another origin (self-hosted server, proxy); fixed origins prevent key forwarding. */
    public val isOriginEditable: Boolean

    /** A key entered by the user and kept in the profile vault. */
    public data class ApiKey(
        override val id: ConnectionMethodId,
        override val provider: ProviderInfo,
        override val origin: EndpointOrigin,
        override val isOriginEditable: Boolean = false,
    ) : ConnectionMethod

    /** An existing login of the engine's own CLI; Heartbeat never reads, imports or refreshes its tokens. */
    public data class CliLogin(
        override val id: ConnectionMethodId,
        override val provider: ProviderInfo,
        override val origin: EndpointOrigin,
        /** Must equal the engine registration's auth owner. */
        val owner: AuthOwnerId,
        /** CLI profile the source refers to. */
        val location: AuthLocationId,
    ) : ConnectionMethod {
        override val isOriginEditable: Boolean get() = false
    }

    /** An endpoint without credentials, typically a local model server. */
    public data class NoAuth(
        override val id: ConnectionMethodId,
        override val provider: ProviderInfo,
        override val origin: EndpointOrigin,
        override val isOriginEditable: Boolean = true,
    ) : ConnectionMethod
}

/** Scope of a source created by this method against [origin]; a fixed origin cannot be replaced. */
public fun ConnectionMethod.scopeFor(origin: EndpointOrigin = this.origin): AuthScope {
    require(isOriginEditable || origin == this.origin) { "The method has a fixed origin" }
    return AuthScope(provider.id, origin)
}

/**
 * Creates a source for [method] in this registry. [key] is required exactly for [ConnectionMethod.ApiKey];
 * the caller keeps owning it. The origin policy is validated before the registry is touched.
 */
public suspend fun AuthSources.create(
    method: ConnectionMethod,
    label: String,
    origin: EndpointOrigin = method.origin,
    key: Secret? = null,
): AuthSource {
    val scope = method.scopeFor(origin)
    require((method is ConnectionMethod.ApiKey) == (key != null)) { "A key belongs exactly to the API key method" }
    return when (method) {
        is ConnectionMethod.ApiKey -> addManagedKey(label, scope, requireNotNull(key))

        is ConnectionMethod.CliLogin ->
            register(AuthSourceDraft.CliLogin(label, scope, method.owner, method.location))

        is ConnectionMethod.NoAuth -> register(AuthSourceDraft.NoAuth(label, scope))
    }
}
