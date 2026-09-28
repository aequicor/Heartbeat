package io.aequicor.heartbeat.core.secrets.impl

/**
 * Platform host configuration, primarily for isolated integration tests.
 * Keep [service] stable in production; changing it makes existing Keychain items inaccessible.
 * [directory] overrides the desktop encrypted-file/lock directory.
 * [isDevelopment] opts desktop development entry points into an isolated local-file backend without OS prompts.
 * Production entry points leave it false; no environment variable or saved preference changes it.
 */
public data class SecretsConfig(
    public val service: String = "io.aequicor.heartbeat.secrets.v1",
    public val directory: String? = null,
    public val isDevelopment: Boolean = false,
)
