package io.aequicor.heartbeat.feature.aistudio.screenmodel

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioAppUpdateActivityTest {
    @Test
    fun ordinaryTurnsRequireConfirmation() {
        assertTrue(AiStudioScreenState(running = persistentSetOf("chat")).hasRunningAgentsForUpdate())
        assertFalse(AiStudioScreenState().hasRunningAgentsForUpdate())
    }

    @Test
    fun everyActiveOrganismSessionRequiresConfirmationWithoutAnOpenPane() {
        listOf(
            SubSessionStateUi.Germinating,
            SubSessionStateUi.Working,
            SubSessionStateUi.AwaitingUser,
            SubSessionStateUi.Judging,
        ).forEach { phase ->
            assertTrue(state(OrganismStatusUi.Developing, phase).hasRunningAgentsForUpdate(), phase.name)
        }
    }

    @Test
    fun stalledOrganismStillRequiresConfirmationWhileADescendantWorks() {
        assertTrue(state(OrganismStatusUi.Stalled, SubSessionStateUi.Working).hasRunningAgentsForUpdate())
    }

    @Test
    fun terminalOrganismsDoNotRequireConfirmationForStaleSessionPhases() {
        for (status in listOf(OrganismStatusUi.Completed, OrganismStatusUi.Aborted)) {
            assertFalse(state(status, SubSessionStateUi.Working).hasRunningAgentsForUpdate(), status.name)
        }
    }

    @Test
    fun inactiveOrganismSessionsDoNotRequireConfirmation() {
        listOf(
            SubSessionStateUi.Resting,
            SubSessionStateUi.Stalled,
            SubSessionStateUi.Completed,
            SubSessionStateUi.Died,
            SubSessionStateUi.Killed,
            SubSessionStateUi.Sentenced,
            SubSessionStateUi.Spared,
            SubSessionStateUi.Answered,
            SubSessionStateUi.Undecided,
        ).forEach { phase ->
            assertFalse(state(OrganismStatusUi.Stalled, phase).hasRunningAgentsForUpdate(), phase.name)
        }
    }

    @Test
    fun `unconfirmed cell release still blocks restart even when every cell is marked ended`() {
        for (status in listOf(OrganismStatusUi.Releasing, OrganismStatusUi.ReleaseUnconfirmed)) {
            assertTrue(state(status, SubSessionStateUi.Completed).hasRunningAgentsForUpdate())
            assertTrue(state(status, SubSessionStateUi.Died).hasRunningAgentsForUpdate())
        }
    }

    private fun state(status: OrganismStatusUi, phase: SubSessionStateUi) = AiStudioScreenState(
        organisms = persistentMapOf(
            "background-organism" to OrganismUi(
                status,
                persistentListOf(SubSessionUi("zygote", SubSessionKindUi.Zygote, "Agent", phase)),
            ),
        ),
    )
}
