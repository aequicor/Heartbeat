package io.aequicor.heartbeat.feature.appupdate.impl.data

import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class MacBundleUpdateTest {
    private val root = createTempDirectory("app-update-mac")
    private val update = MacBundleUpdate(CommandRunner(TestDispatchers))

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `cleanup does not follow the image Applications link`() {
        assertCleanupPreservesLinkedApplications(root, ::cleanUp)
    }

    @Test
    fun `the bundle is three levels above its launcher`() {
        val bundle = Path.of("/Applications/Heartbeat.app")
        assertEquals(bundle, update.bundleOf(bundle.resolve("Contents/MacOS/Heartbeat")))
        assertNull(update.bundleOf(Path.of("/opt/heartbeat/bin/Heartbeat")))
    }

    @Test
    fun `a bundle in a folder the user cannot write is installed by hand`() {
        // Windows has no write permission bit on folders.
        if (System.getProperty("os.name").orEmpty().startsWith("Windows")) return
        val applications = Files.createDirectories(root.resolve("Applications"))
        val bundle = fakeBundle(applications, "1.3.0")
        update.requireReplaceable(bundle)

        applications.toFile().setWritable(false)
        try {
            assertUpdateFailure(AppUpdateFailure.ManualInstallRequired) { update.requireReplaceable(bundle) }
        } finally {
            // Restore only the directory changed by this test; image fixtures link to the real /Applications.
            applications.toFile().setWritable(true)
        }
    }

    @Test
    fun `the bundle of a release image is copied and checked`() = runTest(timeout = MAC_DISK_IMAGE_TEST_TIMEOUT) {
        if (!isMacHost) return@runTest
        val image = releaseImage("1.4.0")
        val directory = Files.createDirectories(root.resolve("updates/1.4.0"))

        val staged = update.prepare(image, "1.4.0", directory)

        assertEquals("1.4.0", bundleVersion(staged))
        assertTrue(Files.isRegularFile(staged.resolve("Contents/MacOS/Heartbeat")))
    }

    @Test
    fun `an image of another version is refused`() = runTest(timeout = MAC_DISK_IMAGE_TEST_TIMEOUT) {
        if (!isMacHost) return@runTest
        val image = releaseImage("1.3.9")
        val directory = Files.createDirectories(root.resolve("updates/1.4.0"))

        assertUpdateFailure(AppUpdateFailure.InvalidRelease) { update.prepare(image, "1.4.0", directory) }
    }

    @Test
    fun `the helper swaps the bundle once the app has quit and opens it`() {
        if (!isMacHost) return
        val installed = fakeBundle(Files.createDirectories(root.resolve("Applications")), "1.3.0")
        val staged = fakeBundle(Files.createDirectories(root.resolve("staging")), "1.4.0", "staged.app")
        val opened = root.resolve("opened")

        val exit = runHelper(helper(installed, staged), root.resolve("stubs"), opened)

        assertEquals(0, exit)
        assertEquals("1.4.0", bundleVersion(installed))
        assertFalse(staged.exists())
        assertFalse(installed.resolveSibling("Heartbeat.app.previous").exists())
        assertEquals("$installed", opened.readText().trim())
    }

    @Test
    fun `a failed swap keeps and opens the installed bundle`() {
        if (!isMacHost) return
        val installed = fakeBundle(Files.createDirectories(root.resolve("Applications")), "1.3.0")
        val opened = root.resolve("opened")

        val exit = runHelper(helper(installed, root.resolve("missing.app")), root.resolve("stubs"), opened)

        assertEquals(1, exit)
        assertEquals("1.3.0", bundleVersion(installed))
        assertEquals("$installed", opened.readText().trim())
    }

    private fun helper(installed: Path, staged: Path): List<String> {
        val script = root.resolve("apply-macos.sh")
        javaClass.getResourceAsStream("/app-update/apply-macos.sh")!!.use { Files.copy(it, script) }
        return update.helperCommand(script, endedProcessId(), installed, staged)
    }

    private fun releaseImage(version: String): Path {
        val source = Files.createDirectories(root.resolve("image-$version"))
        fakeBundle(source, version)
        return root.resolve("Heartbeat-$version-macos-arm64.dmg").also { diskImage(source, it) }
    }
}
