package io.aequicor.heartbeat.core.datastore.impl

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import okio.FileSystem
import okio.Path.Companion.toPath

/** SQLite does not create missing directories: creates the parent of the file on Room's IO thread, then opens it. */
internal class DirectoryCreatingDriver(private val delegate: SQLiteDriver, private val fileSystem: FileSystem) :
    SQLiteDriver by delegate {
    override fun open(fileName: String): SQLiteConnection {
        fileName.toPath().parent?.let(fileSystem::createDirectories)
        return delegate.open(fileName)
    }
}
