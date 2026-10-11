package io.aequicor.heartbeat.feature.harness.impl.domain.authoring

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.impl.domain.script.CompiledHarnessCode
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationContext
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessScriptHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HarnessCodeChecksTest {
    private val host = CountingHost()
    private val checks = HarnessCodeChecks(host) { it }

    @Test
    fun `one request compiles once and later checks reuse the outcome`() = runTest {
        assertIs<CodeCheck.Compiled>(check("hooks"))
        assertIs<CodeCheck.Compiled>(check("hooks"))
        assertEquals(listOf("hooks"), host.compiled)
        assertEquals(1, host.closed)
        host.failing += "broken"
        assertIs<CodeCheck.Failed>(check("broken"))
        assertIs<CodeCheck.Failed>(check("broken"))
        assertEquals(listOf("hooks", "broken"), host.compiled)
    }

    @Test
    fun `a timeout is not remembered so a later check compiles again`() = runTest {
        host.timingOut += "slow"
        assertEquals(CodeCheck.TimedOut, check("slow"))
        host.timingOut.clear()
        assertIs<CodeCheck.Compiled>(check("slow"))
        assertEquals(listOf("slow", "slow"), host.compiled)
    }

    @Test
    fun `only the newest checks are remembered`() = runTest {
        (0..CODE_CHECK_MEMO).forEach { check("source $it") }
        check("source $CODE_CHECK_MEMO")
        assertEquals(CODE_CHECK_MEMO + 1, host.compiled.size)
        check("source 0")
        assertEquals(CODE_CHECK_MEMO + 2, host.compiled.size)
    }

    @Test
    fun `a slow compile does not hold back other checks`() = runTest {
        val gate = CompletableDeferred<Unit>()
        host.gates["slow"] = gate
        val slow = async { check("slow") }
        runCurrent()
        assertFalse(slow.isCompleted)
        assertIs<CodeCheck.Compiled>(check("fast"))
        gate.complete(Unit)
        assertIs<CodeCheck.Compiled>(slow.await())
        assertTrue(host.compiled.containsAll(listOf("slow", "fast")))
    }

    private suspend fun check(source: String) =
        checks.check(HarnessId("harness"), ItemId("item"), HarnessCodeKind.Script, source, isAgent = true)
}

private class CountingHost : HarnessScriptHost {
    val compiled = mutableListOf<String>()
    val failing = mutableSetOf<String>()
    val timingOut = mutableSetOf<String>()
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
    var closed = 0
    override val isAvailable = true

    override suspend fun compile(request: HarnessCompilationRequest): HarnessCompilationResult {
        compiled += request.source
        gates[request.source]?.await()
        return when (request.source) {
            in failing -> HarnessCompilationResult.Failure(emptyList())

            in timingOut -> HarnessCompilationResult.TimedOut

            else -> HarnessCompilationResult.Success(
                object : CompiledHarnessCode {
                    override val kind = HarnessCodeKind.Script
                    override fun retain() = this
                    override fun close() {
                        closed++
                    }
                },
                emptyList(),
            )
        }
    }

    override suspend fun evaluate(code: CompiledHarnessCode, context: HarnessEvaluationContext) =
        HarnessEvaluationResult.Unsupported

    override suspend fun removeCached(harness: HarnessId, item: ItemId) = Unit
}
