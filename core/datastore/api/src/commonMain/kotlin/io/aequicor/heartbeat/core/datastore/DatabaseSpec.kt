package io.aequicor.heartbeat.core.datastore

import androidx.room.ColumnInfo
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * Description of a feature's Room database, opened by [DataStores.database].
 *
 * ```
 * internal val ChatDatabaseSpec = DatabaseSpec("chat", ChatDatabaseConstructor::initialize, ChatMigrations.all)
 * ```
 *
 * @param T the `@Database` class.
 * @property name file name of the database within its owner: `[a-z][a-z0-9_]*`, unique across all features of the
 *   owner — prefix it with the feature; `core_*` is reserved. Stored on disk — never rename. Declare one spec per
 *   database (a top-level `val`): the core rejects a second, different spec with the same name.
 * @property factory creates the generated implementation: `<Db>Constructor::initialize` (`@ConstructedBy`).
 * @property migrations manual migrations; `@AutoMigration`s are declared on the database itself.
 */
@Suppress("UseDataClass") // holds a factory lambda: structural equality would be meaningless
public class DatabaseSpec<T : RoomDatabase>(
    public val name: String,
    public val factory: () -> T,
    public val migrations: List<Migration> = emptyList(),
) {
    init {
        requireStorageName(name)
    }

    override fun toString(): String = "db $name"
}

/**
 * Retention columns of a database row. An entity whose rows expire or belong to an event embeds it
 * **without a prefix**; the core finds such tables by the column names and deletes the rows:
 *
 * ```
 * @Entity(indices = [Index("hb_expires_at")])
 * data class MessageEntity(@PrimaryKey val id: String, val text: String, @Embedded val retention: RecordRetention)
 *
 * dao.insert(MessageEntity(id, text, retentions.stamp(Retention.expiring(Expiry.After(7.days)))))
 * ```
 * Adding it to an existing entity is a schema change: a migration is required.
 *
 * @property createdAt when the row was written, epoch millis: events delete rows written before they were fired.
 * @property expiresAt deadline, epoch millis, or `null`.
 * @property event name of the [DataEvent], or `null`.
 */
public data class RecordRetention(
    @ColumnInfo(name = CREATED_AT_COLUMN) public val createdAt: Long,
    @ColumnInfo(name = EXPIRES_AT_COLUMN) public val expiresAt: Long?,
    @ColumnInfo(name = EVENT_COLUMN) public val event: String?,
) {
    /** Column names, used by the core to find and clean the tables. */
    public companion object {
        /** Column of [createdAt]. */
        public const val CREATED_AT_COLUMN: String = "hb_created_at"

        /** Column of [expiresAt]. */
        public const val EXPIRES_AT_COLUMN: String = "hb_expires_at"

        /** Column of [event]. */
        public const val EVENT_COLUMN: String = "hb_event"
    }
}

/** Turns a [Retention] into [RecordRetention] columns at the moment of writing. App-scoped. */
public interface RecordRetentions {
    /** Columns of a row written now with [retention]. */
    public fun stamp(retention: Retention): RecordRetention
}
