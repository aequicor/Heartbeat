package io.aequicor.heartbeat.core.datastore.impl

import androidx.room.Room
import androidx.room.RoomDatabase
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.datastore.StorageRoot
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/** `Library/Application Support` of the app container: not visible in Files, backed up. */
@ContributesBinding(AppScope::class)
@Inject
internal class IosStorageRoot : StorageRoot {
    @OptIn(ExperimentalForeignApi::class)
    override fun path(): String {
        val url = NSFileManager.defaultManager.URLForDirectory(
            directory = NSApplicationSupportDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        )
        return requireNotNull(url?.path) { "Application Support directory is unavailable" }
    }
}

@ContributesBinding(AppScope::class)
@Inject
internal class IosRoomBuilderFactory : RoomBuilderFactory {
    override fun builder(path: String, factory: () -> RoomDatabase): RoomDatabase.Builder<RoomDatabase> =
        Room.databaseBuilder(name = path, factory = factory)
}
