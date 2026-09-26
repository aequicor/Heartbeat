package io.aequicor.heartbeat.core.profilefacade

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** Stable identifier of a user profile. */
@Serializable
@JvmInline
public value class ProfileId(public val value: String)

/** An open profile: its id and its [ProfileGraph]. */
public data class ProfileSession(public val id: ProfileId, public val graph: ProfileGraph)

/**
 * Sessions of user profiles; at most one profile is active at a time.
 *
 * The active profile id is persisted through [ActiveProfileStorage], so after process death the
 * platform root calls [restore] and gets the same profile back (with a fresh graph).
 * Safe to call from a coroutine of the profile itself (e.g. "sign out" in a feature): storage is written before
 * the profile is closed. Call from the main thread — close actions of the closed scopes run on the caller's thread.
 */
public interface ProfileSessions {
    /** The active session, or `null` when signed out. */
    public val active: StateFlow<ProfileSession?>

    /** Reopens the persisted active profile after a cold start. Returns the already active session if any. */
    public suspend fun restore(): ProfileSession?

    /** Opens the profile [id]; the previously active profile (if different) is closed first. */
    public suspend fun open(id: ProfileId): ProfileSession

    /** Closes the active profile: every profile, feature and shared scope below it is closed. */
    public suspend fun close()
}

/**
 * Persistence of the active profile id. `core:profile-facade:impl` binds an in-memory default with the lowest
 * priority; a persistent implementation overrides it with `@ContributesBinding(AppScope::class, priority = 0)`
 * (any value above `Int.MIN_VALUE`, otherwise the two bindings conflict).
 */
public interface ActiveProfileStorage {
    /** Reads the persisted active profile id. */
    public suspend fun read(): ProfileId?

    /** Persists the active profile id; `null` clears it. */
    public suspend fun write(id: ProfileId?)
}
