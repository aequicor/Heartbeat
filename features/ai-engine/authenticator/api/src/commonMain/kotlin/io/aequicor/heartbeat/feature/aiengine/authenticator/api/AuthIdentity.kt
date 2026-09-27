package io.aequicor.heartbeat.feature.aiengine.authenticator.api

import kotlinx.serialization.Serializable

/** Profile-scoped source identifier; never an account name. */
@Serializable
public data class AuthSourceId(val value: String) {
    init {
        require(value.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))) { "Invalid AuthSourceId" }
    }
}

/** Exclusive owner namespace assigned by registration, independent of facade types. */
@Serializable
public data class AuthOwnerId(val value: String) {
    init {
        require(value.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))) { "Invalid AuthOwnerId" }
    }
}

/** Opaque reference to an external configuration, CLI profile or approved helper. */
@Serializable
public data class AuthLocationId(val value: String) {
    init {
        require(value.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))) { "Invalid AuthLocationId" }
    }
}

/** Opaque managed-vault reference; never a credential value. */
@Serializable
public data class AuthSecretId(val value: String) {
    init {
        require(value.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))) { "Invalid AuthSecretId" }
    }
}

/** Provider namespace, independent of models and engine implementations. */
@Serializable
public data class ProviderId(val value: String) {
    init {
        require(value.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))) { "Invalid ProviderId" }
    }
}

/** Engine-defined non-sensitive fingerprint of significant authentication context. */
@Serializable
public data class AuthContextKey(val value: String) {
    init {
        require(value.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))) { "Invalid AuthContextKey" }
    }
}

/** Observable source revision. Unknown never proves that credentials are unchanged. */
@Serializable
public sealed interface AuthRevision {
    /** No reliable revision can be observed without reading foreign credentials. */
    @Serializable
    public data object Unknown : AuthRevision

    /** An opaque vault version or owner-provided fingerprint, never token material or a raw account id. */
    @Serializable
    public data class Known(val fingerprint: String) : AuthRevision {
        init {
            require(fingerprint.isNotBlank())
        }
        override fun toString(): String = "AuthRevision.Known(***)"
    }
}

/**
 * Canonical HTTP(S) origin: lower-case scheme/host, no userinfo, path, query, fragment or default port.
 * Adapters canonicalize URLs before construction and must never forward credentials across origins.
 */
@Serializable
public data class EndpointOrigin(val value: String) {
    init {
        require(value.matches(Regex("https?://(?:[a-z0-9.-]+|\\[[a-f0-9:]+\\])(?::[0-9]+)?"))) {
            "Expected a canonical HTTP(S) origin"
        }
        val authority = value.substringAfter("://")
        val port = authority.substringAfterLast(':', "").takeIf { authority.last() != ']' && ':' in authority }
        require(port == null || (!port.startsWith("0") && port.toIntOrNull() in 1..MAX_PORT)) { "Invalid origin port" }
        require(!(value.startsWith("https:") && port == "443") && !(value.startsWith("http:") && port == "80")) {
            "Default ports must be omitted"
        }
    }
}

private const val MAX_PORT = 65535

/** Identifier of an authenticator contributed by an engine or the shared authenticator module. */
@Serializable
public data class AuthenticatorId(val value: String) {
    init {
        require(value.matches(Regex("[A-Za-z0-9_.:-]{1,128}")))
    }
}
