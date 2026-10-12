package io.aequicor.heartbeat.feature.researchchat.impl.data

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.attachments.api.AttachmentDescriptor
import io.aequicor.heartbeat.feature.attachments.api.AttachmentId
import io.aequicor.heartbeat.feature.attachments.api.AttachmentInput
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsCatalog
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsIntent
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsMachineKey
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsOutput
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsState
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MachineResearchAttachmentsTest {
    @Test
    fun `abandoned import cancels only its own attachment request`() = runTest {
        val machine = AttachmentsMachine()
        val attachments = MachineResearchAttachments(Registry(machine), UnusedCatalog)
        val input = AttachmentInput.Bytes("source.pdf", "application/pdf", byteArrayOf(1))
        val importing = async { attachments.import(listOf(input), LegacyResearchSupport) }
        runCurrent()
        val request = (machine.sent.single() as AttachmentsIntent.Public.Import).requestId
        importing.cancelAndJoin()
        assertEquals(AttachmentsIntent.Public.Cancel(request), machine.sent.last())
        assertEquals(2, machine.sent.size)
    }

    private class AttachmentsMachine : MachineRef<AttachmentsState, AttachmentsIntent.Public, AttachmentsOutput> {
        override val name = "attachments"
        override val state = MutableStateFlow<AttachmentsState>(AttachmentsState.Ready)
        override val outputs = MutableSharedFlow<AttachmentsOutput>()
        val sent = mutableListOf<AttachmentsIntent.Public>()

        override suspend fun send(intent: AttachmentsIntent.Public): SendResult {
            sent += intent
            return SendResult.Accepted
        }
    }

    private class Registry(private val machine: AttachmentsMachine) : MachineRegistry {
        @Suppress("UNCHECKED_CAST") // This fixture registers only the attachments machine.
        override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
            key: MachineKey<S, I, P, E, O>,
        ): MachineRef<S, P, O>? {
            check(key == AttachmentsMachineKey)
            return machine as MachineRef<S, P, O>
        }

        override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
            key: MachineKey<S, I, P, E, O>,
        ): StateFlow<MachineRef<S, P, O>?> = MutableStateFlow(find(key))

        override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
            key: MachineKey<S, I, P, E, O>,
            intent: P,
        ): SendResult = requireNotNull(find(key)).send(intent)
    }

    private object UnusedCatalog : AttachmentsCatalog {
        override suspend fun get(id: AttachmentId): AttachmentDescriptor? = error("Unused")
        override fun observe(ids: List<AttachmentId>): Flow<List<AttachmentDescriptor>> = error("Unused")
    }
}
