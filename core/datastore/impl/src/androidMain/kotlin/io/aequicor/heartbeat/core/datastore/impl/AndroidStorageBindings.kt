package io.aequicor.heartbeat.core.datastore.impl

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/** App-private internal storage (`Context.filesDir`), removed with the app. */
@ContributesBinding(AppScope::class)
@Inject
internal class AndroidStorageRoot(private val context: Context) : StorageRoot {
    override fun path(): String = context.filesDir.absolutePath
}

@ContributesBinding(AppScope::class)
@Inject
internal class AndroidRoomBuilderFactory(private val context: Context) : RoomBuilderFactory {
    override fun builder(path: String, factory: () -> RoomDatabase): RoomDatabase.Builder<RoomDatabase> =
        Room.databaseBuilder(context = context, name = path, factory = factory)
}
