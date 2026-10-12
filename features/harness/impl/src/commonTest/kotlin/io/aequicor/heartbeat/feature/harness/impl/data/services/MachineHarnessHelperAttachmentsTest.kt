package io.aequicor.heartbeat.feature.harness.impl.data.services

import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.harness.api.HarnessAttachmentWrite
import io.aequicor.heartbeat.feature.harness.api.HarnessEntry
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessRejection
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.runtimeRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessHelperBinding
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MachineHarnessHelperAttachmentsTest {
    private val harness = runtimeRequest(1).harness
    private val ready = HarnessState.Ready(listOf(HarnessEntry(harness)), isRuntimeAvailable = true)
    private val attached = ready.copy(attachments = mapOf(dispatchSession to setOf(harness.id)))
    private val machine = SchedulerAccessMachine(ready)
    private val registry = SchedulerAccessRegistry().apply { library.value = machine }
    private val toggles = SchedulerAccessToggles()
    private val port = MachineHarnessHelperAttachments(registry, toggles)
    private val binding = HarnessHelperBinding(
        HelperId("helper"),
        ActionId("owner"),
        harness.id,
        RequestId("attach"),
        dispatchSession,
    )

    @Test
    fun `pending attachment is not proof and lost durable acknowledgement recovers from committed state`() = runTest {
        machine.state.value = ready.copy(
            attachmentWrite = HarnessAttachmentWrite(
                binding.attachRequest,
                harness.id,
                dispatchSession,
                true,
                attached.attachments,
            ),
        )
        val result = async { port.ensureAttached(binding) }
        runCurrent()
        assertFalse(result.isCompleted)
        assertTrue(machine.sent.isEmpty())
        machine.state.value = attached
        assertTrue(result.await())
        assertTrue(port.ensureAttached(binding))
        assertTrue(machine.sent.isEmpty())
    }

    @Test
    fun `subscription precedes send and committed state recovers a lost output`() = runTest {
        machine.onSend = {
            assertEquals(HarnessIntent.Public.Attach(binding.attachRequest, harness.id, dispatchSession), it)
            assertEquals(1, machine.outputs.subscriptionCount.value)
            machine.outputs.emit(HarnessOutput.StorageFailed(RequestId("unrelated")))
            machine.state.value = attached
            SendResult.Accepted
        }
        assertTrue(port.ensureAttached(binding))
        assertEquals(0, machine.outputs.subscriptionCount.value)
        assertEquals(1, machine.sent.size)
    }

    @Test
    fun `limit and storage failure refuse before any native work and busy retries are bounded`() = runTest {
        for (output in listOf(
            HarnessOutput.Rejected(binding.attachRequest, HarnessRejection.Limit),
            HarnessOutput.StorageFailed(binding.attachRequest),
        )) {
            machine.onSend = {
                machine.outputs.emit(output)
                SendResult.Accepted
            }
            assertFalse(port.ensureAttached(binding))
        }
        machine.sent.clear()
        machine.onSend = {
            machine.outputs.emit(HarnessOutput.Rejected(binding.attachRequest, HarnessRejection.Busy))
            SendResult.Accepted
        }
        assertFalse(port.ensureAttached(binding))
        assertEquals(3, machine.sent.size)
    }

    @Test
    fun `off or replacement after send cannot turn old machine acknowledgement into permission`() = runTest {
        machine.onSend = {
            machine.state.value = attached
            toggles.enabled.value = false
            SendResult.Accepted
        }
        assertFalse(port.ensureAttached(binding))
        machine.state.value = ready
        toggles.enabled.value = true
        machine.onSend = {
            machine.state.value = attached
            registry.library.value = SchedulerAccessMachine(attached)
            SendResult.Accepted
        }
        assertFalse(port.ensureAttached(binding))
    }

    @Test
    fun `caller cancellation removes receipt subscriptions and an unavailable library cannot attach`() = runTest {
        machine.onSend = { SendResult.Accepted }
        val result = async { port.ensureAttached(binding) }
        runCurrent()
        result.cancel()
        assertFailsWith<CancellationException> { result.await() }
        assertEquals(0, machine.outputs.subscriptionCount.value)
        machine.sent.clear()
        machine.state.value = ready.copy(isSuspended = true)
        assertFalse(port.ensureAttached(binding))
        assertTrue(machine.sent.isEmpty())
    }
}
