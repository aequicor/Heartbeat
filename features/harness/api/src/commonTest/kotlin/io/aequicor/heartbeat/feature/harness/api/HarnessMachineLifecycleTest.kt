package io.aequicor.heartbeat.feature.harness.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HarnessMachineLifecycleTest {
    @Test
    fun `start retry and loading errors preserve suspension`() {
        val load = HarnessLoad(1, 0)
        listOf(HarnessState.Idle(true), HarnessState.Failed(true)).forEach { state ->
            HarnessMachineSpec.assertTransition(
                state,
                HarnessIntent.Internal.Start,
                HarnessState.Loading(load, true),
                effects = listOf(HarnessEffect.Load(load)),
            )
        }
        val loading = HarnessState.Loading(load, true)
        HarnessMachineSpec.assertTransition(
            loading,
            HarnessIntent.Internal.LoadFailed(load),
            HarnessState.Failed(true, 1),
            outputs = listOf(HarnessOutput.StorageFailed(null)),
        )
        val invalid = HarnessIntent.Internal.Loaded(load, listOf(harness, harness))
        HarnessMachineSpec.assertTransition(
            loading,
            invalid,
            HarnessState.Failed(true, 1),
            outputs = listOf(HarnessOutput.StorageFailed(null)),
        )
        HarnessMachineSpec.assertIgnored(loading, HarnessIntent.Internal.LoadFailed(load.copy(generation = 2)))
        HarnessMachineSpec.assertIgnored(loading, HarnessIntent.Internal.Loaded(load.copy(generation = 2), emptyList()))
        HarnessMachineSpec.assertIgnored(loading, HarnessIntent.Internal.Start)
    }

    @Test
    fun `public requests and internal edits receive unavailable in non model states`() {
        val commands = listOf(
            create,
            HarnessIntent.Internal.Update(
                request,
                harness.id,
                HarnessChange.Meta("", ""),
                0,
                HarnessAuthor.User,
                now,
            ),
            HarnessIntent.Internal.Delete(
                request,
                harness.id,
                0,
            ),
            HarnessIntent.Internal.SetEnabled(request, harness.id, true, 0, now),
            HarnessIntent.Internal.SetItemEnabled(request, harness.id, code.id, true, 0, now),
            HarnessIntent.Public.Attach(
                request,
                harness.id,
                session,
            ),
            HarnessIntent.Public.Detach(request, harness.id, session),
            HarnessIntent.Public.Reload(request),
        )
        val states = listOf(HarnessState.Idle(), HarnessState.Loading(HarnessLoad(1, 0)), HarnessState.Failed(true))
        states.forEach { state ->
            commands.forEach { intent ->
                HarnessMachineSpec.assertTransition(
                    state,
                    intent,
                    state,
                    outputs = listOf(HarnessOutput.Rejected(request, HarnessRejection.Unavailable)),
                )
            }
        }
    }

    @Test
    fun `other features can only connect chats and reload while edits stay internal`() {
        val edits: List<HarnessIntent.Command> = listOf(
            create,
            HarnessIntent.Internal.SetEnabled(request, harness.id, true, 0, now),
        )
        assertTrue(edits.none { it is HarnessIntent.Public })
        val publics: List<HarnessIntent.Public> = listOf(
            HarnessIntent.Public.Attach(request, harness.id, session),
            HarnessIntent.Public.Detach(request, harness.id, session),
            HarnessIntent.Public.Reload(request),
        )
        // Exhaustive on purpose: a new public command must be reviewed against code activation and approval.
        publics.forEach { intent ->
            val isMembershipOrReload = when (intent) {
                is HarnessIntent.Public.Attach, is HarnessIntent.Public.Detach, is HarnessIntent.Public.Reload -> true
            }
            assertTrue(isMembershipOrReload)
        }
    }

    @Test
    fun `reload retries a failed load unless the feature is suspended`() {
        HarnessMachineSpec.assertTransition(
            HarnessState.Failed(false, 3),
            HarnessIntent.Public.Reload(request),
            HarnessState.Loading(HarnessLoad(4, 0), false),
            effects = listOf(HarnessEffect.Load(HarnessLoad(4, 0))),
        )
        HarnessMachineSpec.assertTransition(
            HarnessState.Failed(false, 3),
            HarnessIntent.Internal.SetEnabled(request, harness.id, true, 0, now),
            HarnessState.Failed(false, 3),
            outputs = listOf(HarnessOutput.Rejected(request, HarnessRejection.Unavailable)),
        )
    }

    @Test
    fun `suspend and resume work in every non model state and duplicates are ignored`() {
        val pairs = listOf(
            HarnessState.Idle() to HarnessState.Idle(true),
            HarnessState.Failed() to HarnessState.Failed(true),
            HarnessState.Loading(HarnessLoad(1, 0)) to HarnessState.Loading(HarnessLoad(1, 0), true),
        )
        pairs.forEach { (normal, suspended) ->
            HarnessMachineSpec.assertTransition(normal, HarnessIntent.Internal.Suspended, suspended)
            HarnessMachineSpec.assertTransition(suspended, HarnessIntent.Internal.Resumed, normal)
            HarnessMachineSpec.assertIgnored(suspended, HarnessIntent.Internal.Suspended)
            HarnessMachineSpec.assertIgnored(normal, HarnessIntent.Internal.Resumed)
        }
    }

    @Test
    fun `loading activates supported code but suspension and unavailable runtime suppress it`() {
        val load = HarnessLoad(1, 0)
        val loaded = HarnessIntent.Internal.Loaded(load, listOf(harness), isRuntimeAvailable = true)
        val next = HarnessState.Ready(
            listOf(HarnessEntry(harness, mapOf(code.id to ItemStatus.Pending(1)))),
            isRuntimeAvailable = true,
            loadGeneration = 1,
            activationGeneration = 1,
        )
        HarnessMachineSpec.assertTransition(
            HarnessState.Loading(load),
            loaded,
            next,
            effects = listOf(HarnessEffect.Activate(listOf(HarnessActivationRequest(harness, code, 1)))),
        )
        HarnessMachineSpec.assertTransition(
            HarnessState.Loading(load, true),
            loaded,
            next.copy(
                isSuspended = true,
                harnesses = listOf(HarnessEntry(harness, mapOf(code.id to ItemStatus.Disabled))),
            ),
        )
        HarnessMachineSpec.assertTransition(
            HarnessState.Loading(load),
            loaded.copy(isRuntimeAvailable = false),
            next.copy(
                isRuntimeAvailable = false,
                harnesses = listOf(HarnessEntry(harness, mapOf(code.id to ItemStatus.Unsupported))),
            ),
        )
    }

    @Test
    fun `reload stays alive and cannot overwrite a concurrent committed write`() {
        val load = HarnessLoad(1, 0)
        val loading = ready.copy(reload = load, loadGeneration = 1)
        HarnessMachineSpec.assertTransition(
            ready,
            HarnessIntent.Public.Reload(request),
            loading,
            effects = listOf(HarnessEffect.Load(load)),
        )
        loading.refuses(HarnessIntent.Public.Reload(request), HarnessRejection.Busy)
        val update = HarnessIntent.Internal.SetEnabled(request, harness.id, false, 0, now)
        val pending = loading.send(update)
        val committed = pending.send(HarnessIntent.Internal.Saved(saveOf(pending).receipt))
        val loaded = HarnessIntent.Internal.Loaded(load, listOf(harness))
        HarnessMachineSpec.assertTransition(committed, loaded, committed.copy(reload = null))
        assertFalse(committed.harnesses.single().harness.isEnabled)
        val preexisting = ready.send(update)
        val reload = preexisting.send(HarnessIntent.Public.Reload(request))
        assertEquals(false, reload.reload?.isApplicable)
        HarnessMachineSpec.assertTransition(
            reload,
            loaded.copy(load = assertNotNull(reload.reload)),
            reload.copy(reload = null),
        )
        HarnessMachineSpec.assertIgnored(ready, loaded)
        HarnessMachineSpec.assertIgnored(loading, HarnessIntent.Internal.LoadFailed(load.copy(generation = 99)))
    }

    @Test
    fun `reload replaces a stable snapshot and errors preserve committed data`() {
        val load = HarnessLoad(1, 0)
        val loading = ready.copy(reload = load, loadGeneration = 1)
        val loaded = HarnessIntent.Internal.Loaded(load, emptyList())
        HarnessMachineSpec.assertTransition(
            loading,
            loaded,
            HarnessState.Ready(revision = 1, loadGeneration = 1, activationGeneration = 1),
            effects = listOf(
                HarnessEffect.Deactivate(listOf(HarnessActivationRequest(harness, code, 0)), false, generation = 0),
            ),
        )
        HarnessMachineSpec.assertTransition(
            loading,
            loaded.copy(harnesses = listOf(harness, harness)),
            ready.copy(loadGeneration = 1),
            outputs = listOf(HarnessOutput.StorageFailed(null)),
        )
        HarnessMachineSpec.assertTransition(
            loading,
            HarnessIntent.Internal.LoadFailed(load),
            ready.copy(loadGeneration = 1),
            outputs = listOf(HarnessOutput.StorageFailed(null)),
        )
    }

    @Test
    fun `effect failures produce the same correlated repository failure as explicit feedback`() {
        val receipt = HarnessReceipt(request, harness.id, 1)
        val attachment = HarnessAttachmentWrite(request, harness.id, session, true, emptyMap())
        val cases = mapOf<HarnessEffect, HarnessIntent.Internal>(
            HarnessEffect.Load(HarnessLoad(1, 0)) to HarnessIntent.Internal.LoadFailed(HarnessLoad(1, 0)),
            HarnessEffect.Save(harness, receipt) to HarnessIntent.Internal.SaveFailed(receipt),
            HarnessEffect.Remove(harness, receipt) to HarnessIntent.Internal.RemoveFailed(receipt),
            HarnessEffect.SaveAttachments(attachment) to HarnessIntent.Internal.AttachmentsFailed(attachment),
        )
        cases.forEach { (effect, expected) ->
            assertEquals(
                expected,
                HarnessMachineSpec.onEffectFailure(effect, IllegalStateException("secret")),
            )
        }
        assertNull(HarnessMachineSpec.persistence)
    }

    @Test
    fun `retry load token never accepts a late first attempt reply`() {
        val first = HarnessLoad(1, 0)
        val failed = HarnessState.Failed(loadGeneration = 1)
        val next = HarnessState.Loading(HarnessLoad(2, 0))
        HarnessMachineSpec.assertTransition(
            failed,
            HarnessIntent.Internal.Start,
            next,
            effects = listOf(HarnessEffect.Load(HarnessLoad(2, 0))),
        )
        HarnessMachineSpec.assertIgnored(next, HarnessIntent.Internal.Loaded(first, listOf(harness)))
        HarnessMachineSpec.assertIgnored(next, HarnessIntent.Internal.LoadFailed(first))
    }
}
