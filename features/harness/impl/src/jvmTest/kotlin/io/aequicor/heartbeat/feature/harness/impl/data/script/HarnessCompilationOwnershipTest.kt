package io.aequicor.heartbeat.feature.harness.impl.data.script

import io.aequicor.heartbeat.feature.harness.impl.domain.script.CompiledHarnessCode
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class HarnessCompilationOwnershipTest {
    @Test
    fun `agent compilation precedes waiting background work`() = runTest {
        val queue = HarnessCompilationQueue()
        val gate = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val first = async {
            queue.run(false) {
                gate.await()
                order.add("first")
            }
        }
        runCurrent()
        val background = async { queue.run(false) { order.add("background") } }
        val agent = async { queue.run(true) { order.add("agent") } }
        runCurrent()
        gate.complete(Unit)
        first.await()
        background.await()
        agent.await()
        assertEquals(listOf("first", "agent", "background"), order)
    }

    @Test
    fun `cancelled queued request neither enters compiler nor consumes next permit`() = runTest {
        val queue = HarnessCompilationQueue()
        val gate = CompletableDeferred<Unit>()
        val first = async { queue.run(false) { gate.await() } }
        runCurrent()
        val cancelled = async { queue.run(true) { error("Cancelled request entered compiler") } }
        runCurrent()
        cancelled.cancelAndJoin()
        val next = async { queue.run(false) { "ready" } }
        gate.complete(Unit)
        first.await()
        assertEquals("ready", next.await())
    }

    @Test
    fun `abandoned compilation releases both already published and future artifacts`() {
        val early = TestCode()
        val late = TestCode()
        HarnessCompilationTicket().apply {
            publish(success(early))
            drop()
            drop()
        }
        HarnessCompilationTicket().apply {
            drop()
            publish(success(late))
            drop()
        }
        assertEquals(1, early.closes)
        assertEquals(1, late.closes)
    }

    @Test
    fun `claimed artifact belongs only to caller`() {
        val code = TestCode()
        val ticket = HarnessCompilationTicket()
        val result = success(code)
        ticket.publish(result)
        assertSame(result, ticket.claim())
        ticket.drop()
        assertEquals(0, code.closes)
        code.close()
        assertEquals(1, code.closes)
    }

    private fun success(code: TestCode) = HarnessCompilationResult.Success(code, emptyList())
}

private class TestCode : CompiledHarnessCode {
    var closes = 0
    override val kind = HarnessCodeKind.Script
    override fun retain(): CompiledHarnessCode = this
    override fun close() {
        closes++
    }
}
