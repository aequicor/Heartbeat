@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher.provisioning

import io.aequicor.heartbeat.platform.nativelauncher.unix.hb_bootstrap_free_space
import io.aequicor.heartbeat.platform.nativelauncher.unix.hb_bootstrap_lock
import kotlinx.cinterop.convert
import okio.FileSystem
import okio.Path
import platform.posix.O_CREAT
import platform.posix.O_RDWR
import platform.posix.chmod
import platform.posix.close
import platform.posix.errno
import platform.posix.open

/** Unix provisioning uses descriptor-owned locks; a process exit releases the lock automatically. */
internal actual object ProvisioningPlatform {
    actual fun freeSpaceBytes(path: Path): Long {
        val free = hb_bootstrap_free_space(path.toString())
        check(free >= 0) { "Cannot query free space for $path: errno=$errno" }
        return free
    }

    actual fun applyFileMode(path: Path, unixMode: Int) {
        val mode = FILE_READ_WRITE or (unixMode and EXECUTABLE_BITS)
        check(chmod(path.toString(), mode.convert()) == 0) { "Cannot set file mode for $path: errno=$errno" }
    }

    private const val FILE_READ_WRITE = 420 // 0644
    private const val EXECUTABLE_BITS = 73 // 0111

    actual fun tryLock(path: Path): ProvisionLockHandle? {
        FileSystem.SYSTEM.createDirectories(checkNotNull(path.parent))
        val descriptor = open(path.toString(), O_CREAT or O_RDWR, OWNER_READ_WRITE)
        check(descriptor >= 0) { "Cannot open provisioning lock $path: errno=$errno" }
        val result = hb_bootstrap_lock(descriptor)
        if (result == 0) return UnixLockHandle(descriptor)
        val failure = errno
        check(close(descriptor) == 0) { "Cannot close provisioning lock: errno=$errno" }
        check(result == 1) { "Cannot lock $path: errno=$failure" }
        return null
    }

    private const val OWNER_READ_WRITE = 384 // 0600
}

private class UnixLockHandle(private val descriptor: Int) : ProvisionLockHandle {
    private var released = false

    override fun release() {
        if (released) return
        released = true
        check(close(descriptor) == 0) { "Cannot release provisioning lock: errno=$errno" }
    }
}
