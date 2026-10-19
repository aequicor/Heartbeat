package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePreparation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PiRuntimeDistributionTest {
    @Test
    fun `inspection never downloads and first use publishes all runtime assets`() = runTest {
        piTemporary { root ->
            val cache = PiArtifactFixture(
                piArchive(root, listOf(PiArchiveEntry("pi/pi", "exe"), PiArchiveEntry("pi/helper.wasm", "wasm"))),
            )
            val distribution = PiRuntimeDistribution(root.resolve("cache"), piRelease(), cache, piDispatchers())
            assertNull(distribution.executable())
            assertEquals(0, cache.calls)
            val progress = mutableListOf<EnginePreparation>()

            val executable = distribution.prepare { progress += it }

            assertEquals("exe", Files.readString(executable))
            assertEquals("wasm", Files.readString(executable.resolveSibling("helper.wasm")))
            assertTrue(Files.isExecutable(executable))
            assertEquals(executable, distribution.executable())
            assertEquals(1, cache.calls)
            assertTrue(EnginePreparation.Downloading(1, 2) in progress)
            assertEquals(EnginePreparation.Installing, progress.last())
        }
    }

    @Test
    fun `repeated launches verify the installed runtime without publishing preparation progress`() = runTest {
        piTemporary { root ->
            val cache = PiArtifactFixture(piArchive(root))
            val distribution = PiRuntimeDistribution(root.resolve("cache"), piRelease(), cache, piDispatchers())
            val installed = distribution.prepare {}
            val progress = mutableListOf<EnginePreparation>()

            repeat(3) {
                assertEquals(installed, distribution.prepare { progress += it })
            }

            assertEquals(emptyList(), progress)
            assertEquals(1, cache.calls)
        }
    }

    @Test
    fun `a new process reuses verified runtime offline and rebuilds a changed file`() = runTest {
        piTemporary { root ->
            val cache = PiArtifactFixture(piArchive(root))
            val storage = root.resolve("cache")
            val first = PiRuntimeDistribution(storage, piRelease(), cache, piDispatchers()).prepare {}
            cache.before = { error("offline cache reuse must not download") }
            val progress = mutableListOf<EnginePreparation>()
            val offline = PiRuntimeDistribution(storage, piRelease(), cache, piDispatchers()).prepare { progress += it }
            assertEquals(first, offline)
            assertEquals(1, cache.calls)
            assertEquals(emptyList(), progress)

            Files.writeString(first, "changed")
            cache.before = {}
            val repaired = PiRuntimeDistribution(storage, piRelease(), cache, piDispatchers()).prepare {}
            assertEquals("executable", Files.readString(repaired))
            assertTrue(first != repaired)
            assertEquals(2, cache.calls)
        }
    }

    @Test
    fun `cancelling a download leaves no active runtime and a retry can complete`() = runTest {
        piTemporary { root ->
            val cache = PiArtifactFixture(piArchive(root))
            val gate = CompletableDeferred<Unit>()
            cache.before = { gate.await() }
            val distribution = PiRuntimeDistribution(root.resolve("cache"), piRelease(), cache, piDispatchers())
            val downloading = launch { distribution.prepare {} }
            runCurrent()
            assertEquals(1, cache.calls)
            downloading.cancel()
            downloading.join()
            assertNull(distribution.executable())
            assertFalse(
                Files.exists(root.resolve("cache").resolve(piRelease().artifact.download.sha256).resolve("active")),
            )

            gate.complete(Unit)
            assertTrue(Files.isExecutable(distribution.prepare {}))
            assertEquals(2, cache.calls)
        }
    }

    @Test
    fun `a failed download is retryable and concurrent callers share one completed preparation`() = runTest {
        piTemporary { root ->
            val cache = PiArtifactFixture(piArchive(root))
            cache.before = { throw IOException("offline") }
            val distribution = PiRuntimeDistribution(root.resolve("cache"), piRelease(), cache, piDispatchers())
            assertFailsWith<IOException> { distribution.prepare {} }
            assertNull(distribution.executable())
            cache.before = {}
            val first = async { distribution.prepare {} }
            val second = async { distribution.prepare {} }
            assertEquals(first.await(), second.await())
            assertEquals(2, cache.calls)
        }
    }

    @Test
    fun `a concurrent caller reuses the completed installation without publishing progress`() = runTest {
        piTemporary { root ->
            val cache = PiArtifactFixture(piArchive(root))
            val gate = CompletableDeferred<Unit>()
            cache.before = { gate.await() }
            val distribution = PiRuntimeDistribution(root.resolve("cache"), piRelease(), cache, piDispatchers())
            val installingProgress = mutableListOf<EnginePreparation>()
            val waitingProgress = mutableListOf<EnginePreparation>()
            val installing = async { distribution.prepare { installingProgress += it } }
            runCurrent()
            val waiting = async { distribution.prepare { waitingProgress += it } }
            runCurrent()
            assertFalse(waiting.isCompleted)

            gate.complete(Unit)

            assertEquals(installing.await(), waiting.await())
            assertTrue(EnginePreparation.Downloading(1, 2) in installingProgress)
            assertEquals(EnginePreparation.Installing, installingProgress.last())
            assertEquals(emptyList(), waitingProgress)
            assertEquals(1, cache.calls)
        }
    }

    @Test
    fun `unexpected cache links cannot become executable runtime files`() = runTest {
        piTemporary { root ->
            val cache = PiArtifactFixture(piArchive(root))
            val storage = root.resolve("cache")
            val first = PiRuntimeDistribution(storage, piRelease(), cache, piDispatchers()).prepare {}
            val outside = Files.writeString(root.resolve("outside"), "outside")
            Files.delete(first)
            piTestSymbolicLink(first, outside)

            val repaired = PiRuntimeDistribution(storage, piRelease(), cache, piDispatchers()).prepare {}

            assertEquals("executable", Files.readString(repaired))
            assertEquals("outside", Files.readString(outside))
            assertEquals(2, cache.calls)
        }
    }

    @Test
    fun `the same process repairs removed or modified runtime files before another launch`() = runTest {
        piTemporary { root ->
            val cache = PiArtifactFixture(piArchive(root))
            val distribution = PiRuntimeDistribution(root.resolve("cache"), piRelease(), cache, piDispatchers())
            val first = distribution.prepare {}
            Files.writeString(first, "changed")
            val progress = mutableListOf<EnginePreparation>()
            val repaired = distribution.prepare { progress += it }
            assertEquals("executable", Files.readString(repaired))
            assertTrue(first != repaired)
            assertTrue(EnginePreparation.Downloading(1, 2) in progress)
            assertEquals(EnginePreparation.Installing, progress.last())
            Files.delete(repaired)
            progress.clear()
            assertEquals("executable", Files.readString(distribution.prepare { progress += it }))
            assertTrue(EnginePreparation.Downloading(1, 2) in progress)
            assertEquals(EnginePreparation.Installing, progress.last())
            assertEquals(3, cache.calls)
        }
    }

    @Test
    fun `a linked cache root or version directory never receives an installation`() = runTest {
        piTemporary { root ->
            val cache = PiArtifactFixture(piArchive(root))
            val outside = Files.createDirectory(root.resolve("outside"))
            val linkedRoot = piTestDirectoryLink(root.resolve("linked"), outside)
            assertFailsWith<IllegalArgumentException> {
                PiRuntimeDistribution(linkedRoot, piRelease(), cache, piDispatchers()).prepare {}
            }
            val storage = Files.createDirectory(root.resolve("cache"))
            piTestDirectoryLink(storage.resolve(piRelease().artifact.download.sha256), outside)
            assertFailsWith<IllegalArgumentException> {
                PiRuntimeDistribution(storage, piRelease(), cache, piDispatchers()).prepare {}
            }
            Files.list(outside).use { assertEquals(0L, it.count()) }
        }
    }

    @Test
    fun `an unreadable active pointer is treated as missing and repaired on first use`() = runTest {
        piTemporary { root ->
            val cache = PiArtifactFixture(piArchive(root))
            val storage = root.resolve("cache")
            PiRuntimeDistribution(storage, piRelease(), cache, piDispatchers()).prepare {}
            Files.write(storage.resolve(piRelease().artifact.download.sha256).resolve("active"), byteArrayOf(-1))
            val restarted = PiRuntimeDistribution(storage, piRelease(), cache, piDispatchers())
            assertNull(restarted.executable())
            assertEquals("executable", Files.readString(restarted.prepare {}))
            assertEquals(2, cache.calls)
        }
    }
}
