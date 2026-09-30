package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
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
import java.util.concurrent.Executors
import java.util.stream.Stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PiRpcTest {
    @Test
    fun `command timeout fails only the command and keeps the process running`() = runRpcTest { dispatchers ->
        val process = FakeProcess()
        val rpc = PiRpc(process, backgroundScope, dispatchers, {}, {}, commandTimeoutMillis = 50)
        val failure = assertFailsWith<EngineException> { withContext(dispatchers.default) { rpc.command("get_state") } }
        assertEquals(EngineFailure.Transport(TransportFailureReason.Timeout), failure.failure)
        assertTrue(rpc.isOpen)
        assertFalse(process.isDestroyed)
        rpc.close()
    }

    @Test
    fun `malformed lines and failing consumers do not stop draining`() = runRpcTest { dispatchers ->
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
        assertEquals("agent_settled", awaitReal(dispatchers) { settled.await() }.string("type"))
        assertTrue(rpc.isOpen)
        rpc.close()
    }

    @Test
    fun `end of output destroys the process and reports a crash`() = runRpcTest { dispatchers ->
        val process = FakeProcess()
        val failure = CompletableDeferred<EngineFailure>()
        val rpc = PiRpc(process, backgroundScope, dispatchers, {}, { failure.complete(it) })
        process.finishOutput()
        assertEquals(EngineFailure.Engine(EngineFailureReason.Crashed), awaitReal(dispatchers) { failure.await() })
        assertFalse(rpc.isOpen)
        assertTrue(process.isDestroyed)
    }

    @Test
    fun `a crash fails in-flight commands with the crash`() = runRpcTest { dispatchers ->
        val process = FakeProcess()
        val rpc = PiRpc(process, backgroundScope, dispatchers, {}, {})
        val failure = async(dispatchers.default) { assertFailsWith<EngineException> { rpc.command("get_state") } }
        awaitReal(dispatchers) { while (process.written().isEmpty()) delay(POLL_MILLIS) }
        process.finishOutput()
        assertEquals(
            EngineFailure.Engine(EngineFailureReason.Crashed),
            awaitReal(dispatchers) { failure.await() }.failure,
        )
    }

    @Test
    fun `close fails pending commands and later commands immediately`() = runRpcTest { dispatchers ->
        val process = FakeProcess()
        val rpc = PiRpc(process, backgroundScope, dispatchers, {}, {})
        rpc.close()
        val failure = assertFailsWith<EngineException> { rpc.command("get_state") }
        assertIs<EngineFailure.Engine>(failure.failure)
        assertTrue(process.isDestroyed)
    }

    private fun runRpcTest(block: suspend TestScope.(DispatcherProvider) -> Unit) = runTest {
        RpcTestDispatchers().use { block(it) }
    }

    private suspend fun <T> awaitReal(dispatchers: DispatcherProvider, block: suspend () -> T): T =
        withContext(dispatchers.default) { withTimeout(AWAIT_MILLIS) { block() } }

    private companion object {
        const val AWAIT_MILLIS = 5_000L
        const val POLL_MILLIS = 10L
    }
}

/** Blocking pipes and native command deadlines use physical time, with a pool isClosed after each test. */
private class RpcTestDispatchers :
    DispatcherProvider,
    AutoCloseable {
    private val dispatcher = Executors.newCachedThreadPool { task ->
        Thread(task, "pi-rpc-test").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    override val main: CoroutineDispatcher = dispatcher
    override val io: CoroutineDispatcher = dispatcher
    override val default: CoroutineDispatcher = dispatcher

    override fun close() = dispatcher.close()
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

    fun written(): String = synchronized(stdin) { stdin.toString(Charsets.UTF_8) }

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
