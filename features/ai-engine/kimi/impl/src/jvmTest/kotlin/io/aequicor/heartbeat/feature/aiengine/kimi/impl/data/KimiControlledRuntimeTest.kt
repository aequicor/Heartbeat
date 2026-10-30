package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KimiControlledRuntimeTest {
    @Test
    fun `missing bundle never falls back to installed CLI`() {
        assertFailsWith<EngineException> { kimiControlledExecutable(null) }
    }

    @Test
    fun `packaged executable requires pinned provenance and matching bytes`() {
        val directory = Files.createTempDirectory("heartbeat-kimi-runtime").toFile()
        try {
            val runtime = directory.resolve("kimi").apply { mkdirs() }
            val executable = runtime.resolve("kimi.exe").apply { writeText("fake native runtime") }
            executable.setExecutable(true)
            val hash = MessageDigest.getInstance("SHA-256").digest(executable.readBytes())
                .joinToString("") { "%02x".format(it) }
            runtime.resolve("runtime.properties").writeText(
                "version=2.4.0-hb5\nsourceCommit=f26e4d91ef8d5d233f6c20b42a150f96924d4f5e\n" +
                    "permissionGate=1\nsha256=$hash\n",
            )
            assertEquals(executable.absolutePath, kimiControlledExecutable(directory.path, isWindows = true))
            executable.appendText("tampered")
            assertFailsWith<EngineException> { kimiControlledExecutable(directory.path, isWindows = true) }
        } finally {
            directory.deleteRecursively()
        }
    }
}
