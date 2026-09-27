package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeConfiguration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse

class ClaudeProcessTest {
    @Test
    fun `cancellation terminates a native child blocked on stdin`() = runTest {
        val program = Files.createTempFile("claude-child", ".java")
        Files.writeString(program, CHILD_PROGRAM)
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main = testDispatcher
            override val default = testDispatcher
            override val io = Dispatchers.IO
        }
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val transport = ProcessClaudeTransport(dispatchers, ClaudeConfiguration(executable = java))
        val started = CompletableDeferred<Long>()
        try {
            val task = async {
                transport.run(listOf(program.toString()), closeInput = false) { line ->
                    started.complete(line.toLong())
                    false
                }
            }
            val pid = started.await()
            task.cancelAndJoin()
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        } finally {
            Files.deleteIfExists(program)
        }
    }
}

private const val CHILD_PROGRAM = """
class Child {
    public static void main(String[] args) throws Exception {
        System.out.println(ProcessHandle.current().pid());
        System.out.flush();
        System.in.read();
    }
}
"""
