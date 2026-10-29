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
