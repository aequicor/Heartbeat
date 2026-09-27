package io.aequicor.heartbeat.core.datastore.impl

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import io.aequicor.heartbeat.core.logging.Log

/** Logs a manual migration of a feature database (`I`, tag `DB`). */
internal class LoggingMigration(private val label: String, private val delegate: Migration) :
    Migration(delegate.startVersion, delegate.endVersion) {

    override fun migrate(connection: SQLiteConnection) {
        Log.tag(DB_LOG_TAG).i { "$label: migrating $startVersion -> $endVersion" }
        delegate.migrate(connection)
    }
}
