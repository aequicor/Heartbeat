package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KimiControlledRuntimeTest {
    @Test
    fun `independent release pin binds digest to exact target`() {
        val hash = "56da8b554a4de311e80d8642d1c85bc6ec2fe2b73d65e1e2e6a0603959b24e00"
        val known = KimiControlledRuntime("unused", hash, "darwin-arm64")
        assertTrue(isKnownKimiRelease(known))
        assertFalse(isKnownKimiRelease(known.copy(target = "darwin-x64")))
        assertFalse(isKnownKimiRelease(known.copy(target = "unknown")))
        assertFalse(isKnownKimiRelease(known.copy(fingerprint = "0".repeat(64))))
    }

    @Test
    fun `missing bundle never falls back to installed CLI`() {
        assertFailsWith<EngineException> { kimiControlledExecutable(null) }
    }

    @Test
    fun `packaged executable checks pin metadata and matching bytes`() {
        val directory = Files.createTempDirectory("heartbeat-kimi-runtime").toFile()
        try {
            val runtime = directory.resolve("kimi").apply { mkdirs() }
            val executable = runtime.resolve("kimi.exe").apply { writeText("fake native runtime") }
            executable.setExecutable(true)
            val hash = MessageDigest.getInstance("SHA-256").digest(executable.readBytes())
                .joinToString("") { "%02x".format(it) }
            runtime.resolve("runtime.properties").writeText(
                "version=2.4.0-hb7\nsourceCommit=f26e4d91ef8d5d233f6c20b42a150f96924d4f5e\n" +
                    "permissionGate=1\nsha256=$hash\ntarget=${kimiRuntimeTarget()}\n",
            )
            assertEquals(executable.absolutePath, kimiControlledExecutable(directory.path, isWindows = true))
            executable.appendText("tampered")
            assertFailsWith<EngineException> { kimiControlledExecutable(directory.path, isWindows = true) }
        } finally {
            directory.deleteRecursively()
        }
    }
}
