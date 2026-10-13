package io.aequicor.heartbeat.feature.serviceasui.api

import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiIntent.Internal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class ServiceUiReviewedWriteTest {
    @Test
    fun `delete and recreate cannot reuse a review of surface revision one`() {
        val original = UiLibrary().applied(put(0)).library
        val recreated = original.applied(UiWriteOp.Delete(Content.id, 1)).library.applied(put(0)).library
        assertEquals(1L, recreated[Content.id]?.revision)
        assertEquals(original.revision + 2, recreated.revision)
        val ready = ServiceUiState.Ready(library = recreated)

        for (op in listOf(put(1), UiWriteOp.Delete(Content.id, 1))) {
            val write = UiWrite("reviewed", UiWriteOrigin.Agent, op, expectedLibraryRevision = original.revision)
            ServiceUiMachineSpec.assertTransition(
                from = ready,
                intent = Internal.Edit(write),
                to = ready,
                outputs = listOf(ServiceUiOutput.EditRejected(write.id, UiEditRejection.Conflict)),
            )
        }
    }

    @Test
    fun `review of the current library accepts put and delete before persisting`() {
        val library = UiLibrary().applied(put(0)).library
        for (op in listOf(put(1), UiWriteOp.Delete(Content.id, 1))) {
            assertAccepted(library, UiWrite("reviewed", UiWriteOrigin.Agent, op, library.revision))
        }
    }

    @Test
    fun `ordinary callers can omit the library precondition`() {
        val library = UiLibrary().applied(put(0)).library
        for (op in listOf(put(1), UiWriteOp.Delete(Content.id, 1))) {
            assertAccepted(library, UiWrite("settings", UiWriteOrigin.User, op))
        }
    }

    private fun assertAccepted(library: UiLibrary, write: UiWrite) {
        val ready = ServiceUiState.Ready(library = library)
        val result = library.applied(write.op)
        val saving = ready.copy(write = UiPendingWrite(write, result))
        ServiceUiMachineSpec.assertTransition(
            from = ready,
            intent = Internal.Edit(write),
            to = saving,
            effects = listOf(ServiceUiEffect.Persist(write.id, result.library)),
        )
        ServiceUiMachineSpec.assertTransition(
            from = saving,
            intent = Internal.Persisted(write.id),
            to = ready.copy(library = result.library),
            outputs = listOf(ServiceUiOutput.EditApplied(write.id, result.surface, result.removed)),
        )
    }

    private fun UiLibrary.applied(op: UiWriteOp) = assertIs<UiWriteResult.Applied>(apply(op))

    private fun put(expected: Long) = UiWriteOp.Put(Content, expected, null, At)

    private companion object {
        val At: Instant = Instant.fromEpochSeconds(1_700_000_000)
        val Content = UiSurfaceContent(
            owner = UiOwner("harness", "fitness"),
            name = UiSurfaceName("workout"),
            placement = UiPlacement.Card,
            title = "Workout",
            spec = """{"version":1}""",
        )
    }
}
