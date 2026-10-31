package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekConfiguration
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeepSeekStartupTest {
    private val host = DeepSeekHost(
        osName = "Windows 11",
        path = "C:\\bin",
        userHome = "C:\\Users\\u",
        localAppData = "C:\\Users\\u\\AppData\\Local",
    )

    @Test
    fun `explicit executable wins over the system command`() {
        val startup = resolveDeepSeekStartup(DeepSeekConfiguration(executable = "C:\\tools\\dsh.cmd"), host)
        assertEquals("C:\\tools\\dsh.cmd", startup.executable)
        assertFalse(startup.isRunnable)
    }

    @Test
    fun `environment pins the sandbox floor and home directory`() {
        val env = dshEnvironment("/tmp/dsh-home")
        assertEquals("workspace-write", env["DSH_PERMISSION_MODE"])
        assertEquals("/tmp/dsh-home", env["DSH_HOME"])
    }

    @Test
    fun `system dsh is resolved from PATH`() {
        val dir = Files.createTempDirectory("dsh-path").toFile()
        val exe = Files.createFile(dir.toPath().resolve("dsh.cmd")).toFile()
        val windows = DeepSeekHost(
            osName = "Windows 11",
            path = dir.path,
            userHome = "C:\\Users\\u",
            localAppData = "C:\\Users\\u\\AppData\\Local",
            isExecutable = { it.isFile },
        )
        val startup = resolveDeepSeekStartup(DeepSeekConfiguration(), windows)
        assertEquals(exe.path, startup.executable)
        assertTrue(startup.isRunnable)
    }
}
