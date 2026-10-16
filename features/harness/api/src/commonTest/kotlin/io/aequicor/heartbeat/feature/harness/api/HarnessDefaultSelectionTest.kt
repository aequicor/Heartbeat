package io.aequicor.heartbeat.feature.harness.api

import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HarnessDefaultSelectionTest {
    @Test
    fun `default selection changes only after durable save and never changes existing attachments`() {
        val original = harness.copy(items = emptyList())
        var committed = ready.copy(
            harnesses = listOf(HarnessEntry(original)),
            attachments = mapOf(session to setOf(original.id)),
        )
        for (isDefault in listOf(true, false)) {
            val previous = committed.harnesses.single().harness
            val intent = HarnessIntent.Internal.Update(
                request,
                original.id,
                HarnessChange.DefaultForNewSessions(isDefault),
                previous.revision,
                HarnessAuthor.User,
                now,
            )
            val pending = committed.send(intent)
            val mutation = saveOf(pending)
            val candidate = previous.copy(isDefaultForNewSessions = isDefault, revision = previous.revision + 1)
            HarnessMachineSpec.assertTransition(
                committed,
                intent,
                pending,
                effects = listOf(HarnessEffect.Save(candidate, mutation.receipt)),
            )
            assertEquals(previous, pending.harnesses.single().harness)
            assertEquals(committed.attachments, pending.attachments)
            val updated = pending.send(HarnessIntent.Internal.Saved(mutation.receipt))
            HarnessMachineSpec.assertTransition(
                pending,
                HarnessIntent.Internal.Saved(mutation.receipt),
                updated,
                outputs = listOf(HarnessOutput.Updated(request, original.id)),
            )
            assertEquals(candidate, updated.harnesses.single().harness)
            assertEquals(committed.attachments, updated.attachments)
            committed = updated
        }
    }

    @Test
    fun `default selection rejects stale edits and retains committed preference after failed storage`() {
        val intent = HarnessIntent.Internal.Update(
            request,
            harness.id,
            HarnessChange.DefaultForNewSessions(true),
            harness.revision,
            HarnessAuthor.User,
            now,
        )
        ready.refuses(intent.copy(expectedRevision = 1), HarnessRejection.Conflict)
        val pending = ready.send(intent)
        pending.refuses(intent, HarnessRejection.Busy)
        val failed = ready.copy(writeGeneration = pending.writeGeneration)
        HarnessMachineSpec.assertTransition(
            pending,
            HarnessIntent.Internal.SaveFailed(saveOf(pending).receipt),
            failed,
            outputs = listOf(HarnessOutput.StorageFailed(request)),
        )
        assertFalse(failed.harnesses.single().harness.isDefaultForNewSessions)
    }

    @Test
    fun `default change reactivates the new library revision while retaining chat connections`() {
        val connected = ready.copy(
            harnesses = listOf(HarnessEntry(harness, mapOf(code.id to ItemStatus.Active(3)))),
            attachments = mapOf(session to setOf(harness.id)),
            activationGeneration = 3,
        )
        val pending = connected.send(
            HarnessIntent.Internal.Update(
                request,
                harness.id,
                HarnessChange.DefaultForNewSessions(true),
                harness.revision,
                HarnessAuthor.User,
                now,
            ),
        )
        val mutation = saveOf(pending)
        val candidate = harness.copy(isDefaultForNewSessions = true, revision = 1)
        assertEquals(connected.harnesses, pending.harnesses)
        HarnessMachineSpec.assertTransition(
            pending,
            HarnessIntent.Internal.Saved(mutation.receipt),
            connected.copy(
                harnesses = listOf(HarnessEntry(candidate, mapOf(code.id to ItemStatus.Pending(4)))),
                revision = 1,
                writeGeneration = 1,
                activationGeneration = 4,
            ),
            effects = listOf(HarnessEffect.Activate(listOf(HarnessActivationRequest(candidate, code, 4)))),
            outputs = listOf(HarnessOutput.Updated(request, harness.id)),
        )
    }

    @Test
    fun `default preference alone never activates a harness in an existing session`() {
        val preferred = harness.copy(isDefaultForNewSessions = true)
        assertEquals(emptyList(), activeHarnesses(listOf(preferred), emptyMap(), session))
        assertEquals(emptyList(), applicableHarnesses(listOf(preferred), emptyMap(), session))
        assertEquals(
            listOf(preferred),
            activeHarnesses(listOf(preferred), mapOf(session to setOf(preferred.id)), session),
        )
    }

    @Test
    fun `imported content preserves the profile default preference`() {
        val preferred = ready.copy(harnesses = listOf(HarnessEntry(harness.copy(isDefaultForNewSessions = true))))
        val pending = preferred.send(
            HarnessIntent.Internal.Update(
                request,
                harness.id,
                HarnessChange.Content("Imported", "", emptyList(), ToolPolicySpec()),
                harness.revision,
                HarnessAuthor.User,
                now,
            ),
        )
        assertTrue(saveOf(pending).harness.isDefaultForNewSessions)
    }
}
