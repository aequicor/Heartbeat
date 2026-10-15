package io.aequicor.heartbeat.feature.browser.impl.data

import io.aequicor.heartbeat.core.common.release.GitHubDownloadHosts
import io.aequicor.heartbeat.core.network.release.ReleaseDownload
import io.aequicor.heartbeat.core.network.release.ReleaseException
import io.aequicor.heartbeat.core.network.release.ReleaseFailure
import io.aequicor.heartbeat.core.network.release.VerifiedReleaseDownloader
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserRuntimeFailure
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserRuntimePreparation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopBrowserRuntimeInstallerTest {
    @Test
    fun `downloads pinned host once and a new process reuses complete cache offline`() = runTest {
        val root = Files.createTempDirectory("browser-runtime")
        try {
            val fixture = RuntimeFixture()
            val dispatchers = browserRuntimeDispatchers(StandardTestDispatcher(testScheduler))
            val installer = fixture.installer(root, dispatchers)
            val first = async { installer.prepare(RELEASE_TAG, PLATFORM) }
            val second = async { installer.prepare(RELEASE_TAG, PLATFORM) }
            val directory = first.await()
            assertEquals(directory, second.await())
            assertEquals(1, fixture.requests.size)
            val request = fixture.requests.single()
            assertEquals(
                "https://github.com/aequicor/Heartbeat/releases/download/$VERSION/jcef-natives-$PLATFORM-$VERSION.jar",
                request.url,
            )
            assertEquals(fixture.sha, request.sha256)
            assertEquals(GitHubDownloadHosts, request.allowedHosts)
            assertEquals(1L shl 30, request.maxBytes)
            assertEquals(BrowserRuntimePreparation.Ready, installer.preparation.value)
            fixture.failure = ReleaseException(ReleaseFailure.Network)
            assertEquals(directory, fixture.installer(root, dispatchers).prepare(RELEASE_TAG, PLATFORM))
            assertEquals(1, fixture.requests.size)
            assertEquals("license", Files.readString(directory.resolve("JCEF-LICENSE.txt")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `corrupt cache gets a new immutable installation without altering old files`() = runTest {
        val root = Files.createTempDirectory("browser-runtime-corrupt")
        try {
            val fixture = RuntimeFixture()
            val dispatchers = browserRuntimeDispatchers(StandardTestDispatcher(testScheduler))
            val old = fixture.installer(root, dispatchers).prepare(RELEASE_TAG, PLATFORM)
            Files.writeString(old.resolve("libcef.so"), "changed")
            val new = fixture.installer(root, dispatchers).prepare(RELEASE_TAG, PLATFORM)
            assertNotEquals(old, new)
            assertEquals("changed", Files.readString(old.resolve("libcef.so")))
            assertEquals("native", Files.readString(new.resolve("libcef.so")))
            assertEquals(2, fixture.requests.size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `network and checksum failures are visible retryable and never activate partial files`() = runTest {
        val root = Files.createTempDirectory("browser-runtime-retry")
        try {
            val fixture = RuntimeFixture()
            val installer = fixture.installer(root, browserRuntimeDispatchers(StandardTestDispatcher(testScheduler)))
            for ((failure, expected) in listOf(
                ReleaseFailure.Network to BrowserRuntimeFailure.Network,
                ReleaseFailure.ChecksumMismatch to BrowserRuntimeFailure.InvalidRuntime,
            )) {
                fixture.failure = ReleaseException(failure)
                assertFailsWith<ReleaseException> { installer.prepare(RELEASE_TAG, PLATFORM) }
                assertEquals(BrowserRuntimePreparation.Failed(expected), installer.preparation.value)
                assertFalse(Files.walk(root).use { paths -> paths.anyMatch { it.fileName.toString() == "active" } })
                assertFalse(
                    Files.walk(root).use { paths -> paths.anyMatch { it.fileName.toString().startsWith(".staging-") } },
                )
            }
            fixture.failure = null
            assertTrue(Files.isDirectory(installer.prepare(RELEASE_TAG, PLATFORM)))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `download requirement never waits and remembers a verified cache without downloading`() = runTest {
        val root = Files.createTempDirectory("browser-runtime-required")
        try {
            val fixture = RuntimeFixture()
            val dispatchers = browserRuntimeDispatchers(StandardTestDispatcher(testScheduler))
            val installer = fixture.installer(root, dispatchers)
            assertTrue(installer.requiresDownload(RELEASE_TAG, PLATFORM))
            assertTrue(fixture.requests.isEmpty())
            assertFalse(fixture.installer(root, dispatchers, bundled = true).requiresDownload(RELEASE_TAG, PLATFORM))

            fixture.hold = true
            val pending = async { installer.prepare(RELEASE_TAG, PLATFORM) }
            fixture.started.await()
            assertTrue(installer.requiresDownload(RELEASE_TAG, PLATFORM), "A running download is not awaited")
            pending.cancelAndJoin()
            fixture.hold = false
            installer.prepare(RELEASE_TAG, PLATFORM)
            assertFalse(installer.requiresDownload(RELEASE_TAG, PLATFORM))

            val restarted = fixture.installer(root, dispatchers)
            assertFalse(restarted.requiresDownload(RELEASE_TAG, PLATFORM))
            assertEquals(BrowserRuntimePreparation.Ready, restarted.preparation.value)
            assertEquals(2, fixture.requests.size, "The cache check never downloads")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `cancelled download cleans staging resets progress and allows retry`() = runTest {
        val root = Files.createTempDirectory("browser-runtime-cancel")
        try {
            val fixture = RuntimeFixture()
            fixture.hold = true
            val installer = fixture.installer(root, browserRuntimeDispatchers(StandardTestDispatcher(testScheduler)))
            val pending = async { installer.prepare(RELEASE_TAG, PLATFORM) }
            fixture.started.await()
            assertEquals(BrowserRuntimePreparation.Downloading(4, 10), installer.preparation.value)
            pending.cancelAndJoin()
            assertEquals(BrowserRuntimePreparation.Idle, installer.preparation.value)
            assertFalse(
                Files.walk(root).use { paths -> paths.anyMatch { it.fileName.toString().startsWith(".staging-") } },
            )
            fixture.hold = false
            assertTrue(Files.isDirectory(installer.prepare(RELEASE_TAG, PLATFORM)))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `bundled verified archive installs without any download`() = runTest {
        val root = Files.createTempDirectory("browser-runtime-bundled")
        try {
            val fixture = RuntimeFixture()
            fixture.failure = ReleaseException(ReleaseFailure.Network)
            val installer = fixture.installer(
                root,
                browserRuntimeDispatchers(StandardTestDispatcher(testScheduler)),
                bundled = true,
            )
            val runtime = installer.prepare(RELEASE_TAG, PLATFORM)
            assertEquals("native", Files.readString(runtime.resolve("libjcef.so")))
            assertTrue(fixture.requests.isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unparseable completion marker is repaired through a new installation`() = runTest {
        val root = Files.createTempDirectory("browser-runtime-marker")
        try {
            val fixture = RuntimeFixture()
            val dispatchers = browserRuntimeDispatchers(StandardTestDispatcher(testScheduler))
            val old = fixture.installer(root, dispatchers).prepare(RELEASE_TAG, PLATFORM)
            Files.writeString(old.resolve(".heartbeat-runtime.json"), "partial")
            val replacement = fixture.installer(root, dispatchers).prepare(RELEASE_TAG, PLATFORM)
            assertNotEquals(old, replacement)
            assertEquals(2, fixture.requests.size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `API or platform mismatch fails before downloading`() = runTest {
        val fixture = RuntimeFixture()
        val root = Files.createTempDirectory("browser-runtime-lock")
        try {
            val installer = fixture.installer(root, browserRuntimeDispatchers(StandardTestDispatcher(testScheduler)))
            assertFailsWith<IllegalArgumentException> { installer.prepare("other", PLATFORM) }
            assertFailsWith<IllegalArgumentException> { installer.prepare(RELEASE_TAG, "linux-arm64") }
            assertTrue(fixture.requests.isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a second process cannot publish while an installation owns the file lock`() = runTest {
        val fixture = RuntimeFixture().apply { hold = true }
        val root = Files.createTempDirectory("browser-runtime-locking")
        try {
            val dispatchers = browserRuntimeDispatchers(StandardTestDispatcher(testScheduler))
            val first = async { fixture.installer(root, dispatchers).prepare(RELEASE_TAG, PLATFORM) }
            fixture.started.await()
            assertFailsWith<java.nio.channels.OverlappingFileLockException> {
                fixture.installer(root, dispatchers).prepare(RELEASE_TAG, PLATFORM)
            }
            first.cancelAndJoin()
            runCurrent()
            assertEquals(1, fixture.requests.size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

private class RuntimeFixture : VerifiedReleaseDownloader {
    val requests = mutableListOf<ReleaseDownload>()
    var failure: Exception? = null
    var hold = false
    val started = CompletableDeferred<Unit>()
    private val archive = browserTar(
        (
            listOf(
                "libjcef.so",
                "libcef.so",
                "jcef_helper",
                "icudtl.dat",
                "resources.pak",
                "v8_context_snapshot.bin",
                "locales/en-US.pak",
            ).map { it to "native" } + listOf(
                "build_meta.json" to """{"release_tag":"$RELEASE_TAG","platform":"$PLATFORM"}""",
                "JCEF-LICENSE.txt" to "license",
            )
        ).map { (name, text) -> TarArchiveEntry(name).apply { mode = 493 } to text.toByteArray() },
    )
    private val jar = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("jcef-natives-$PLATFORM-$RELEASE_TAG.tar.gz"))
            zip.write(archive)
            zip.closeEntry()
        }
        output.toByteArray()
    }
    val sha: String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jar))
    private val metadata = """{
        "artifactVersion":"$VERSION", "jcefCommit":"${"a".repeat(40)}", "cefVersion":"test",
        "nativeArtifacts":{"$PLATFORM":{"file":"jcef-natives-$PLATFORM-$VERSION.jar","sha256":"$sha"}}
    }"""

    fun installer(
        root: Path,
        dispatchers: io.aequicor.heartbeat.core.common.DispatcherProvider,
        bundled: Boolean = false,
    ) = DesktopBrowserRuntimeInstaller(
        root,
        this,
        dispatchers,
        { metadata },
        { if (bundled) archive.inputStream() else null },
    )

    override suspend fun download(request: ReleaseDownload, target: Path, progress: suspend (Long, Long?) -> Unit) {
        requests += request
        Files.write(target, byteArrayOf(1))
        progress(4, 10)
        started.complete(Unit)
        failure?.let { throw it }
        if (hold) awaitCancellation()
        Files.write(target, jar)
    }
}

private const val VERSION = "152.0.6-hb5"
private const val PLATFORM = "linux-amd64"
private const val RELEASE_TAG = "jcef-aaaaaaa+cef-test+heartbeat-hb5"
