package io.aequicor.heartbeat.feature.appupdate.impl.data

import io.aequicor.heartbeat.core.common.release.GitHubRelease
import io.aequicor.heartbeat.core.network.release.ReleaseDownload
import io.aequicor.heartbeat.core.network.release.ReleaseException
import io.aequicor.heartbeat.core.network.release.ReleaseFailure
import io.aequicor.heartbeat.core.network.release.ReleaseSource
import io.aequicor.heartbeat.core.network.release.VerifiedReleaseDownloader
import io.aequicor.heartbeat.feature.appupdate.api.AppRelease
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure
import io.aequicor.heartbeat.feature.appupdate.api.AppVersion
import io.aequicor.heartbeat.feature.appupdate.impl.domain.AppInstallation
import io.aequicor.heartbeat.feature.appupdate.impl.domain.AppUpdateException
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
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class DesktopUpdateInstallerTest {
    private val root = createTempDirectory("app-update-installer")
    private val workspace = UpdateWorkspace(root.resolve("updates"))
    private val repository = GitHubRepository("aequicor", "Heartbeat")
    private val launcherRoot = root.resolve("launcher")
    private val trainOffer = AppRelease(
        "t2",
        "train-manifest.json",
        "$TRAIN_DOWNLOADS/train-t2/train-manifest.json",
        "",
        null,
        "",
        train = "t2",
    )
    private val started = mutableListOf<List<String>>()
    private val helperLogs = mutableListOf<Path>()
    private var requests = 0

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `cleanup does not follow the image Applications link`() {
        assertCleanupPreservesLinkedApplications(root, ::cleanUp)
    }

    @Test
    fun `a verified release image replaces the installed bundle after the app quits`() = runTest(
        timeout = MAC_DISK_IMAGE_TEST_TIMEOUT,
    ) {
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
        val image = root.resolve("image.dmg").also { Files.write(it, byteArrayOf(1)) }
        applications.toFile().setWritable(false)
        try {
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
        } finally {
            // Restore only the directory changed by this test; image fixtures link to the real /Applications.
            applications.toFile().setWritable(true)
        }
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
    fun `Linux offers a package-manager update without downloading or launching helpers`() = runTest {
        val file = root.resolve("unused.deb")
        val launcher = root.resolve("heartbeat/bin/heartbeat")
        val update = AppRelease(
            "1.4.0",
            "Heartbeat-1.4.0-linux-x64.deb",
            "https://example.com/a.deb",
            "ab",
            null,
            "",
        )

        assertUpdateFailure(AppUpdateFailure.ManualInstallRequired) {
            installer(
                file,
                launcher,
            ).install(installation(ReleasePlatform.LinuxX64), update, recording(mutableListOf()))
        }
        assertEquals(0, requests)
        assertTrue(started.isEmpty())
        assertEquals(PreviousAttempt.None, workspace.takeOutcome(AppVersion(1, 3, 0)))
    }

    @Test
    fun `a queued train relaunches the same app bundle once the app quits`() = runTest {
        val installed = fakeBundle(Files.createDirectories(root.resolve("Applications")), "1.3.0")

        trainInstaller(installed.resolve("Contents/MacOS/Heartbeat"))
            .install(installation(ReleasePlatform.MacOsArm64), trainOffer, recording(mutableListOf()))

        assertTrue(Files.isRegularFile(launcherRoot.resolve("pending/t2/manifest.json")))
        val command = started.single()
        assertEquals(listOf("/bin/sh", "-c"), command.take(2))
        assertEquals(listOf("heartbeat-relaunch", "$PROCESS_ID", "$installed", "/usr/bin/open"), command.drop(3))
        // The relaunch logs next to its directory, which the next launch removes: helper.log does not grow.
        assertEquals(root.resolve("updates/relaunch/relaunch.log"), helperLogs.single())
        if (!isWindowsHost) {
            val opened = root.resolve("opened")
            val stubs = root.resolve("stubs")
            val ended = command.mapIndexed { index, part ->
                when (index) {
                    4 -> "${endedProcessId()}"
                    6 -> "${stubs.resolve("open")}"
                    else -> part
                }
            }
            assertEquals(0, runHelper(ended, stubs, opened))
            assertEquals("$installed", opened.readText().trim())
        }
        // No installer runs: the next launch must not report an attempt of a version that never changes.
        assertEquals(PreviousAttempt.None, workspace.takeOutcome(AppVersion(1, 3, 0)))
        assertFalse(root.resolve("updates/relaunch").exists())
        assertFalse(root.resolve("updates/helper.log").exists())
    }

    @Test
    fun `a relaunch helper that cannot start still leaves the train queued`() = runTest {
        val installed = fakeBundle(Files.createDirectories(root.resolve("Applications")), "1.3.0")

        // Completes normally: the app quits and the user's next start applies the queued train.
        trainInstaller(installed.resolve("Contents/MacOS/Heartbeat"), failHelper = true)
            .install(installation(ReleasePlatform.MacOsArm64), trainOffer, recording(mutableListOf()))

        assertTrue(Files.isRegularFile(launcherRoot.resolve("pending/t2/manifest.json")))
        assertEquals(1, started.size)
    }

    @Test
    fun `a launch without a known launcher queues the train without a relaunch`() = runTest {
        trainInstaller(launcher = null)
            .install(installation(ReleasePlatform.WindowsX64), trainOffer, recording(mutableListOf()))

        assertTrue(Files.isRegularFile(launcherRoot.resolve("pending/t2/manifest.json")))
        assertTrue(started.isEmpty())
    }

    @Test
    fun `on Windows a queued train relaunches the launcher without an installer`() = runTest {
        val launcher = root.resolve("Heartbeat/Heartbeat.exe")

        trainInstaller(launcher)
            .install(installation(ReleasePlatform.WindowsX64), trainOffer, recording(mutableListOf()))

        val script = root.resolve("updates/relaunch/apply-windows.ps1")
        assertEquals(
            listOf("-File", "$script", "-ProcessId", "$PROCESS_ID", "-App", "$launcher"),
            started.single().dropWhile { it != "-File" },
        )
        assertTrue(Files.isRegularFile(script))
        assertTrue(Files.isRegularFile(launcherRoot.resolve("pending/t2/manifest.json")))
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

    private fun installer(file: Path, launcher: Path): DesktopUpdateInstaller {
        val downloader = UpdateDownloader(
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
        )
        return installer(downloader, launcher)
    }

    /**
     * An installer whose train `t2` has every jar in the store already: nothing is downloaded. [failHelper] makes the
     * relaunch helper fail to start.
     */
    private fun trainInstaller(launcher: Path?, failHelper: Boolean = false): DesktopUpdateInstaller {
        val jar = "core-bytes".toByteArray()
        Files.createDirectories(launcherRoot.resolve("store"))
        Files.write(storeJar(launcherRoot, sha256(jar)), jar)
        val list = "https://api.github.com/repos/aequicor/Heartbeat/releases?per_page=30"
        val publications = releaseJson("train-t2", listOf("train-manifest.json" to 10L, "core.jar" to 10L))
        val source = object : ReleaseSource {
            override suspend fun latestGitHubRelease(owner: String, repository: String): GitHubRelease =
                throw ReleaseException(ReleaseFailure.Network)

            override suspend fun document(url: String, allowedHosts: Set<String>, maxBytes: Int): String = when (url) {
                list -> "[$publications]"
                trainOffer.url -> manifestJson("t2", listOf("core.jar" to sha256(jar)))
                else -> throw ReleaseException(ReleaseFailure.Http, 404)
            }
        }
        return installer(UpdateDownloader(NoDownloads, TestDispatchers), launcher, source, failHelper)
    }

    private fun installer(
        downloader: UpdateDownloader,
        launcher: Path?,
        trainSource: ReleaseSource = NoReleases,
        failHelper: Boolean = false,
    ): DesktopUpdateInstaller {
        val properties = listOfNotNull(
            launcher?.let { "jpackage.app-path" to "$it" },
            "heartbeat.launcher.root" to "$launcherRoot",
        ).toMap()
        return DesktopUpdateInstaller(
            workspace = workspace,
            downloader = downloader,
            mac = MacBundleUpdate(CommandRunner(TestDispatchers)),
            trains = TrainUpdate(GitHubReleaseClient(trainSource), downloader, Dispatchers.IO),
            io = Dispatchers.IO,
            host = InstallerLaunch(
                properties = LaunchProperties(properties::get),
                startHelper = { command, output ->
                    started += command
                    helperLogs.add(output)
                    if (failHelper) throw AppUpdateException(AppUpdateFailure.InstallFailed, "helper did not start")
                },
                processId = { PROCESS_ID },
            ),
        )
    }

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

    private object NoDownloads : VerifiedReleaseDownloader {
        override suspend fun download(request: ReleaseDownload, target: Path, progress: suspend (Long, Long?) -> Unit) =
            error("nothing must be downloaded")
    }

    private companion object {
        const val PROCESS_ID = 4242L
        val isWindowsHost = System.getProperty("os.name").orEmpty().startsWith("Windows")
    }
}
