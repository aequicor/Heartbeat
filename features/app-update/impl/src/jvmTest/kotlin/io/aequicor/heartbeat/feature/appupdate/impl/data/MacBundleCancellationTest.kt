package io.aequicor.heartbeat.feature.appupdate.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class MacBundleCancellationTest {
    @Test
    fun `cancellation after attach finishes still detaches the image`() = runTest {
        if (!isMacHost) return@runTest
        val root = createTempDirectory("app-update-cancel")
        val directory = Files.createDirectories(root.resolve("update"))
        val mount = directory.resolve("mount")
        val source = Files.createDirectories(root.resolve("source"))
        fakeBundle(source, "1.4.0")
        val image = root.resolve("release.dmg")
        diskImage(source, image)
        lateinit var preparing: Job
        val ioDispatcher = AfterAttachDispatcher { preparing.cancel() }
        val dispatchers = object : DispatcherProvider by TestDispatchers {
            override val io: CoroutineDispatcher = ioDispatcher
        }
        try {
            preparing = launch(start = CoroutineStart.LAZY) {
                MacBundleUpdate(CommandRunner(dispatchers)).prepare(image, "1.4.0", directory)
            }
            preparing.start()
            preparing.join()

            assertTrue(preparing.isCancelled)
            assertTrue(Files.list(mount).use { it.findAny().isEmpty }, "Cancelled preparation left a mounted image")
        } finally {
            // A regression must not leave the real test image attached on the developer's machine.
            val detach = ProcessBuilder("/usr/bin/hdiutil", "detach", "$mount", "-force")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            detach.waitFor()
            root.deleteRecursively()
        }
    }

    /** Runs attach to completion then cancels its caller before the dispatched result can be delivered. */
    private class AfterAttachDispatcher(private val cancel: () -> Unit) : CoroutineDispatcher() {
        private var isFirst = true

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val shouldCancel = isFirst
            isFirst = false
            block.run()
            if (shouldCancel) cancel()
        }
    }
}
