package io.aequicor.heartbeat.feature.harness.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HarnessMachineWritesTest {
    @Test
    fun `create reserves the name and bytes but becomes visible only after Saved`() {
        val empty = HarnessState.Ready(isRuntimeAvailable = true)
        val receipt = HarnessReceipt(request, harness.id, 0)
        val mutation = HarnessMutation.Save(receipt, harness, true)
        val pending = empty.copy(pending = mapOf(harness.id to mutation), writeGeneration = 1)
        HarnessMachineSpec.assertTransition(
            empty,
            create,
            pending,
            effects = listOf(HarnessEffect.Save(harness, receipt)),
        )
        pending.refuses(create.copy(requestId = RequestId("another")), HarnessRejection.Duplicate)
        val committed = pending.copy(
            harnesses = listOf(HarnessEntry(harness, mapOf(code.id to ItemStatus.Pending(1)))),
            pending = emptyMap(),
            revision = 1,
            activationGeneration = 1,
        )
        HarnessMachineSpec.assertTransition(
            pending,
            HarnessIntent.Internal.Saved(receipt),
            committed,
            effects = listOf(HarnessEffect.Activate(listOf(HarnessActivationRequest(harness, code, 1)))),
            outputs = listOf(HarnessOutput.Created(request, harness.id)),
        )
        HarnessMachineSpec.assertIgnored(committed, HarnessIntent.Internal.Saved(receipt))
    }

    @Test
    fun `update keeps old code until save and preserves it on failed storage`() {
        val changed = code.copy(source = "new source")
        val intent = HarnessIntent.Public.Update(
            request,
            harness.id,
            HarnessChange.PutItem(changed),
            0,
            HarnessAuthor.User,
            now,
        )
        val candidate = harness.copy(items = listOf(changed), revision = 1)
        val receipt = HarnessReceipt(request, harness.id, 1)
        val mutation = HarnessMutation.Save(receipt, candidate, false)
        val pending = ready.copy(pending = mapOf(harness.id to mutation), writeGeneration = 1)
        HarnessMachineSpec.assertTransition(
            ready,
            intent,
            pending,
            effects = listOf(HarnessEffect.Save(candidate, receipt)),
        )
        pending.refuses(intent, HarnessRejection.Busy)
        pending.refuses(intent.copy(expectedRevision = 7), HarnessRejection.Conflict)
        HarnessMachineSpec.assertTransition(
            pending,
            HarnessIntent.Internal.SaveFailed(receipt),
            ready.copy(writeGeneration = 1),
            outputs = listOf(HarnessOutput.StorageFailed(request)),
        )
        val committed = pending.send(HarnessIntent.Internal.Saved(receipt))
        assertEquals(candidate, committed.harnesses.single().harness)
        assertEquals(
            HarnessOutput.Updated(request, harness.id),
            HarnessMachineSpec.resolve(pending, HarnessIntent.Internal.Saved(receipt))?.outputs?.single(),
        )
        HarnessMachineSpec.assertIgnored(
            pending,
            HarnessIntent.Internal.Saved(receipt.copy(requestId = RequestId("stale"))),
        )
        HarnessMachineSpec.assertIgnored(pending, HarnessIntent.Internal.RemoveFailed(receipt))
    }

    @Test
    fun `delete retains committed entry on failure and removes attachments only after Removed`() {
        val initial = ready.copy(attachments = mapOf(session to setOf(harness.id)))
        val receipt = HarnessReceipt(request, harness.id, 0)
        val mutation = HarnessMutation.Remove(receipt, harness)
        val pending = initial.copy(pending = mapOf(harness.id to mutation), writeGeneration = 1)
        HarnessMachineSpec.assertTransition(
            initial,
            HarnessIntent.Public.Delete(request, harness.id, 0),
            pending,
            effects = listOf(HarnessEffect.Remove(harness, receipt)),
        )
        HarnessMachineSpec.assertTransition(
            pending,
            HarnessIntent.Internal.RemoveFailed(receipt),
            initial.copy(
                harnesses = listOf(HarnessEntry(harness, mapOf(code.id to ItemStatus.Pending(1)))),
                writeGeneration = 1,
                activationGeneration = 1,
            ),
            effects = listOf(HarnessEffect.Activate(listOf(HarnessActivationRequest(harness, code, 1)))),
            outputs = listOf(HarnessOutput.StorageFailed(request)),
        )
        HarnessMachineSpec.assertTransition(
            pending,
            HarnessIntent.Internal.Removed(receipt),
            initial.copy(harnesses = emptyList(), attachments = emptyMap(), revision = 1, writeGeneration = 1),
            outputs = listOf(HarnessOutput.Deleted(request, harness.id)),
        )
        HarnessMachineSpec.assertIgnored(pending, HarnessIntent.Internal.Saved(receipt))
        HarnessMachineSpec.assertIgnored(initial, HarnessIntent.Internal.Removed(receipt))
        pending.refuses(HarnessIntent.Public.Attach(request, harness.id, session), HarnessRejection.Busy)
    }

    @Test
    fun `item and harness enabling are revision checked durable updates`() {
        ready.refuses(HarnessIntent.Public.SetEnabled(request, harness.id, false, 3, now), HarnessRejection.Conflict)
        ready.refuses(
            HarnessIntent.Public.SetItemEnabled(request, harness.id, code.id, false, 3, now),
            HarnessRejection.Conflict,
        )
        val disabled = ready.send(HarnessIntent.Public.SetEnabled(request, harness.id, false, 0, now))
        val mutation = saveOf(disabled)
        assertFalse(mutation.harness.isEnabled)
        val done = disabled.send(HarnessIntent.Internal.Saved(mutation.receipt))
        assertEquals(ItemStatus.Disabled, done.harnesses.single().itemStatus[code.id])
        assertEquals(
            listOf(
                HarnessEffect.Deactivate(
                    listOf(HarnessActivationRequest(harness, code, 0)),
                    true,
                    setOf(harness.id),
                    generation = 1,
                ),
            ),
            HarnessMachineSpec.resolve(disabled, HarnessIntent.Internal.Saved(mutation.receipt))?.effects,
        )
        val itemDisabled = ready.send(HarnessIntent.Public.SetItemEnabled(request, harness.id, code.id, false, 0, now))
        assertFalse(saveOf(itemDisabled).harness.items.single().isEnabled)
        assertTrue(itemDisabled.harnesses.single().harness.items.single().isEnabled)
    }

    @Test
    fun `names cannot mutate or collide and missing items and harnesses are rejected`() {
        val update = HarnessIntent.Public.Update(
            request,
            harness.id,
            HarnessChange.PutItem(code.copy(name = ItemName("renamed"))),
            0,
            HarnessAuthor.User,
            now,
        )
        ready.refuses(update, HarnessRejection.Invalid)
        ready.refuses(
            update.copy(change = HarnessChange.PutItem(code.copy(id = ItemId("other")))),
            HarnessRejection.Duplicate,
        )
        ready.refuses(update.copy(change = HarnessChange.RemoveItem(ItemId("missing"))), HarnessRejection.NotFound)
        ready.refuses(update.copy(id = HarnessId("missing")), HarnessRejection.NotFound)
        ready.refuses(
            update.copy(change = HarnessChange.Meta("new", ""), at = now - kotlin.time.Duration.parse("1s")),
            HarnessRejection.Invalid,
        )
        HarnessState.Ready().refuses(
            create.copy(draft = draft.copy(items = listOf(code, code))),
            HarnessRejection.Invalid,
        )
    }

    @Test
    fun `meta scope tools and remove item all produce revised durable candidates`() {
        val changes = listOf(
            HarnessChange.Meta("new", "description"),
            HarnessChange.Scope(HarnessScope.Profile),
            HarnessChange.Tools(ToolPolicySpec(setOf("search"))),
            HarnessChange.RemoveItem(code.id),
        )
        changes.forEach { change ->
            val intent = HarnessIntent.Public.Update(request, harness.id, change, 0, HarnessAuthor.Agent(session), now)
            val pending = ready.send(intent)
            val mutation = saveOf(pending)
            assertEquals(1, mutation.harness.revision)
            HarnessMachineSpec.assertTransition(
                ready,
                intent,
                pending,
                effects = listOf(HarnessEffect.Save(mutation.harness, mutation.receipt)),
            )
        }
    }

    @Test
    fun `concurrent creates reserve count and profile storage limits`() {
        val entries = (1..HarnessLimits.HARNESSES).map { index ->
            HarnessEntry(harness.copy(id = HarnessId("id$index"), name = HarnessName("name$index")))
        }
        ready.copy(harnesses = entries).refuses(create, HarnessRejection.Limit)
        val huge = draft.copy(description = "x".repeat(HarnessLimits.BYTES_PER_HARNESS))
        HarnessState.Ready().refuses(create.copy(draft = huge), HarnessRejection.Limit)
        val items = (1..HarnessLimits.ITEMS).map { code.copy(id = ItemId("$it"), name = ItemName("i$it")) }
        val full = ready.copy(harnesses = listOf(HarnessEntry(harness.copy(items = items))))
        full.refuses(
            HarnessIntent.Public.Update(request, harness.id, HarnessChange.PutItem(code), 0, HarnessAuthor.User, now),
            HarnessRejection.Limit,
        )
    }

    @Test
    fun `pending shrink cannot lend committed bytes to another create before durable confirmation`() {
        val entries = (1..8).map { index ->
            HarnessEntry(
                harness.copy(
                    id = HarnessId("id$index"),
                    name = HarnessName("name$index"),
                    description = "x".repeat(250 * 1024),
                ),
            )
        }
        val full = ready.copy(harnesses = entries)
        val old = entries.first().harness
        val shrink = HarnessIntent.Public.Update(
            request,
            old.id,
            HarnessChange.Meta("small", ""),
            0,
            HarnessAuthor.User,
            now,
        )
        val shrinking = full.send(shrink)
        shrinking.refuses(create.copy(draft = draft.copy(description = "x".repeat(100 * 1024))), HarnessRejection.Limit)
        val failed = shrinking.send(HarnessIntent.Internal.SaveFailed(saveOf(shrinking).receipt))
        assertEquals(full.harnesses, failed.harnesses)
        val committed = shrinking.send(HarnessIntent.Internal.Saved(saveOf(shrinking).receipt))
        val accepted = committed.send(create.copy(draft = draft.copy(description = "x".repeat(100 * 1024))))
        assertEquals(1, accepted.pending.size)
    }

    @Test
    fun `late save receipt cannot commit a different retry with the same public request id`() {
        val intent = HarnessIntent.Public.Update(
            request,
            harness.id,
            HarnessChange.Meta("first", ""),
            0,
            HarnessAuthor.User,
            now,
        )
        val first = ready.send(intent)
        val receipt = saveOf(first).receipt
        val failed = first.send(HarnessIntent.Internal.SaveFailed(receipt))
        val retry = failed.send(intent.copy(change = HarnessChange.Meta("second", "")))
        assertEquals(2, saveOf(retry).receipt.generation)
        HarnessMachineSpec.assertIgnored(retry, HarnessIntent.Internal.Saved(receipt))
        HarnessMachineSpec.assertIgnored(retry, HarnessIntent.Internal.SaveFailed(receipt))
    }
}
