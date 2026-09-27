@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.core.secrets.impl.data

import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.posix.O_CREAT
import platform.posix.O_EXLOCK
import platform.posix.O_RDWR
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.close
import platform.posix.open

/** Stable advisory lock shared by every graph in this app sandbox. */
internal fun <T> withVaultLock(profile: String, action: () -> T): T {
    val directory = NSHomeDirectory() + "/Library/Application Support/Heartbeat/secrets"
    check(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)) {
        "Cannot create protected storage lock directory"
    }
    val descriptor = open("$directory/$profile.lock", O_CREAT or O_RDWR or O_EXLOCK, S_IRUSR or S_IWUSR)
    check(descriptor >= 0) { "Cannot open protected storage lock" }
    try {
        // O_EXLOCK acquires an advisory exclusive lock atomically with open, released on close.
        return action()
    } finally {
        check(close(descriptor) == 0) { "Cannot close protected storage lock" }
    }
}
