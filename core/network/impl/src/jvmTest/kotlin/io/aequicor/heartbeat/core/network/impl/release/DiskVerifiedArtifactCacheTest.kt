package io.aequicor.heartbeat.core.network.impl.release

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.network.release.ReleaseArtifact
import io.aequicor.heartbeat.core.network.release.ReleaseDownload
import io.aequicor.heartbeat.core.network.release.ReleaseFailure
import io.aequicor.heartbeat.core.network.release.VerifiedReleaseDownloader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiskVerifiedArtifactCacheTest {
    private val workspace = Files.createTempDirectory("heartbeat-artifact-cache")
    private val bytes = "pinned optional component".toByteArray()
    private val request = ReleaseDownload(
        "https://example.test/component.jar",
        sha256(bytes),
        bytes.size.toLong(),
        setOf("example.test"),
    )
    private val artifact = ReleaseArtifact("component.jar", request)

    @AfterTest
    fun cleanUp() {
        workspace.toFile().deleteRecursively()
    }

    @Test
    fun `a new process reuses verified bytes without downloading`() = runTest {
        var downloads = 0
        val first = cache { target ->
            downloads++
            Files.write(target, bytes, CREATE_NEW)
        }.prepare(artifact)
        val second = cache { error("Offline must not download") }.prepare(artifact)
        assertEquals(first, second)
        assertContentEquals(bytes, Files.readAllBytes(second))
        assertEquals(1, downloads)
    }

    @Test
    fun `corruption is detected and replaced before reuse`() = runTest {
        var downloads = 0
        val cache = cache { target ->
            downloads++
            Files.write(target, bytes, CREATE_NEW)
        }
        val installed = cache.prepare(artifact)
        Files.write(installed, "corrupt".toByteArray())
        assertContentEquals(bytes, Files.readAllBytes(cache.prepare(artifact)))
        assertEquals(2, downloads)
    }

    @Test
    fun `concurrent callers share one successful download`() = runTest {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var downloads = 0
        val cache = cache { target ->
            downloads++
            started.complete(Unit)
            finish.await()
            Files.write(target, bytes, CREATE_NEW)
        }
        val first = async { cache.prepare(artifact) }
        started.await()
        val second = async { cache.prepare(artifact) }
        finish.complete(Unit)
        assertEquals(first.await(), second.await())
        assertEquals(1, downloads)
    }

    @Test
    fun `cancelling a waiter does not cancel the active download`() = runTest {
        val finish = CompletableDeferred<Unit>()
        var downloads = 0
        val cache = cache { target ->
            downloads++
            finish.await()
            Files.write(target, bytes, CREATE_NEW)
        }
        val first = async { cache.prepare(artifact) }
        runCurrent()
        val waiter = launch { cache.prepare(artifact) }
        runCurrent()
        waiter.cancelAndJoin()
        finish.complete(Unit)
        assertContentEquals(bytes, Files.readAllBytes(first.await()))
        assertEquals(1, downloads)
    }

    @Test
    fun `cancelled download removes partial file and permits explicit retry`() = runTest {
        val started = CompletableDeferred<Path>()
        val cache = cache { target ->
            Files.write(target, bytes, CREATE_NEW)
            started.complete(target)
            awaitCancellation()
        }
        val job = launch { cache.prepare(artifact) }
        val partial = started.await()
        job.cancelAndJoin()
        assertFalse(Files.exists(partial))
        val retry = cache { target -> Files.write(target, bytes, CREATE_NEW) }.prepare(artifact)
        assertContentEquals(bytes, Files.readAllBytes(retry))
    }

    @Test
    fun `bad downloaded bytes are never published even from a faulty downloader`() = runTest {
        val cache = cache { target -> Files.write(target, "bad".toByteArray(), CREATE_NEW) }
        assertReleaseFailure(ReleaseFailure.ChecksumMismatch) { cache.prepare(artifact) }
        Files.walk(workspace).use { paths ->
            assertTrue(
                paths.noneMatch {
                    it.fileName.toString().startsWith(
                        ".part-",
                    ) || it.fileName.toString() == artifact.fileName
                },
            )
        }
    }

    @Test
    fun `cache file symlink never reads or modifies its target`() = runTest {
        val external = workspace.resolve("external.jar")
        Files.write(external, bytes)
        val directory = workspace.resolve("artifacts").resolve(request.sha256)
        Files.createDirectories(directory)
        Files.createSymbolicLink(directory.resolve(artifact.fileName), external)
        assertReleaseFailure(ReleaseFailure.Storage) { cache { error("Must not download") }.prepare(artifact) }
        assertContentEquals(bytes, Files.readAllBytes(external))
    }

    @Test
    fun `digest directory symlink cannot redirect publication`() = runTest {
        val external = Files.createDirectory(workspace.resolve("external"))
        val root = Files.createDirectory(workspace.resolve("artifacts"))
        Files.createSymbolicLink(root.resolve(request.sha256), external)
        assertReleaseFailure(ReleaseFailure.Storage) { cache { error("Must not download") }.prepare(artifact) }
        Files.list(external).use { assertEquals(0L, it.count()) }
    }

    @Test
    fun `artifact identity rejects paths and malformed checksums`() {
        listOf("../a", "/a", "a/b", "a\\b", ".", "..").forEach { name ->
            assertFailsWith<IllegalArgumentException> { ReleaseArtifact(name, request) }
        }
        assertFailsWith<IllegalArgumentException> { ReleaseArtifact("a.jar", request.copy(sha256 = "../bad")) }
    }

    private fun TestScope.cache(write: suspend (Path) -> Unit): DiskVerifiedArtifactCache {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        }
        val downloader = object : VerifiedReleaseDownloader {
            override suspend fun download(
                request: ReleaseDownload,
                target: Path,
                progress: suspend (Long, Long?) -> Unit,
            ) {
                write(target)
                progress(bytes.size.toLong(), bytes.size.toLong())
            }
        }
        return DiskVerifiedArtifactCache(workspace.resolve("artifacts"), downloader, dispatchers)
    }
}
