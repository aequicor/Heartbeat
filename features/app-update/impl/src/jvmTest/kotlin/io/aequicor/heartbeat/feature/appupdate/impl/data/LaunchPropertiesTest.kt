package io.aequicor.heartbeat.feature.appupdate.impl.data

import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.feature.appupdate.api.AppVersion
import io.aequicor.heartbeat.feature.appupdate.api.UnsupportedReason
import io.aequicor.heartbeat.feature.appupdate.impl.domain.AppInstallation
import io.aequicor.heartbeat.feature.appupdate.impl.domain.GitHubRepository
import io.aequicor.heartbeat.feature.appupdate.impl.domain.InstallationCheck
import io.aequicor.heartbeat.feature.appupdate.impl.domain.ReleasePlatform
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
}
