package io.aequicor.heartbeat.feature.plantumlsupport.impl

import io.aequicor.heartbeat.core.network.release.ReleaseArtifact
import io.aequicor.heartbeat.core.network.release.VerifiedArtifactCache
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.PlantUmlRuntime
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.bundledPlantUmlJar
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.plantUmlArtifact
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.validatePlantUmlRuntimeJar
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.worker.plantUmlWorkerClasspath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlantUmlRuntimeTest {
    @Test
    fun `callers share preparation and a cancelled viewport does not cancel its download`() = runTest {
        val release = CompletableDeferred<Path>()
        var requests = 0
        val cache = object : VerifiedArtifactCache {
            override suspend fun prepare(artifact: ReleaseArtifact, progress: suspend (Long, Long?) -> Unit): Path {
                requests++
                assertEquals(setOf("repo.maven.apache.org"), artifact.download.allowedHosts)
                assertTrue(artifact.download.url.endsWith(artifact.fileName))
                return release.await()
            }
        }
        val runtime = PlantUmlRuntime(cache, backgroundScope, StandardTestDispatcher(testScheduler), bundled = { null })
        val first = async { runtime.prepare() }
        val second = async { runtime.prepare() }
        testScheduler.runCurrent()
        assertEquals(1, requests)
        first.cancelAndJoin()
        assertFalse(release.isCancelled)
        val jar = Path.of("verified.jar")
        release.complete(jar)
        assertEquals(jar, second.await())
        assertEquals(jar, runtime.file())
        assertEquals(jar, runtime.prepare())
        assertEquals(2, requests)
    }

    @Test
    fun `a failed download permits a later preparation attempt`() = runTest {
        var requests = 0
        val jar = Path.of("verified.jar")
        val cache = object : VerifiedArtifactCache {
            override suspend fun prepare(artifact: ReleaseArtifact, progress: suspend (Long, Long?) -> Unit): Path {
                if (++requests == 1) error("offline")
                return jar
            }
        }
        val app = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext.job))
        val runtime = PlantUmlRuntime(cache, app, StandardTestDispatcher(testScheduler), bundled = { null })
        assertFailsWith<IllegalStateException> { runtime.prepare() }
        assertEquals(jar, runtime.prepare())
        assertEquals(2, requests)
    }

    @Test
    fun `a bundled jar that does not match the pin gives way to the verified download`() = runTest {
        val repackaged = Files.createTempFile("plantuml-repackaged", ".jar")
        try {
            Files.write(repackaged, byteArrayOf(1, 2, 3))
            val verified = Path.of("verified.jar")
            var requests = 0
            val cache = object : VerifiedArtifactCache {
                override suspend fun prepare(
                    artifact: ReleaseArtifact,
                    progress: suspend (Long, Long?) -> Unit,
                ): Path {
                    requests++
                    return verified
                }
            }
            val runtime = PlantUmlRuntime(
                cache,
                backgroundScope,
                StandardTestDispatcher(testScheduler),
                bundled = { repackaged },
            )
            assertEquals(verified, runtime.prepare())
            assertEquals(1, requests)
        } finally {
            Files.deleteIfExists(repackaged)
        }
    }

    @Test
    fun `the Gradle dependency matches the official lock and needs no download`() = runTest {
        val jar = assertNotNull(bundledPlantUmlJar())
        val cache = object : VerifiedArtifactCache {
            override suspend fun prepare(artifact: ReleaseArtifact, progress: suspend (Long, Long?) -> Unit): Path =
                error("Bundled development dependency must not download")
        }
        val runtime = PlantUmlRuntime(cache, backgroundScope, StandardTestDispatcher(testScheduler))
        assertEquals(jar, runtime.prepare())
        assertEquals(Files.size(jar), plantUmlArtifact().download.size)
        assertTrue(jar.toString() in plantUmlWorkerClasspath(jar).split(File.pathSeparator))
    }

    @Test
    fun `the worker rejects a replaced external jar before loading classes`() {
        val file = Files.createTempFile("plantuml-replaced-", ".jar")
        try {
            RandomAccessFile(file.toFile(), "rw").use { it.setLength(assertNotNull(plantUmlArtifact().download.size)) }
            val failure = assertFailsWith<IllegalArgumentException> { validatePlantUmlRuntimeJar(file) }
            assertEquals("Invalid PlantUML runtime checksum", failure.message)
        } finally {
            Files.delete(file)
        }
    }
}
