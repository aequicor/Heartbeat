package io.aequicor.heartbeat.feature.appupdate.impl.data

import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.feature.appupdate.api.AppVersion
import io.aequicor.heartbeat.feature.appupdate.api.UnsupportedReason
import io.aequicor.heartbeat.feature.appupdate.impl.domain.AppInstallation
import io.aequicor.heartbeat.feature.appupdate.impl.domain.GitHubRepository
import io.aequicor.heartbeat.feature.appupdate.impl.domain.InstallationCheck
import io.aequicor.heartbeat.feature.appupdate.impl.domain.ReleasePlatform
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LaunchPropertiesTest {
    private val release = mapOf(
        "heartbeat.app.version" to "1.3.0",
        "heartbeat.app.update.repository" to "aequicor/Heartbeat",
        "jpackage.app-path" to "/Applications/Heartbeat.app/Contents/MacOS/Heartbeat",
        "os.arch" to "aarch64",
    )

    private fun probe(properties: Map<String, String>, host: HostPlatform = HostPlatform.MacOs) =
        DesktopInstallationProbe(
            object : PlatformInfo {
                override val host = host
            },
            LaunchProperties(properties::get),
        )

    @Test
    fun `an installed release build updates itself`() {
        assertEquals(
            InstallationCheck.Supported(
                AppInstallation(
                    AppVersion(1, 3, 0),
                    GitHubRepository("aequicor", "Heartbeat"),
                    ReleasePlatform.MacOsArm64,
                ),
            ),
            probe(release).current(),
        )
    }

    @Test
    fun `the installed train is known only when the launcher states its root`() {
        val train = release + ("heartbeat.train" to "t1")
        assertEquals("t1", trainOf(train + ("heartbeat.launcher.root" to "/launcher")))
        assertNull(trainOf(train))
        assertNull(trainOf(release))
    }

    @Test
    fun `rolled back and queued trains come from the launcher`() {
        val launcherRoot = createTempDirectory("app-update-launcher")
        try {
            Files.createDirectories(launcherRoot.resolve("pending/t3"))
            Files.writeString(launcherRoot.resolve("pending/t3/manifest.json"), "{}")
            // A train without its manifest is not queued yet.
            Files.createDirectories(launcherRoot.resolve("pending/t4"))
            val properties = release + mapOf(
                "heartbeat.train" to "t1",
                "heartbeat.launcher.root" to "$launcherRoot",
                "heartbeat.train.bad" to " t2, ,t5 ",
            )

            val installation = (probe(properties).current() as InstallationCheck.Supported).installation

            assertEquals(setOf("t2", "t5"), installation.badTrains)
            assertEquals(setOf("t3"), installation.pendingTrains)
        } finally {
            launcherRoot.toFile().deleteRecursively()
        }
    }

    private fun trainOf(properties: Map<String, String>): String? =
        (probe(properties).current() as InstallationCheck.Supported).installation.train

    @Test
    fun `development launches never update themselves`() {
        val notRelease = InstallationCheck.Unsupported(UnsupportedReason.NotReleaseBuild)
        assertEquals(notRelease, probe(release - "heartbeat.app.update.repository").current())
        assertEquals(notRelease, probe(release - "jpackage.app-path").current())
        assertEquals(notRelease, probe(release + ("heartbeat.app.version" to "development")).current())
        assertEquals(notRelease, probe(release + ("heartbeat.app.update.repository" to "not a repository")).current())
    }

    @Test
    fun `hosts without a published build are unsupported`() {
        val unsupported = InstallationCheck.Unsupported(UnsupportedReason.UnsupportedPlatform)
        assertEquals(unsupported, probe(release, HostPlatform.Linux).current())
        assertEquals(unsupported, probe(release + ("os.arch" to "riscv64")).current())
    }

    @Test
    fun `the JVM architecture picks the build`() {
        assertEquals(ReleasePlatform.MacOsArm64, releasePlatform(HostPlatform.MacOs, "aarch64"))
        assertEquals(ReleasePlatform.MacOsX64, releasePlatform(HostPlatform.MacOs, "x86_64"))
        assertEquals(ReleasePlatform.WindowsX64, releasePlatform(HostPlatform.Windows, "amd64"))
        assertEquals(ReleasePlatform.WindowsArm64, releasePlatform(HostPlatform.Windows, "aarch64"))
        assertEquals(ReleasePlatform.LinuxX64, releasePlatform(HostPlatform.Linux, "amd64"))
        assertEquals(ReleasePlatform.LinuxX64, releasePlatform(HostPlatform.Linux, "x86_64"))
        assertNull(releasePlatform(HostPlatform.Linux, "aarch64"))
        assertNull(releasePlatform(HostPlatform.Android, "aarch64"))
    }

    @Test
    fun `native installation is updated while workers retain the JVM path`() {
        val inner = "/cache/images/one/Heartbeat.app/Contents/MacOS/Heartbeat"
        val outer = "/Applications/Heartbeat.app/Contents/MacOS/Heartbeat"
        val properties = release + mapOf("jpackage.app-path" to inner, "heartbeat.native.launcher" to outer)
        assertEquals(Path.of(outer).toAbsolutePath(), LaunchProperties(properties::get).launcher)
        assertEquals(inner, properties["jpackage.app-path"])
    }

    @Test
    fun `invalid native path cannot fall back to updating the inner cache`() {
        val properties = release + ("heartbeat.native.launcher" to "relative/Heartbeat")
        assertNull(LaunchProperties(properties::get).launcher)
        assertNull(LaunchProperties((release + ("heartbeat.native.launcher" to "bad\u0000path"))::get).launcher)
    }

    @Test
    fun `direct JVM cache fallback runs without claiming a replaceable installation`() {
        val image = createTempDirectory("app-update-bootstrap")
        try {
            Files.writeString(image.resolve("bootstrap-image.json"), "{}")
            listOf("Heartbeat.exe", "bin/Heartbeat", "Heartbeat.app/Contents/MacOS/Heartbeat").forEach { relative ->
                val executable = image.resolve(relative)
                Files.createDirectories(executable.parent)
                Files.writeString(executable, "")
                val properties = release + ("jpackage.app-path" to executable.toString())
                assertNull(LaunchProperties(properties::get).launcher)
                assertEquals(
                    InstallationCheck.Unsupported(UnsupportedReason.NotReleaseBuild),
                    probe(properties).current(),
                )
                val native = properties + ("heartbeat.native.launcher" to image.resolve("outer/Heartbeat").toString())
                assertEquals(image.resolve("outer/Heartbeat"), LaunchProperties(native::get).launcher)
            }
        } finally {
            image.toFile().deleteRecursively()
        }
    }
}
