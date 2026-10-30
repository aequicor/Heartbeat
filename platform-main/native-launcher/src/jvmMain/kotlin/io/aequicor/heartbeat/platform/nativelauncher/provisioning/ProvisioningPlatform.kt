package io.aequicor.heartbeat.platform.nativelauncher.provisioning

import okio.Path
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** JVM-реализация примитивов provisioning: блокировка канала файла и свободное место файлового хранилища. */
@Suppress("UnusedPrivateProperty") // detekt не видит использования свойств в actual-функциях KMP.
internal actual object ProvisioningPlatform {
    private val log = io.aequicor.heartbeat.core.logging.Log.tag("Provision")

    actual fun freeSpaceBytes(path: Path): Long = Files.getFileStore(path.toNioPath()).usableSpace

    actual fun applyFileMode(path: Path, unixMode: Int) {
        val view = Files.getFileAttributeView(
            path.toNioPath(),
            java.nio.file.attribute.PosixFileAttributeView::class.java,
        )
        if (view == null) {
            return
        }
        val bits = FILE_READ_WRITE or (unixMode and EXECUTABLE_BITS)
        val permissions = java.nio.file.attribute.PosixFilePermission.entries.filterIndexed { index, _ ->
            bits and (1 shl (PERMISSION_BITS - index)) != 0
        }.toSet()
        Files.setPosixFilePermissions(path.toNioPath(), permissions)
    }

    private const val FILE_READ_WRITE = 420 // 0644
    private const val EXECUTABLE_BITS = 73 // 0111
    private const val PERMISSION_BITS = 8

    actual fun tryLock(path: Path): ProvisionLockHandle? {
        Files.createDirectories(path.toNioPath().parent)
        val channel = FileChannel.open(
            path.toNioPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        )
        val lock = try {
            channel.tryLock()
        } catch (e: java.nio.channels.OverlappingFileLockException) {
            log.w(e) { "Provision lock is already held in this JVM" }
            null
        }
        return if (lock == null) {
            channel.close()
            null
        } else {
            JvmLockHandle(channel, lock)
        }
    }
}

private class JvmLockHandle(private val channel: FileChannel, private val lock: FileLock) : ProvisionLockHandle {
    private val log = io.aequicor.heartbeat.core.logging.Log.tag("Provision")

    override fun release() {
        runCatching { lock.release() }.onFailure { log.w(it) { "Provision lock release failed" } }
        runCatching { channel.close() }.onFailure { log.w(it) { "Provision lock channel close failed" } }
    }
}
