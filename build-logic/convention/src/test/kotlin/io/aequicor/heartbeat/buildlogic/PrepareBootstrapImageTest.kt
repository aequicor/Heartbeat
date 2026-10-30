package io.aequicor.heartbeat.buildlogic

import org.gradle.testfixtures.ProjectBuilder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrepareBootstrapImageTest {
    @Test
    fun `staged fallback retains the original image and exact runtime bytes`() {
        verifyStaging("windows")
        if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            verifyStaging("linux")
        }
    }

    @Test
    fun `staging refuses to overwrite the source image`() {
        val root = Files.createTempDirectory("bootstrap-overlap-test").toFile()
        try {
            val project = ProjectBuilder.builder().withProjectDir(root).build()
            val source = root.resolve("original").apply { mkdirs() }
            val keep = source.resolve("keep").apply { writeText("original") }
            val task = project.tasks.create("prepareImage", PrepareBootstrapImage::class.java).apply {
                sourceImage.set(source)
                os.set("windows")
                appName.set("Heartbeat")
                destination.set(source)
            }
            assertFailsWith<IllegalArgumentException> { task.prepare() }
            assertTrue(keep.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun verifyStaging(os: String) {
        val root = Files.createTempDirectory("bootstrap-image-test").toFile()
        try {
            val project = ProjectBuilder.builder().withProjectDir(root).build()
            val source = root.resolve("original").apply { mkdirs() }
            val binary = source.resolve(if (os == "windows") "Heartbeat.exe" else "bin/Heartbeat")
            val config = source.resolve(if (os == "windows") "app/Heartbeat.cfg" else "lib/app/Heartbeat.cfg")
            binary.parentFile.mkdirs()
            config.parentFile.mkdirs()
            binary.writeBytes(byteArrayOf(1, 2, 3))
            binary.setExecutable(true, false)
            config.writeText("original JVM configuration")
            val runtime = source.resolve("runtime/lib/runtime.bin").apply { parentFile.mkdirs() }
            val runtimeBytes = byteArrayOf(4, 5, 6)
            runtime.writeBytes(runtimeBytes)
            val destination = root.resolve("staged")
            val task = project.tasks.create("prepareImage", PrepareBootstrapImage::class.java).apply {
                sourceImage.set(source)
                this.os.set(os)
                appName.set("Heartbeat")
                this.destination.set(destination)
            }
            task.prepare()
            val alias = destination.resolve(if (os == "windows") "HeartbeatJvm.exe" else "bin/HeartbeatJvm")
            assertContentEquals(binary.readBytes(), alias.readBytes())
            assertContentEquals(config.readBytes(), alias.parentFile.resolve(
                if (os == "windows") "app/HeartbeatJvm.cfg" else "../lib/app/HeartbeatJvm.cfg",
            ).readBytes())
            assertContentEquals(runtimeBytes, destination.resolve("runtime/lib/runtime.bin").readBytes())
            assertFalse(source.resolve(if (os == "windows") "HeartbeatJvm.exe" else "bin/HeartbeatJvm").exists())
            if (os == "linux") assertTrue(alias.canExecute())
        } finally {
            root.deleteRecursively()
        }
    }
}
