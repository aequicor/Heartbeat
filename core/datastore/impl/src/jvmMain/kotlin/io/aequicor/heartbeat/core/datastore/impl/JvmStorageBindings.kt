package io.aequicor.heartbeat.core.datastore.impl

import androidx.room.Room
import androidx.room.RoomDatabase
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.datastore.StorageRoot
import io.aequicor.heartbeat.core.logging.desktopDataName
import io.aequicor.heartbeat.core.logging.desktopDataNameLowercase
import java.io.File

/** Per-user application data: `%APPDATA%` (Windows), `Application Support` (macOS), `$XDG_DATA_HOME` (Linux). */
@ContributesBinding(AppScope::class)
@Inject
internal class JvmStorageRoot : StorageRoot {
    override fun path(): String = jvmStorageRootPath()
}

/** Resolves the same root before graph creation, so desktop hosts can exclude concurrent storage owners. */
public fun jvmStorageRootPath(): String {
    val home = System.getProperty("user.home").orEmpty()
    val os = System.getProperty("os.name").orEmpty()
    return when {
        os.startsWith("Windows", ignoreCase = true) ->
            File(System.getenv("APPDATA") ?: "$home/AppData/Roaming", "Aequicor/${desktopDataName()}")

        os.startsWith("Mac", ignoreCase = true) -> File(home, "Library/Application Support/${desktopDataName()}")

        else -> linuxStorageDirectory(home, System.getenv("XDG_DATA_HOME"))
    }.absolutePath
}

/** XDG requires an absolute data home; empty or relative values use the user's standard data directory. */
internal fun linuxStorageDirectory(home: String, xdgDataHome: String?): File {
    val dataHome = xdgDataHome?.takeIf { it.isNotBlank() && File(it).isAbsolute } ?: "$home/.local/share"
    return File(dataHome, desktopDataNameLowercase())
}

@ContributesBinding(AppScope::class)
@Inject
internal class JvmRoomBuilderFactory : RoomBuilderFactory {
    override fun builder(path: String, factory: () -> RoomDatabase): RoomDatabase.Builder<RoomDatabase> =
        Room.databaseBuilder(name = path, factory = factory)
}
