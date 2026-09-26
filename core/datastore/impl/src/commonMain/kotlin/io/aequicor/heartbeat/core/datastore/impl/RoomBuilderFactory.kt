package io.aequicor.heartbeat.core.datastore.impl

import androidx.room.RoomDatabase

/**
 * `Room.databaseBuilder` differs per platform (Android needs a `Context`), so it is bound per platform
 * (`<Platform>RoomBuilderFactory`). Room's builders are reified; the generated [factory] creates the concrete
 * database, so the builder is typed as [RoomDatabase] and the caller casts the result.
 */
internal fun interface RoomBuilderFactory {
    fun builder(path: String, factory: () -> RoomDatabase): RoomDatabase.Builder<RoomDatabase>
}
