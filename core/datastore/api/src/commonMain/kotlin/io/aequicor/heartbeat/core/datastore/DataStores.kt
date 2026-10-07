package io.aequicor.heartbeat.core.datastore

import androidx.room.RoomDatabase
import io.aequicor.heartbeat.core.profilefacade.ProfileId

/**
 * Storages of one owner: key-value stores and Room databases. The owner is chosen by the qualifier:
 *
 * ```
 * @Inject class SettingsRepositoryImpl(@ForScope(AppScope::class) stores: DataStores)      // lives forever
 * @Inject class ChatRepositoryImpl(@ForScope(ProfileScope::class) stores: DataStores)      // per profile
 * ```
 *
 * Profile storages live in the directory of the profile: they are closed when the profile closes (sign-out,
 * switch) and hold the same data when it is opened again. They are deleted only by [StorageMaintenance.wipeProfile].
 *
 * Lifetime of single records is declared by [Retention]: expired records and records of fired events are deleted
 * by the core — on open, by a timer while the storage is open, and on [fire].
 */
public interface DataStores {
    /** Whose storages these are. */
    public val owner: StorageOwner

    /**
     * Absolute directory for immutable feature-owned files, removed with this owner's storage.
     * [name] follows the same safe naming rules as a store. Resolving the path does not create it or perform IO;
     * callers create directories and read/write files on their IO dispatcher. Fails after the owner closes.
     * The directory is for application data, never an externally selected file or an export destination.
     */
    public fun filesDirectory(name: String): String

    /**
     * The key-value store [spec] of this owner. One instance per name: repeated calls return the same store;
     * a different spec with the same name fails with [IllegalStateException].
     */
    public fun keyValue(spec: KeyValueSpec): KeyValueStore

    /**
     * The Room database [spec] of this owner, configured by the core (bundled SQLite driver, IO dispatcher,
     * migrations with logs, retention of rows with [RecordRetention]). One instance per name. Closing the owner
     * cancels its Room operations; physical close follows their completion. [StorageMaintenance.awaitClosed]
     * confirms physical cleanup before external file operations. Never close the returned database manually.
     * Never build a Room database directly.
     */
    public fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T

    /**
     * Fires [event]: deletes the records bound to it and written before now. Records of storages that are not open
     * are deleted when they are opened.
     * - app owner: records of every owner — the app and all profiles;
     * - profile owner: records of this profile only.
     */
    public suspend fun fire(event: DataEvent)
}

/** Owner of storages: defines their directory and lifetime. */
public sealed interface StorageOwner {
    /** Application-wide: lives forever. */
    public data object App : StorageOwner

    /** One profile: closed with the profile, kept on disk until [StorageMaintenance.wipeProfile]. */
    public data class Profile(
        /** The profile. */
        public val id: ProfileId,
    ) : StorageOwner
}

/** Maintenance of storages beyond one owner. App-scoped. */
public interface StorageMaintenance {
    /**
     * Waits for the snapshot of owners already closing at invocation, including actual database close. Does not
     * close scopes or wait for active owners or later closures. Failures propagate; cancellation of this wait
     * does not cancel cleanup. Before deleting an entire storage root, close the app scope and join its jobs
     * first (including app-owned Preferences jobs), then await this barrier. [wipeProfile] does this for its owner.
     */
    public suspend fun awaitClosed()

    /**
     * Deletes every storage of the profile [id] (e.g. when the account is removed from the device).
     * Fails with [IllegalStateException] if the profile is active — close the session first.
     * An empty profile ID is rejected with [IllegalArgumentException] before accessing its storage directory.
     */
    public suspend fun wipeProfile(id: ProfileId)
}
