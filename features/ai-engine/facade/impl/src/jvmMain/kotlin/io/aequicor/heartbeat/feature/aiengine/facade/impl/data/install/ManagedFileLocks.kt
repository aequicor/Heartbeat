package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.install

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** App-wide ownership of managed-copy locks. Every store in one app shares this registry. */
internal interface ManagedFileLocks {
    /** Acquires the named lock of [directory], or refuses when the app or another process already owns it. */
    fun acquire(directory: Path, name: String): FileLock

    /** Releases an acquired lock and its channel; a null or already closed lock is harmless. */
    fun release(lock: FileLock?)

    /** True if [directory]'s named lease is absent or can be acquired, without disturbing a live app lease. */
    fun isAbandoned(directory: Path, name: String): Boolean
}

/**
 * One channel per locked file: POSIX can release every lock on an inode when any channel of that inode closes.
 * A sweep never opens a second channel of a locally owned file. App shutdown closes all remaining channels.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
internal class JvmManagedFileLocks(
    @ForScope(AppScope::class) scope: ScopeHandle,
) : ManagedFileLocks {
    private val log = Log.tag("ManagedFileLocks")
    private val locks = mutableMapOf<Path, FileLock>()
    private var isClosed = false

    init {
        scope.onClose(::close)
    }

    override fun acquire(directory: Path, name: String): FileLock = synchronized(locks) {
        if (isClosed) refuse()
        storage { Files.createDirectories(directory) }
        val file = storage { directory.toRealPath().resolve(name) }
        if (file in locks) refuse()
        val channel = storage {
            FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        }
        var isLocked = false
        try {
            val acquired = try {
                storage { channel.tryLock() }
            } catch (e: OverlappingFileLockException) {
                log.w(e.withoutDetails()) { "managed copy lock is already held in this process" }
                null
            }
            isLocked = acquired != null
            if (acquired != null) locks[file] = acquired
            acquired ?: refuse()
        } finally {
            if (!isLocked) storage { channel.close() }
        }
    }

    override fun release(lock: FileLock?) {
        if (lock == null) return
        synchronized(locks) {
            try {
                storage { lock.channel().close() }
            } finally {
                locks.entries.removeAll { it.value == lock }
            }
        }
    }

    override fun isAbandoned(directory: Path, name: String): Boolean = synchronized(locks) {
        try {
            val file = directory.toRealPath().resolve(name)
            if (file in locks) return@synchronized false
            if (!Files.exists(file)) return@synchronized true
            FileChannel.open(file, StandardOpenOption.WRITE).use { channel ->
                channel.tryLock()?.use { true } ?: false
            }
        } catch (e: OverlappingFileLockException) {
            log.w(e.withoutDetails()) { "staged copy is being checked in this process" }
            false
        } catch (e: IOException) {
            log.w(e.withoutDetails()) { "staged copy lease could not be checked; files are kept" }
            false
        }
    }

    private fun close() = synchronized(locks) {
        isClosed = true
        locks.values.forEach { lock ->
            try {
                lock.channel().close()
            } catch (e: IOException) {
                log.w(e.withoutDetails()) { "managed copy lock could not be closed on shutdown" }
            }
        }
        locks.clear()
    }

    private fun refuse(): Nothing {
        log.w { "managed copy lock is unavailable" }
        throw installFailure(InstallFailureReason.FilesInUse)
    }

    @Suppress("SwallowedException") // File system details contain user paths.
    private fun <T> storage(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        log.w(e.withoutDetails()) { "managed copy lock could not be accessed" }
        throw installFailure(InstallFailureReason.Storage, e.withoutDetails())
    }
}
