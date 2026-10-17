package io.aequicor.heartbeat.feature.organicai.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessAttachmentWrite
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessEntry
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessMachineKey
import io.aequicor.heartbeat.feature.harness.api.HarnessMutation
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessReceipt
import io.aequicor.heartbeat.feature.harness.api.HarnessRejection
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.ToolPolicySpec
import io.aequicor.heartbeat.feature.organicai.impl.session
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class MachineCellHarnessesTest {
    private val at = Instant.fromEpochMilliseconds(1_000)
    private val cell = session("cell")
    private val ready = HarnessState.Ready(listOf(HarnessEntry(harness("compose")), HarnessEntry(harness("verify"))))
    private val library = Library(ready)
    private val registry = Registry().apply { machine.value = library }
    private val toggles = Toggles()
    private val port = MachineCellHarnesses(registry, toggles)

    private fun harness(id: String, isEnabled: Boolean = true) = Harness(
        HarnessId(id), HarnessName(id), "", "", HarnessScope.Profile, isEnabled,
        emptyList(), ToolPolicySpec(), null, 0, at, at,
    )

    /** The library commits the attachment and reports it, as the real one does. */
    private fun commits() {
        library.onSend = { intent ->
            val attach = intent as HarnessIntent.Public.Attach
            val current = library.state.value as HarnessState.Ready
            val connected = current.attachments[attach.session].orEmpty() + attach.id
            library.state.value = current.copy(attachments = current.attachments + (attach.session to connected))
            library.outputs.emit(HarnessOutput.Attached(attach.requestId, attach.id, attach.session))
            SendResult.Accepted
        }
    }

    @Test
    fun `an empty choice needs neither the library nor the toggle`() = runTest {
        toggles.enabled.value = false
        registry.machine.value = null
        port.connect(cell, emptySet())
        assertTrue(library.sent.isEmpty())
    }

    @Test
    fun `a connection the session already has is not written again`() = runTest {
        library.state.value = ready.copy(attachments = mapOf(cell to setOf(HarnessId("compose"), HarnessId("verify"))))
        port.connect(cell, setOf("compose", "verify"))
        assertTrue(library.sent.isEmpty())
    }

    @Test
    fun `missing connections are written one after another and confirmed by their receipts`() = runTest {
        commits()
        port.connect(cell, setOf("verify", "compose"))
        assertEquals(
            listOf(HarnessId("compose"), HarnessId("verify")),
            library.sent.map { (it as HarnessIntent.Public.Attach).id },
        )
        assertEquals(setOf(cell), library.sent.map { (it as HarnessIntent.Public.Attach).session }.toSet())
        val committed = (library.state.value as HarnessState.Ready).attachments[cell]
        assertEquals(setOf(HarnessId("compose"), HarnessId("verify")), committed)
        port.connect(cell, setOf("compose", "verify"))
        assertEquals(2, library.sent.size)
    }

    @Test
    fun `a refused write fails the connection`() = runTest {
        library.onSend = { intent ->
            library.outputs.emit(HarnessOutput.Rejected(intent.requestId, HarnessRejection.Conflict))
            SendResult.Accepted
        }
        assertFailsWith<IllegalStateException> { port.connect(cell, setOf("compose")) }
        assertEquals(1, library.sent.size)
    }

    @Test
    fun `a failed storage write fails the connection`() = runTest {
        library.onSend = { intent ->
            library.outputs.emit(HarnessOutput.StorageFailed(intent.requestId))
            SendResult.Accepted
        }
        assertFailsWith<IllegalStateException> { port.connect(cell, setOf("compose")) }
    }

    @Test
    fun `a write the machine does not take fails the connection`() = runTest {
        library.onSend = { SendResult.Ignored }
        assertFailsWith<IllegalStateException> { port.connect(cell, setOf("compose")) }
    }

    @Test
    fun `a write nobody answers fails after its deadline`() = runTest {
        library.onSend = { SendResult.Accepted }
        val connecting = async { assertFailsWith<IllegalStateException> { port.connect(cell, setOf("compose")) } }
        runCurrent()
        assertFalse(connecting.isCompleted)
        advanceTimeBy(16.seconds)
        runCurrent()
        assertTrue(connecting.isCompleted)
        connecting.await()
    }

    @Test
    fun `a busy library is retried a bounded number of times`() = runTest {
        library.onSend = { intent ->
            library.outputs.emit(HarnessOutput.Rejected(intent.requestId, HarnessRejection.Busy))
            SendResult.Accepted
        }
        assertFailsWith<IllegalStateException> { port.connect(cell, setOf("compose")) }
        assertEquals(3, library.sent.size)
    }

    @Test
    fun `a write in flight is awaited before this one is sent`() = runTest {
        val flight = HarnessAttachmentWrite(
            RequestId("other"),
            HarnessId("verify"),
            session("elsewhere"),
            isAttached = true,
            attachments = emptyMap(),
        )
        library.state.value = ready.copy(attachmentWrite = flight)
        commits()
        val connecting = async { port.connect(cell, setOf("compose")) }
        runCurrent()
        assertTrue(library.sent.isEmpty())
        library.state.value = ready
        connecting.await()
        assertEquals(1, library.sent.size)
    }

    @Test
    fun `a missing or disabled harness fails without any write`() = runTest {
        assertFailsWith<IllegalStateException> { port.connect(cell, setOf("gone")) }
        library.state.value = HarnessState.Ready(listOf(HarnessEntry(harness("compose", isEnabled = false))))
        assertFailsWith<IllegalStateException> { port.connect(cell, setOf("compose")) }
        val receipt = HarnessReceipt(RequestId("remove"), HarnessId("compose"), 0, 1)
        val removal = HarnessMutation.Remove(receipt, harness("compose"))
        library.state.value = ready.copy(pending = mapOf(HarnessId("compose") to removal))
        assertFailsWith<IllegalStateException> { port.connect(cell, setOf("compose")) }
        assertTrue(library.sent.isEmpty())
    }

    @Test
    fun `a suspended or loading library fails without any write`() = runTest {
        library.state.value = ready.copy(isSuspended = true)
        assertFailsWith<IllegalStateException> { port.connect(cell, setOf("compose")) }
        library.state.value = HarnessState.Idle()
        assertFailsWith<IllegalStateException> { port.connect(cell, setOf("compose")) }
        assertTrue(library.sent.isEmpty())
    }

    @Test
    fun `a library that is off or not running fails the connection`() = runTest {
        toggles.enabled.value = false
        assertFailsWith<IllegalStateException> { port.connect(cell, setOf("compose")) }
        toggles.enabled.value = true
        registry.machine.value = null
        assertFailsWith<IllegalStateException> { port.connect(cell, setOf("compose")) }
        assertTrue(library.sent.isEmpty())
    }

    private class Library(initial: HarnessState) : MachineRef<HarnessState, HarnessIntent.Public, HarnessOutput> {
        override val name = "harness"
        override val state = MutableStateFlow(initial)
        override val outputs = MutableSharedFlow<HarnessOutput>()
        val sent = mutableListOf<HarnessIntent.Public>()
        var onSend: suspend (HarnessIntent.Public) -> SendResult = { error("unexpected write") }

        override suspend fun send(intent: HarnessIntent.Public): SendResult {
            sent += intent
            return onSend(intent)
        }
    }

    private class Registry : MachineRegistry {
        val machine = MutableStateFlow<Library?>(null)

        @Suppress("UNCHECKED_CAST") // The fixture publishes only the harness key.
        override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
            key: MachineKey<S, I, P, E, O>,
        ): MachineRef<S, P, O>? = when (key) {
            HarnessMachineKey -> machine.value as MachineRef<S, P, O>?
            else -> error("unexpected key")
        }

        @Suppress("UNCHECKED_CAST") // The only registration has the exact state/output types of its key.
        override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
            key: MachineKey<S, I, P, E, O>,
        ): StateFlow<MachineRef<S, P, O>?> = when (key) {
            HarnessMachineKey -> machine as StateFlow<MachineRef<S, P, O>?>
            else -> error("unexpected key")
        }

        override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
            key: MachineKey<S, I, P, E, O>,
            intent: P,
        ): SendResult = error("connections are written through the machine reference")
    }

    private class Toggles : FeatureToggles {
        val enabled = MutableStateFlow(true)

        @Suppress("UNCHECKED_CAST") // Only the boolean harness flag is read.
        override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> {
            check(toggle == HarnessEnabled)
            return enabled as Flow<T>
        }

        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = observe(toggle).first()
    }
}
