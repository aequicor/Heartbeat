package io.aequicor.heartbeat.core.secrets

/** Opaque stable identifier, scoped to the injected profile. Never put sensitive data in identifiers. */
public data class SecretKey(public val value: String) {
    init {
        require(value.matches(Regex("[a-zA-Z0-9_-]{1,128}"))) { "Invalid secret identifier" }
    }
}

/** Stable consuming slot (e.g. engine / engine UUID / api-key). Labels belong in the consuming feature. */
public data class SecretUsage(public val type: String, public val id: String, public val slot: String) {
    init {
        require(listOf(type, id, slot).all { it.matches(Regex("[a-zA-Z0-9_-]{1,128}")) }) {
            "Invalid usage identifier"
        }
    }
}

/** Outcome of deletion; [InUse.usages] lets the caller show exactly which consumers block it. */
public sealed interface SecretRemoval {
    /** The value was removed. */
    public data object Removed : SecretRemoval

    /** There was no value with that key. */
    public data object Missing : SecretRemoval

    /** No data changed because these persistent references still exist. */
    public data class InUse(public val usages: List<SecretUsage>) : SecretRemoval
}

/**
 * Protected persistent storage of the active profile. Switching/signing out closes access, not data.
 * Obtain a fresh instance when reopening a profile. Calls are main-safe; backend failures propagate.
 * Values and usage references commit together. Consumers persist a slot and resolve it via [readFor],
 * rather than copying values. Already resolved values cannot be retroactively rotated.
 */
public interface SecretStore {
    /** Creates or replaces one value without changing its key or references. Input remains caller-owned. */
    public suspend fun write(key: SecretKey, value: Secret)

    /** Returns a newly owned value, or null. Caller must close it. */
    public suspend fun read(key: SecretKey): Secret?

    /** Lists identifiers only, never values. */
    public suspend fun keys(): List<SecretKey>

    /** Atomically binds/rebinds a slot, or unbinds it when [key] is null. Missing target keys are rejected. */
    public suspend fun bind(usage: SecretUsage, key: SecretKey?)

    /** Resolves a slot to the current value; caller must close it. */
    public suspend fun readFor(usage: SecretUsage): Secret?

    /** Lists persistent references to a value. */
    public suspend fun usages(key: SecretKey): List<SecretUsage>

    /** Atomically checks references and removes only an unused value. */
    public suspend fun remove(key: SecretKey): SecretRemoval
}
