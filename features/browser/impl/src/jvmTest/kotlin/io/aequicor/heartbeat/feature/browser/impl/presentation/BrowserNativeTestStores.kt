package io.aequicor.heartbeat.feature.browser.impl.presentation

import androidx.room.RoomDatabase
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.StorageOwner
import java.nio.file.Path

/** Native fixtures use only the real app-owned directory layout; no storage database is needed. */
internal class BrowserNativeTestStores(private val root: Path) : DataStores {
    override val owner = StorageOwner.App
    override fun filesDirectory(name: String): String = root.resolve("storage/app/files").resolve(name).toString()
    override fun keyValue(spec: KeyValueSpec): KeyValueStore = error("Native fixture does not use key-value storage")
    override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("Native fixture does not use Room")
    override suspend fun fire(event: DataEvent): Unit = error("Native fixture does not use retention events")
}
