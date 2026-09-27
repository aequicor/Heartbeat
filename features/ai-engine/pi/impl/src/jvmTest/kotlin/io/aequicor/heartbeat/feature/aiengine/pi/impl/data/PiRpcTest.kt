package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.stream.Stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PiRpcTest {
    private val dispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Default
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
    }

    @Test
    fun `command timeout fails only the command and keeps the process running`() = runTest {
        val process = FakeProcess()
        val rpc = PiRpc(process, backgroundScope, dispatchers, {}, {}, commandTimeoutMillis = 50)
        val failure = assertFailsWith<EngineException> { withContext(Dispatchers.Default) { rpc.command("get_state") } }
        assertEquals(EngineFailure.Transport(TransportFailureReason.Timeout), failure.failure)
        assertTrue(rpc.isOpen)
        assertFalse(process.isDestroyed)
        rpc.close()
    }

    @Test
    fun `malformed lines and failing consumers do not stop draining`() = runTest {
        val process = FakeProcess()
        val settled = CompletableDeferred<JsonObject>()
        val rpc = PiRpc(
            process,
            backgroundScope,
            dispatchers,
            { record ->
                if (record.string("type") == "agent_start") error("consumer bug")
                settled.complete(record)
            },
            {},
        )
        process.emit("not json")
        process.emit("[1,2]")
        process.emit("""{"type":"agent_start"}""")
        process.emit("""{"type":"agent_settled"}""")
        assertEquals("agent_settled", awaitReal { settled.await() }.string("type"))
        assertTrue(rpc.isOpen)
        rpc.close()
    }

    @Test
    fun `end of output destroys the process and reports a crash`() = runTest {
        val process = FakeProcess()
        val failure = CompletableDeferred<EngineFailure>()
        val rpc = PiRpc(process, backgroundScope, dispatchers, {}, { failure.complete(it) })
        process.finishOutput()
        assertEquals(EngineFailure.Engine(EngineFailureReason.Crashed), awaitReal { failure.await() })
        assertFalse(rpc.isOpen)
        assertTrue(process.isDestroyed)
    }

    @Test
    fun `close fails pending commands and later commands immediately`() = runTest {
        val process = FakeProcess()
        val rpc = PiRpc(process, backgroundScope, dispatchers, {}, {})
        rpc.close()
        val failure = assertFailsWith<EngineException> { rpc.command("get_state") }
        assertIs<EngineFailure.Engine>(failure.failure)
        assertTrue(process.isDestroyed)
    }

    private suspend fun <T> awaitReal(block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(AWAIT_MILLIS) { block() } }

    private companion object {
        const val AWAIT_MILLIS = 5_000L
    }
}

private class FakeProcess : Process() {
    private val stdout = PipedInputStream(PIPE_SIZE)
    private val feed = PipedOutputStream(stdout)
    private val stdin = ByteArrayOutputStream()
    private val stderr = ByteArrayInputStream(ByteArray(0))

    @Volatile var isDestroyed = false

    fun emit(line: String) {
        feed.write((line + "\n").toByteArray())
        feed.flush()
    }

    fun finishOutput() = feed.close()

    override fun getOutputStream(): OutputStream = stdin
    override fun getInputStream(): InputStream = stdout
    override fun getErrorStream(): InputStream = stderr
    override fun waitFor(): Int = 0
    override fun exitValue(): Int = if (isDestroyed) 0 else throw IllegalThreadStateException()
    override fun isAlive(): Boolean = !isDestroyed
    override fun descendants(): Stream<ProcessHandle> = Stream.empty()
    override fun destroy() {
        isDestroyed = true
        feed.close()
    }

    private companion object {
        const val PIPE_SIZE = 65_536
    }
}
