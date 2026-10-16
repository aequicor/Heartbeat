package io.aequicor.heartbeat.buildlogic

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopJdkModulesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `limited development and packaged runtimes expose physical memory management`() {
        val packaged = desktopJdkModules.filterNot { it == "jdk.compiler" || it == "jdk.attach" }
        listOf(desktopJdkModules, packaged).forEach { modules ->
            val output = temporaryFolder.newFile()
            val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
            val process = ProcessBuilder(
                File(System.getProperty("java.home"), "bin/$executable").absolutePath,
                "--limit-modules=${modules.joinToString(",")}",
                "--describe-module",
                "jdk.management",
            ).redirectErrorStream(true).redirectOutput(output).start()
            try {
                assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Runtime module probe timed out")
                val diagnostic = output.readText()
                assertEquals(0, process.exitValue(), diagnostic)
                assertTrue("exports com.sun.management" in diagnostic, diagnostic)
            } finally {
                if (process.isAlive) process.destroyForcibly().waitFor()
            }
        }
    }
}
