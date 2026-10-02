package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.install

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ArchiveKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.GitHubDownloadHosts
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.InstallPlan
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.InstallStep
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class FileManagedInstallStoreTest {
    private val root: Path = Files.createTempDirectory("heartbeat-managed")
    private val engine = EngineId("codex")
    private val releases = mutableMapOf<String, ByteArray>()
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(1_000)
    }

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `cancellation on return from acquiring the file lock still releases it`() = runTest {
        lateinit var request: kotlinx.coroutines.Deferred<Unit>
        val dispatcher = afterDispatch(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)) {
            if (root.resolve("codex/.lock").exists()) request.cancel()
        }
        request = async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            store(dispatcher).stage(engine, plan("1.0.0")) {}
            Unit
        }
        request.start()
        request.join()
        assertTrue(request.isCancelled)
        FileChannel.open(root.resolve("codex/.lock"), StandardOpenOption.WRITE).use { channel ->
            kotlin.test.assertNotNull(channel.tryLock()).use { }
        }
    }

    @Test
    fun `refresh in another store preserves a candidate being checked`() = runTest {
        val first = store()
        val staged = first.stage(engine, plan("2.0.0")) {}
        store().refresh()
        assertTrue(Path.of(staged.candidate.executable).exists())
        val lease = root.resolve("codex/staging/${staged.token}/.lease")
        assertFalse(canLockFromAnotherProcess(lease), "same-JVM refresh must preserve the native lease")
        assertEquals("2.0.0", first.activate(staged).version)
        assertNoLeftovers()
    }

    @Test
    fun `discard releases the stage lease even when another engine operation refuses its lock`() = runTest {
        val first = store()
        val staged = first.stage(engine, plan("1.0.0")) {}
        FileChannel.open(root.resolve("codex/.lock"), StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                assertInstallFailure(InstallFailureReason.FilesInUse) { first.discard(staged) }
            }
        }
        assertFalse(root.resolve("codex/staging/${staged.token}/.lease").exists())
        store().refresh()
        assertFalse(Path.of(staged.candidate.executable).exists())
    }

    private fun canLockFromAnotherProcess(file: Path): Boolean {
        val program = root.resolve("LockProbe.java")
        program.writeText(LOCK_PROBE)
        val binary = if (isPosix) "java" else "java.exe"
        val java = Path.of(System.getProperty("java.home"), "bin", binary).toString()
        val process = ProcessBuilder(java, program.toString(), file.toString()).redirectErrorStream(true).start()
        return try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            assertEquals(0, process.exitValue(), output)
            output.toBooleanStrict()
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `a malformed active record preserves the installed executable`() = runTest {
        val first = store()
        val installed = first.activate(first.stage(engine, plan("1.0.0")) {})
        root.resolve("codex/active.json").writeText("broken json")
        assertInstallFailure(InstallFailureReason.Storage) { store().refresh() }
        assertTrue(Path.of(installed.executable).exists())
    }

    @Test
    fun `uninstall and update refuse a record pointing to an external directory`() = runTest {
        val first = store()
        val installed = first.activate(first.stage(engine, plan("1.0.0")) {})
        val external = Files.createDirectories(root.resolve("external"))
        external.resolve("keep.txt").writeText("keep")
        val record = root.resolve("codex/active.json")
        val version = Path.of(installed.executable).parent.parent.fileName.toString()
        record.writeText(record.readText().replace(version, external.toString().replace("\\", "\\\\")))
        assertInstallFailure(InstallFailureReason.Storage) { first.uninstall(engine) }
        val staged = first.stage(engine, plan("2.0.0")) {}
        assertInstallFailure(InstallFailureReason.Storage) { first.activate(staged) }
        first.discard(staged)
        assertEquals("keep", external.resolve("keep.txt").readText())
        assertTrue(Path.of(installed.executable).exists())
    }

    @Test
    fun `a staged copy runs nothing until it is activated`() = runTest {
        val store = store()
        val steps = mutableListOf<InstallStep>()

        val staged = store.stage(engine, plan("1.0.0"), steps::add)

        assertTrue(Path.of(staged.candidate.executable).exists())
        if (isPosix) assertTrue(Path.of(staged.candidate.executable).isExecutable())
        assertEquals(emptyMap(), store.state.value)
        assertEquals(
            listOf(InstallStep.Verified, InstallStep.Unpacking),
            steps.filterNot { it is InstallStep.Downloading },
        )
        assertTrue(steps.first() is InstallStep.Downloading)

        val installed = store.activate(staged)

        assertEquals("1.0.0", installed.version)
        assertEquals("codex 1.0.0", Path.of(installed.executable).readText())
        assertEquals(mapOf(engine to installed), store.state.value)
        assertTrue(Path.of(installed.executable).startsWith(root.resolve("codex/versions")))
        assertNoLeftovers()
    }

    @Test
    fun `the active copy survives a restart and a newer one replaces it`() = runTest {
        val first = store()
        first.activate(first.stage(engine, plan("1.0.0")) {})

        val restarted = store()
        restarted.refresh()
        assertEquals("1.0.0", restarted.state.value.getValue(engine).version)

        val updated = restarted.activate(restarted.stage(engine, plan("1.1.0")) {})

        assertEquals("codex 1.1.0", Path.of(updated.executable).readText())
        assertEquals(1, root.resolve("codex/versions").listDirectoryEntries().size)
        // A busy runtime may still run the replaced copy: it is deleted by the next refresh, not right away.
        assertEquals(1, root.resolve("codex/trash").listDirectoryEntries().size)
        store().refresh()
        assertNoLeftovers()
    }

    @Test
    fun `a replaced copy that cannot be moved aside stays until a later refresh`() = runTest {
        val store = store()
        store.activate(store.stage(engine, plan("1.0.0")) {})
        // A file where the trash folder belongs makes moving the previous copy fail, as a copy in use does on Windows.
        root.resolve("codex/trash").also { it.toFile().deleteRecursively() }.writeText("blocked")

        val updated = store.activate(store.stage(engine, plan("1.1.0")) {})

        assertEquals(mapOf(engine to updated), store.state.value)
        assertEquals(2, root.resolve("codex/versions").listDirectoryEntries().size)
        root.resolve("codex/trash").toFile().delete()
        val restarted = store()
        restarted.refresh()
        assertEquals("1.1.0", restarted.state.value.getValue(engine).version)
        assertEquals(1, root.resolve("codex/versions").listDirectoryEntries().size)
        assertNoLeftovers()
    }

    @Test
    fun `a refresh leaves the files of an engine another process is changing`() = runTest {
        val store = store()
        store.activate(store.stage(engine, plan("1.0.0")) {})
        Files.createDirectories(root.resolve("codex/staging/in-progress"))
        FileChannel.open(root.resolve("codex/.lock"), StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val other = store()
                other.refresh()
                assertTrue(root.resolve("codex/staging/in-progress").exists())
                assertEquals(emptyMap(), other.state.value)
            }
        }
        store().refresh()
        assertNoLeftovers()
    }

    @Test
    fun `a discarded or failed stage leaves the active copy and no partial files`() = runTest {
        val store = store()
        val installed = store.activate(store.stage(engine, plan("1.0.0")) {})

        store.discard(store.stage(engine, plan("2.0.0")) {})
        assertInstallFailure(InstallFailureReason.ChecksumMismatch) {
            store.stage(engine, plan("3.0.0").copy(sha256 = "0".repeat(64))) {}
        }
        assertInstallFailure(InstallFailureReason.ExecutableMissing) {
            store.stage(engine, plan("4.0.0").copy(executable = "bin/missing")) {}
        }

        assertEquals(mapOf(engine to installed), store.state.value)
        assertEquals("codex 1.0.0", Path.of(installed.executable).readText())
        assertNoLeftovers()
    }

    @Test
    fun `uninstalling removes the copy for this and later sessions`() = runTest {
        val store = store()
        store.activate(store.stage(engine, plan("1.0.0")) {})

        store.uninstall(engine)

        assertEquals(emptyMap(), store.state.value)
        assertFalse(root.resolve("codex/active.json").exists())
        assertTrue(root.resolve("codex/versions").listDirectoryEntries().isEmpty())
        val restarted = store()
        restarted.refresh()
        assertEquals(emptyMap(), restarted.state.value)
        store.uninstall(engine)
    }

    @Test
    fun `a record pointing outside its folder is ignored`() = runTest {
        val store = store()
        store.activate(store.stage(engine, plan("1.0.0")) {})
        val record = root.resolve("codex/active.json")
        record.writeText(record.readText().replace("bin/codex", "../../../outside"))

        val restarted = store()
        assertInstallFailure(InstallFailureReason.InvalidArchive) { restarted.refresh() }

        assertEquals(emptyMap(), restarted.state.value)
    }

    @Test
    fun `another Heartbeat process changing the copies makes them busy`() = runTest {
        val store = store()
        Files.createDirectories(root.resolve("codex"))
        FileChannel.open(
            root.resolve("codex/.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.lock().use {
                assertInstallFailure(InstallFailureReason.FilesInUse) { store.stage(engine, plan("1.0.0")) {} }
            }
        }

        store.activate(store.stage(engine, plan("1.0.0")) {})
        assertEquals("1.0.0", store.state.value.getValue(engine).version)
    }

    private fun store(io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO): FileManagedInstallStore {
        val client = releaseClient { request ->
            val bytes = releases[request.url.encodedPath]
            if (bytes == null) respond("", HttpStatusCode.NotFound) else respond(bytes, HttpStatusCode.OK)
        }
        return FileManagedInstallStore(
            root,
            ReleaseDownloader(client, io),
            ArchiveExtractor(),
            io,
            clock,
        )
    }

    private fun plan(version: String): InstallPlan {
        val path = "/openai/codex/releases/download/rust-v$version/codex-package.tar.gz"
        val archive = tarGz(
            directory("codex-package/"),
            directory("codex-package/bin/"),
            file("codex-package/bin/codex", "codex $version", mode = 0b111_101_101),
        )
        releases[path] = archive
        return InstallPlan(
            version,
            "https://github.com$path",
            sha256(archive),
            archive.size.toLong(),
            ArchiveKind.TarGz(stripComponents = 1),
            "bin/codex",
            GitHubDownloadHosts,
        )
    }

    private fun assertNoLeftovers() {
        listOf("staging", "downloads", "trash").map { root.resolve("codex").resolve(it) }.filter { it.exists() }
            .forEach { assertTrue(it.listDirectoryEntries().isEmpty(), "$it is not empty") }
    }

    private val isPosix = "posix" in root.fileSystem.supportedFileAttributeViews()
}

private const val LOCK_PROBE = """
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
class LockProbe {
    public static void main(String[] args) throws Exception {
        try (var channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            System.out.println(lock != null);
        }
    }
}
"""
