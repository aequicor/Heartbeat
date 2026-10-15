package io.aequicor.heartbeat.feature.harness.impl.data.script

import io.aequicor.heartbeat.feature.harness.impl.data.script.worker.verifiedCompilerFiles
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HarnessCompilerBootstrapTest {
    @Test
    fun `worker accepts exactly the original pinned compiler dependency set`() {
        assertEquals(KotlinVersion.CURRENT.toString(), HarnessCompilerArtifacts.VERSION)
        val bundled = checkNotNull(HarnessCompilerArtifacts.bundled())

        assertEquals(bundled, verifiedCompilerFiles(bundled.map(Path::toString)))
    }

    @Test
    fun `worker rejects incomplete reordered and relative dependency lists`() {
        val bundled = checkNotNull(HarnessCompilerArtifacts.bundled()).map(Path::toString)

        assertFailsWith<IllegalArgumentException> { verifiedCompilerFiles(bundled.dropLast(1)) }
        assertFailsWith<IllegalArgumentException> { verifiedCompilerFiles(bundled.reversed()) }
        assertFailsWith<IllegalArgumentException> {
            verifiedCompilerFiles(listOf(Path.of(bundled.first()).fileName.toString()) + bundled.drop(1))
        }
    }

    @Test
    fun `worker rejects replaced bytes even when the pinned filename and size match`() {
        val directory = Files.createTempDirectory("harness-bootstrap-")
        val bundled = checkNotNull(HarnessCompilerArtifacts.bundled())
        try {
            val replacement = directory.resolve(bundled.last().fileName)
            val bytes = Files.readAllBytes(bundled.last())
            bytes[0] = (bytes[0].toInt() xor 1).toByte()
            Files.write(replacement, bytes)

            assertFailsWith<IllegalArgumentException> {
                verifiedCompilerFiles((bundled.dropLast(1) + replacement).map(Path::toString))
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
