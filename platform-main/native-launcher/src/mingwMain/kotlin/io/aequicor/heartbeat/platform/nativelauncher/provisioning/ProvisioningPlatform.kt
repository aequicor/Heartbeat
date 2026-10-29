@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher.provisioning

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import okio.Path
import platform.windows.CloseHandle
import platform.windows.CreateFileW
import platform.windows.GENERIC_WRITE
import platform.windows.GetDiskFreeSpaceExW
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.OPEN_ALWAYS

/** Windows-реализация примитивов provisioning: эксклюзивный CreateFileW и свободное место GetDiskFreeSpaceExW. */
internal actual object ProvisioningPlatform {
    actual fun freeSpaceBytes(path: Path): Long = memScoped {
        val free = alloc<platform.windows.ULARGE_INTEGER>()
        check(GetDiskFreeSpaceExW(path.toString().wcstr.getPointer(this), free.ptr, null, null) != 0) {
            "Cannot query free space for $path"
        }
        free.QuadPart.toLong()
    }

    actual fun tryLock(path: Path): ProvisionLockHandle? = memScoped {
        okio.FileSystem.SYSTEM.createDirectories(path.parent ?: return@memScoped null)
        val handle = CreateFileW(
            path.toString().wcstr.getPointer(this),
            GENERIC_WRITE.toUInt(),
            0u,
            null,
            OPEN_ALWAYS.toUInt(),
            0u,
            null,
        )
        if (handle == INVALID_HANDLE_VALUE) null else WinLockHandle(handle!!)
    }
}

private class WinLockHandle(
    private val handle: platform.windows.HANDLE,
) : ProvisionLockHandle {
    override fun release() {
        CloseHandle(handle)
    }
}
