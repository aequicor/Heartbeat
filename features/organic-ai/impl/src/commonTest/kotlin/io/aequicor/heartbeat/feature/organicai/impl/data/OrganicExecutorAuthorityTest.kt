package io.aequicor.heartbeat.feature.organicai.impl.data

import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiState
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismStatus
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganismJournal
import io.aequicor.heartbeat.feature.organicai.impl.domain.cellExecution
import io.aequicor.heartbeat.feature.organicai.impl.organism
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrganicExecutorAuthorityTest {
    @Test
    fun `live and durable state must independently authorize the same incarnation`() = runTest {
        val original = organism()
        val binding = original.cellExecution(CellId.ZYGOTE)!!
        val machine = OrganismAgentToolsTest.SpecMachine(original)
        val journal = Journal(original)
        val toggles = OrganismAgentToolsTest.Toggles()
        val authority = OrganicExecutorAuthority(journal, lazy { machine }, toggles)
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
