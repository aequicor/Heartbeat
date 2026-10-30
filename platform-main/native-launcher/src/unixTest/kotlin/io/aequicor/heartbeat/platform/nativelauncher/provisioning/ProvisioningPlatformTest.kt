@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher.provisioning

import kotlinx.cinterop.toKString
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.posix.getenv
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProvisioningPlatformTest {
    @Test
    fun lockIsExclusiveAndReleaseIsIdempotent() {
        val root = (getenv("TMPDIR")?.toKString() ?: "/tmp").toPath()
            .resolve("heartbeat-native-lock-" + getpid())
        val lockPath = root.resolve("provision.lock")
        val first = assertNotNull(ProvisioningPlatform.tryLock(lockPath))
        try {
            assertNull(ProvisioningPlatform.tryLock(lockPath))
            assertTrue(ProvisioningPlatform.freeSpaceBytes(root) > 0)
        } finally {
            first.release()
            first.release()
        }
        assertNotNull(ProvisioningPlatform.tryLock(lockPath)).release()
        FileSystem.SYSTEM.deleteRecursively(root)
    }
}
