package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

/** Exclusive owner of all app/profile stores. Metadata is never evidence of ownership; only the OS lock is. */
internal class DesktopStorageOwner private constructor(
    val directory: Path,
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    override fun close() {
        try {
            lock.release()
        } finally {
            channel.close()
        }
    }

    companion object {
        /** Fail closed on IO/security errors. Canonical paths make symlink aliases share the same lock. */
        fun claim(root: Path): DesktopStorageOwner? {
            Files.createDirectories(root)
            val directory = root.toRealPath().resolve("execution-owner")
            privateRuntimeDirectory(directory)
            val file = directory.resolve("owner.lock")
            check(!Files.isSymbolicLink(file)) { "Execution lock must not be a symbolic link" }
            val channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            return try {
                val lock = try {
                    channel.tryLock()
                } catch (error: OverlappingFileLockException) {
                    Log.tag("DesktopStorageOwner").w(error) { "Storage is already owned in this process" }
                    null
                }
                if (lock == null) {
                    channel.close()
                    null
                } else {
                    DesktopStorageOwner(directory, channel, lock)
                }
            } catch (error: CancellationException) {
                channel.close()
                throw error
            } catch (error: Exception) {
                channel.close()
                throw error
            }
        }
    }
}

/** Private runtime metadata on POSIX and Windows; never continue with an unprotected control credential. */
internal fun privateRuntimeDirectory(path: Path) {
    check(!Files.isSymbolicLink(path)) { "Runtime directory must not be a symbolic link" }
    Files.createDirectories(path)
    privateRuntimePath(path)
}

internal fun privateRuntimePath(path: Path) {
    val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
    if (posix != null) {
        posix.setPermissions(PosixFilePermissions.fromString(if (Files.isDirectory(path)) "rwx------" else "rw-------"))
    } else {
        val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?: throw IOException("Private runtime metadata permissions are unsupported")
        acl.acl = listOf(
            AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.owner)
                .setPermissions(AclEntryPermission.entries.toSet()).build(),
        )
    }
}
