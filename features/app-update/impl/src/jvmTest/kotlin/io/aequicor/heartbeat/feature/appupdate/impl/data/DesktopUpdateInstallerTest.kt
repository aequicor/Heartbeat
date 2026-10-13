package io.aequicor.heartbeat.feature.appupdate.impl.data

import io.aequicor.heartbeat.core.network.release.ReleaseDownload
import io.aequicor.heartbeat.core.network.release.VerifiedReleaseDownloader
import io.aequicor.heartbeat.feature.appupdate.api.AppRelease
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure
import io.aequicor.heartbeat.feature.appupdate.api.AppVersion
import io.aequicor.heartbeat.feature.appupdate.impl.domain.AppInstallation
import io.aequicor.heartbeat.feature.appupdate.impl.domain.GitHubRepository
import io.aequicor.heartbeat.feature.appupdate.impl.domain.InstallProgressSink
import io.aequicor.heartbeat.feature.appupdate.impl.domain.PreviousAttempt
import io.aequicor.heartbeat.feature.appupdate.impl.domain.ReleasePlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class DesktopUpdateInstallerTest {
    private val root = createTempDirectory("app-update-installer")
    private val workspace = UpdateWorkspace(root.resolve("updates"))
    private val repository = GitHubRepository("aequicor", "Heartbeat")
    private val started = mutableListOf<List<String>>()
    private var requests = 0

    @AfterTest
    fun cleanUp() {
        root.toFile().walk().forEach { it.setWritable(true) }
        root.deleteRecursively()
    }

    @Test
    fun `a verified release image replaces the installed bundle after the app quits`() = runTest {
        if (!isMacHost) return@runTest
        val installed = fakeBundle(Files.createDirectories(root.resolve("Applications")), "1.3.0")
        val image = releaseImage("1.4.0")
        val release = release("1.4.0", image, ReleasePlatform.MacOsArm64)
        val phases = mutableListOf<String>()

        installer(image, launcher = installed.resolve("Contents/MacOS/Heartbeat"))
            .install(installation(ReleasePlatform.MacOsArm64), release, recording(phases))

        assertEquals("preparing", phases.last())
        assertTrue(phases.first().startsWith("downloading"))
        val command = started.single()
        assertEquals(listOf("/bin/sh", "$PROCESS_ID", "$installed"), listOf(command[0], command[2], command[3]))
        assertEquals("1.4.0", bundleVersion(Path.of(command[4])))
        assertEquals(
            0,
            runHelper(
                command.mapIndexed { index, part -> if (index == 2) "${endedProcessId()}" else part },
                root.resolve("stubs"),
                root.resolve("opened"),
            ),
        )
        assertEquals("1.4.0", bundleVersion(installed))
        assertEquals(PreviousAttempt.Succeeded, workspace.takeOutcome(AppVersion(1, 4, 0)))
    }

    @Test
    fun `a bundle the user cannot replace is refused before anything is downloaded`() = runTest {
        if (!isMacHost) return@runTest
        val applications = Files.createDirectories(root.resolve("Applications"))
        val installed = fakeBundle(applications, "1.3.0")
        applications.toFile().setWritable(false)
        val image = root.resolve("image.dmg").also { Files.write(it, byteArrayOf(1)) }

        assertUpdateFailure(AppUpdateFailure.ManualInstallRequired) {
            installer(image, launcher = installed.resolve("Contents/MacOS/Heartbeat"))
                .install(
                    installation(ReleasePlatform.MacOsArm64),
                    release("1.4.0", image, ReleasePlatform.MacOsArm64),
                    recording(mutableListOf()),
                )
        }
        assertEquals(0, requests)
        assertTrue(started.isEmpty())
    }

    @Test
    fun `on Windows the helper runs the downloaded installer silently and restarts the app`() = runTest {
        val setup = root.resolve("setup.exe").also { Files.write(it, ByteArray(1_024) { 7 }) }
        val launcher = root.resolve("Heartbeat/Heartbeat.exe")
        val release = release("1.4.0", setup, ReleasePlatform.WindowsX64)

        installer(
            setup,
            launcher,
        ).install(installation(ReleasePlatform.WindowsX64), release, recording(mutableListOf()))

        val command = started.single()
        val directory = root.resolve("updates/1.4.0")
        assertEquals(
            listOf(
                "-File", "${directory.resolve("apply-windows.ps1")}",
                "-ProcessId", "$PROCESS_ID",
                "-Installer", "${directory.resolve(release.assetName)}",
                "-App", "$launcher",
                "-InstallLog", "${directory.resolve("install.log")}",
            ),
            command.dropWhile { it != "-File" },
        )
        assertTrue(Files.isRegularFile(directory.resolve("apply-windows.ps1")))
        assertEquals(PreviousAttempt.Failed, workspace.takeOutcome(AppVersion(1, 3, 0)))
    }

    @Test
    fun `the outcome of an earlier attempt is read once per launch`() = runTest {
        workspace.releaseDirectory("1.4.0")
        workspace.markStarted("1.4.0")
        val installer = installer(root.resolve("unused"), root.resolve("Heartbeat/Heartbeat.exe"))

        assertEquals(PreviousAttempt.Failed, installer.previousAttempt(AppVersion(1, 3, 0)))
        // A later check of the same launch keeps the outcome; the mark itself is already gone.
        assertEquals(PreviousAttempt.Failed, installer.previousAttempt(AppVersion(1, 3, 0)))
        assertEquals(PreviousAttempt.None, workspace.takeOutcome(AppVersion(1, 3, 0)))
    }

    private fun installer(file: Path, launcher: Path) = DesktopUpdateInstaller(
        workspace = workspace,
        downloader = UpdateDownloader(
            object : VerifiedReleaseDownloader {
                override suspend fun download(
                    request: ReleaseDownload,
                    target: Path,
                    progress: suspend (Long, Long?) -> Unit,
                ) {
                    requests++
                    Files.copy(file, target)
                    progress(Files.size(target), request.size)
                }
            },
            TestDispatchers,
        ),
        mac = MacBundleUpdate(CommandRunner(TestDispatchers)),
        launch = LaunchProperties(mapOf("jpackage.app-path" to "$launcher")::get),
        io = Dispatchers.IO,
        processId = { PROCESS_ID },
        startHelper = { command, _ -> started += command },
    )

    private fun installation(platform: ReleasePlatform) = AppInstallation(AppVersion(1, 3, 0), repository, platform)

    private fun release(version: String, file: Path, platform: ReleasePlatform): AppRelease {
        val name = platform.assetName(AppVersion.parse(version)!!)
        return AppRelease(
            version = version,
            assetName = name,
            url = "https://github.com/aequicor/Heartbeat/releases/download/v$version/$name",
            sha256 = sha256(file.readBytes()),
            size = Files.size(file),
            pageUrl = "https://github.com/aequicor/Heartbeat/releases/tag/v$version",
        )
    }

    private fun recording(phases: MutableList<String>) = object : InstallProgressSink {
        override suspend fun downloading(received: Long, total: Long?) {
            phases += "downloading $received/$total"
        }

        override suspend fun preparing() {
            phases += "preparing"
        }
    }

    private fun releaseImage(version: String): Path {
        val source = Files.createDirectories(root.resolve("image-$version"))
        fakeBundle(source, version)
        return root.resolve("release-$version.dmg").also { diskImage(source, it) }
    }

    private companion object {
        const val PROCESS_ID = 4242L
    }
}
