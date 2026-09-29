package io.aequicor.heartbeat.core.datastore.impl

import androidx.room.Room
import androidx.room.RoomDatabase
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.datastore.StorageRoot
import java.io.File

/** Per-user application data: `%APPDATA%` (Windows), `Application Support` (macOS), `$XDG_DATA_HOME` (Linux). */
@ContributesBinding(AppScope::class)
@Inject
internal class JvmStorageRoot(private val platform: PlatformInfo) : StorageRoot {
    override fun path(): String {
        val home = System.getProperty("user.home").orEmpty()
        val linuxData = System.getenv("XDG_DATA_HOME") ?: "$home/.local/share"
        val dir = when (platform.host) {
            HostPlatform.Windows -> File(System.getenv("APPDATA") ?: "$home/AppData/Roaming", "Aequicor/Heartbeat")
            HostPlatform.MacOs -> File(home, "Library/Application Support/Heartbeat")
            HostPlatform.Linux, HostPlatform.Android, HostPlatform.Ios -> File(linuxData, "heartbeat")
        }
        return dir.absolutePath
    }
}

@ContributesBinding(AppScope::class)
@Inject
internal class JvmRoomBuilderFactory : RoomBuilderFactory {
    override fun builder(path: String, factory: () -> RoomDatabase): RoomDatabase.Builder<RoomDatabase> =
        Room.databaseBuilder(name = path, factory = factory)
}
