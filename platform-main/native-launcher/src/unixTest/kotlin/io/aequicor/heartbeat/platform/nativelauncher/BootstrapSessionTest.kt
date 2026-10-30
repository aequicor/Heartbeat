package io.aequicor.heartbeat.platform.nativelauncher

import io.aequicor.heartbeat.platform.nativelauncher.provisioning.BootstrapLayout
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ImageStore
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionedImage
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisioningPlatform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BootstrapSessionTest {
    @Test
    fun offlineImageWaitsForNativeWindowToHideAndPreservesApplicationExitCode() {
        val fs = FileSystem.SYSTEM
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.resolve("hb-session-${Random.nextLong()}")
        val target = launcherHostTarget()
        val config = LauncherConfig("", "", emptyList(), root.resolve("Heartbeat"), "1.0.0", target, "release")
        val store = ImageStore(config.imageRoot)
        val staged = store.stagingDir("test")
        val path = staged.resolve(BootstrapLayout.forPlatform(target.substringBefore('-'), "release").executable)
        fs.createDirectories(checkNotNull(path.parent))
        val script = """
            #!/bin/sh
            printf ready > "${'$'}HEARTBEAT_HANDOFF_DIR/ready"
            while [ ! -e "${'$'}HEARTBEAT_HANDOFF_DIR/hidden" ]; do sleep 0.01; done
            exit 23
        """.trimIndent() + "\n"
        fs.write(path) { writeUtf8(script) }
        ProvisioningPlatform.applyFileMode(path, 493)
        store.activate(
            ProvisionedImage("test", store.imageDir("test"), "jbr", "train", "1.0.0", target, "release"),
            staged,
        )
        val session = BootstrapSession(config, emptyList())
        try {
            runBlocking {
                withTimeout(10_000) {
                    session.start()
                    val ready = session.state.first { it is BootstrapSession.State.ReadyToLaunch }
                        as BootstrapSession.State.ReadyToLaunch
                    session.launch(ready.image)
                    val handoff = session.state.first { it is BootstrapSession.State.Handoff }
                        as BootstrapSession.State.Handoff
                    assertTrue(!fs.exists(handoff.directory.resolve("hidden")))
                    session.acknowledgeHandoff(handoff.directory)
                    val finished = session.state.first { it is BootstrapSession.State.Finished }
                        as BootstrapSession.State.Finished
                    assertEquals(23, finished.code)
                }
            }
        } finally {
            session.dispose()
            fs.deleteRecursively(root)
        }
    }

    @Test
    fun cancellationLetsOwnedChildExitBeforeRemovingHandoff() {
        val fs = FileSystem.SYSTEM
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.resolve("hb-cancel-${Random.nextLong()}")
        val target = launcherHostTarget()
        val config = LauncherConfig("", "", emptyList(), root.resolve("Heartbeat"), "1.0.0", target, "release")
        val store = ImageStore(config.imageRoot)
        val staged = store.stagingDir("test")
        val path = staged.resolve(BootstrapLayout.forPlatform(target.substringBefore('-'), "release").executable)
        fs.createDirectories(checkNotNull(path.parent))
        val clean = root.resolve("clean")
        fs.write(path) {
            writeUtf8(
                """
                #!/bin/sh
                printf ready > "${'$'}HEARTBEAT_HANDOFF_DIR/ready"
                while [ ! -e "${'$'}HEARTBEAT_HANDOFF_DIR/cancel" ]; do sleep 0.01; done
                printf clean > "${'$'}1"
                exit 0
                """.trimIndent() + "\n",
            )
        }
        ProvisioningPlatform.applyFileMode(path, 493)
        store.activate(
            ProvisionedImage("test", store.imageDir("test"), "jbr", "train", "1.0.0", target, "release"),
            staged,
        )
        val session = BootstrapSession(config, listOf(clean.toString()))
        try {
            runBlocking {
                withTimeout(10_000) {
                    session.start()
                    val ready = session.state.first { it is BootstrapSession.State.ReadyToLaunch }
                        as BootstrapSession.State.ReadyToLaunch
                    session.launch(ready.image)
                    val handoff = session.state.first { it is BootstrapSession.State.Handoff }
                        as BootstrapSession.State.Handoff
                    session.cancel()
                    val finished = session.state.first { it is BootstrapSession.State.Finished }
                        as BootstrapSession.State.Finished
                    assertEquals(0, finished.code)
                    assertTrue(fs.exists(clean))
                    assertTrue(!fs.exists(handoff.directory))
                }
            }
        } finally {
            session.dispose()
            fs.deleteRecursively(root)
        }
    }
}
