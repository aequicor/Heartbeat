package io.aequicor.heartbeat.feature.harness.api.script

import kotlinx.serialization.json.JsonElement

/** A value and the revision of its entire harness namespace, including when [value] is absent. */
public data class ScriptDataSnapshot(val value: JsonElement?, val revision: Long) {
    override fun toString(): String = "ScriptDataSnapshot(***)"
}

/** Compare-and-set outcome. A conflict never modifies the stored value. */
public sealed interface ScriptDataWriteResult {
    /** Persisted snapshot; unchanged values keep their revision and do not invalidate surfaces. */
    public data class Written(val snapshot: ScriptDataSnapshot) : ScriptDataWriteResult

    /** Current snapshot; reread/reconcile the user's edit instead of silently replacing concurrent changes. */
    public data class Conflict(val current: ScriptDataSnapshot) : ScriptDataWriteResult
}

/**
 * Durable JSON shared by all approved scripts of this harness in the current profile. The host fixes the owner;
 * scripts cannot address another harness. Data survives item/source replacement, detach, disable and restart.
 * Deleting the harness deletes this namespace; durable process snapshots are preserved outside it in the profile
 * archive when durable mode is enabled or a process is already pinned. Profile wipe deletes everything.
 * Values use the profile's
 * protected store (with its explicitly separate development backend), never browser localStorage or script fields.
 *
 * Every operation requires a live, published approved activation. Calls during evaluation or after revocation
 * fail. Keys match `[a-z][a-z0-9_]{0,63}`; at most HarnessLimits.DATA_KEYS and HarnessLimits.DATA_BYTES UTF-8 bytes
 * per namespace, including metadata. Invalid keys and oversized writes throw IllegalArgumentException before
 * commit. Values, errors and snapshots must never be logged. IO failures propagate without a successful result.
 */
public interface ScriptData {
    /** Reads one key atomically; an absent namespace has revision zero. */
    public suspend fun read(key: String): ScriptDataSnapshot

    /**
     * Atomically writes [value] only if the namespace still has [expectedRevision]. Null removes a key;
     * JsonNull stores JSON null. A changed write advances the namespace revision and refreshes owner surfaces
     * only after persistence succeeds. Revisions cover all keys, preventing delete/recreate ABA conflicts.
     */
    public suspend fun write(key: String, value: JsonElement?, expectedRevision: Long): ScriptDataWriteResult

    /**
     * Atomic read/modify/write with optimistic retry. [transform] must be pure and quick: concurrent writes can
     * invoke it repeatedly, with no locks held. Returning null removes the key. Cancellation stops retries.
     */
    public suspend fun update(key: String, transform: (JsonElement?) -> JsonElement?): ScriptDataSnapshot
}
