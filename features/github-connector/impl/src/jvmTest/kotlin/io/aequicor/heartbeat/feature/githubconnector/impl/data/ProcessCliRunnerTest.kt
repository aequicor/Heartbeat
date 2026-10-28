package io.aequicor.heartbeat.feature.githubconnector.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnectorFailure
import io.aequicor.heartbeat.feature.githubconnector.impl.domain.GitHubCliException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ProcessCliRunnerTest {
    private val dispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Default
        override val io: CoroutineDispatcher = Dispatchers.IO
    }
    private val isPosix = !System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("windows")

    @Test
    fun `output environment and exit code reach the caller and inherited tokens do not`() = runBlocking {
        if (!isPosix) return@runBlocking
        val result = ProcessCliRunner(dispatchers, 10.seconds).run(
            listOf("sh", "-c", "printf '%s|%s' \"\$GH_TOKEN\" \"\$GH_PROMPT_DISABLED\"; exit 3"),
            mapOf("GH_TOKEN" to "child-token"),
            mergeError = false,
        )
        assertEquals(3, result.exitCode)
        assertEquals("child-token|1", result.output)
    }

    @Test
    fun `stderr is merged only on request`() = runBlocking {
        if (!isPosix) return@runBlocking
        val command = listOf("sh", "-c", "echo out; echo err >&2")
        val merged = ProcessCliRunner(dispatchers, 10.seconds).run(command, emptyMap(), mergeError = true)
        val plain = ProcessCliRunner(dispatchers, 10.seconds).run(command, emptyMap(), mergeError = false)
        assertTrue("err" in merged.output)
        assertEquals("out\n", plain.output)
    }

    @Test
    fun `a hanging cli becomes a failed step instead of a cancellation`() = runBlocking {
        if (!isPosix) return@runBlocking
        val error = assertFailsWith<GitHubCliException> {
            ProcessCliRunner(dispatchers, 300.milliseconds).run(listOf("sh", "-c", "sleep 30"), emptyMap(), false)
        }
        assertEquals(GitHubConnectorFailure.Failed, error.failure)
    }
}
