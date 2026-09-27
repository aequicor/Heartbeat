package io.aequicor.heartbeat.core.secrets.impl

/**
 * Platform host configuration, primarily for isolated integration tests.
 * Keep [service] stable in production; changing it makes existing Keychain items inaccessible.
 * [directory] overrides the desktop encrypted-file/lock directory, never the encryption mechanism.
 */
public data class SecretsConfig(
    public val service: String = "io.aequicor.heartbeat.secrets.v1",
    public val directory: String? = null,
)
