package io.aequicor.heartbeat.feature.organicai.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiState
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismStatus
import io.aequicor.heartbeat.feature.organicai.impl.TARGET
import io.aequicor.heartbeat.feature.organicai.impl.domain.MemoryNativeJournal
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicNativeAttempt
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicNativeClaim
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicNativeStage
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganismJournal
import io.aequicor.heartbeat.feature.organicai.impl.domain.cellExecution
import io.aequicor.heartbeat.feature.organicai.impl.organism
import io.aequicor.heartbeat.feature.organicai.impl.session
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrganicExecutorAuthorityTest {
    @Test
    fun `native send consumes only the current request with its frozen payload and model`() = runTest {
        val original = organism()
        val binding = original.cellExecution(CellId.ZYGOTE)!!
        val request = (original.cells.first().phase as CellPhase.Working).request
        val prompt = PromptRequest(request, listOf(ContentPart.Text("frozen")))
        val ref = session("native")
        val native = MemoryNativeJournal()
        native.seed(
            original.id,
            original.incarnation!!,
            OrganicNativeAttempt(
                OrganicNativeClaim("claim", binding.instance.id, binding, TARGET, null, request, "frozen"),
                OrganicNativeStage.Entered,
                session = ref,
                prepared = prompt,
            ),
        )
        val machine = OrganismAgentToolsTest.SpecMachine(original)
        val authority = OrganicExecutorAuthority(
            Journal(original),
            lazy { machine },
            OrganismAgentToolsTest.Toggles(),
            native,
        )
        assertTrue(authority.permitsPrompt(binding, ref, TARGET, prompt))
        assertFalse(authority.permitsPrompt(binding, ref, TARGET.copy(model = ModelId("changed")), prompt))
        assertFalse(
            authority.permitsPrompt(binding, ref, TARGET, prompt.copy(parts = listOf(ContentPart.Text("other")))),
        )
        val next = original.copy(
            cells = original.cells.map {
                if (it.id == CellId.ZYGOTE) {
                    it.copy(
                        phase = (it.phase as CellPhase.Working).copy(request = RequestId("next")),
                    )
                } else {
                    it
                }
            },
        )
        machine.state.value = OrganicAiState.Living(mapOf(next.id to next))
        assertFalse(authority.enterPrompt(binding, ref, request))
        machine.state.value = OrganicAiState.Living(mapOf(original.id to original))
        assertTrue(authority.enterPrompt(binding, ref, request))
        assertFalse(authority.enterPrompt(binding, ref, request))
    }

    @Test
    fun `live and durable state must independently authorize the same incarnation`() = runTest {
        val original = organism()
        val binding = original.cellExecution(CellId.ZYGOTE)!!
        val machine = OrganismAgentToolsTest.SpecMachine(original)
        val journal = Journal(original)
        val toggles = OrganismAgentToolsTest.Toggles()
        val authority = OrganicExecutorAuthority(journal, lazy { machine }, toggles, MemoryNativeJournal())
        assertTrue(authority.permits(binding))
        journal.record = null
        assertFalse(authority.permits(binding))
        journal.record = original.copy(incarnation = "replacement")
        assertFalse(authority.permits(binding))
        journal.record = original
        machine.state.value = OrganicAiState.Living(
            mapOf(original.id to original.copy(status = OrganismStatus.Aborted)),
        )
        assertFalse(authority.permits(binding))
        machine.state.value = OrganicAiState.Living(mapOf(original.id to original))
        journal.record = original.copy(status = OrganismStatus.Aborted)
        assertFalse(authority.permits(binding))
        journal.record = original
        toggles.isEnabled = false
        assertFalse(authority.permits(binding))
        toggles.isEnabled = true
        machine.state.value = OrganicAiState.Dormant
        assertFalse(authority.permits(binding))
    }

    private class Journal(var record: Organism?) : OrganismJournal {
        override suspend fun load(): List<Organism> = listOfNotNull(record)
        override suspend fun save(organism: Organism) {
            record = organism
        }
    }
}
