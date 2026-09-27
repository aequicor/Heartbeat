package io.aequicor.heartbeat.core.datastore.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import okio.ByteString.Companion.encodeUtf8
import okio.Path
import okio.Path.Companion.toPath

/**
 * Files of the storages:
 * ```
 * <root>/storage/app/{kv/<name>.preferences_pb, db/<name>.db, events.json}
 * <root>/storage/profiles/<hex(profile id)>/{kv/…, db/…, events.json}
 * ```
 * The profile id must be nonempty and is hex-encoded into a safe directory name.
 */
@SingleIn(AppScope::class)
@Inject
internal class StorageLayout(private val root: StorageRoot) {

    private val base: Path by lazy { root.path().toPath() / STORAGE_DIR }

    fun ownerDir(owner: StorageOwner): Path = when (owner) {
        StorageOwner.App -> base / APP_DIR
        is StorageOwner.Profile -> profileDir(owner.id)
    }

    fun profileDir(id: ProfileId): Path {
        require(id.value.isNotEmpty()) { "profile id must not be empty" }
        return base / PROFILES_DIR / id.value.encodeUtf8().hex()
    }

    fun keyValueFile(owner: StorageOwner, name: String): Path = ownerDir(owner) / KV_DIR / "$name.preferences_pb"

    fun databaseFile(owner: StorageOwner, name: String): Path = ownerDir(owner) / DB_DIR / "$name.db"

    fun journalFile(owner: StorageOwner): Path = ownerDir(owner) / JOURNAL_FILE

    private companion object {
        const val STORAGE_DIR = "storage"
        const val APP_DIR = "app"
        const val PROFILES_DIR = "profiles"
        const val KV_DIR = "kv"
        const val DB_DIR = "db"
        const val JOURNAL_FILE = "events.json"
    }
}

/** Short owner name for logs: `app`, `profile <id>`. */
internal val StorageOwner.label: String
    get() = when (this) {
        StorageOwner.App -> "app"
        is StorageOwner.Profile -> "profile ${id.value}"
    }
