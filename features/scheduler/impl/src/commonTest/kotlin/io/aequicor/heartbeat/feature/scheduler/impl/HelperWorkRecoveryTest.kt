package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperOutcome
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import io.aequicor.heartbeat.feature.scheduler.api.HelperReleaseResult
import io.aequicor.heartbeat.feature.scheduler.api.HelperResult
import io.aequicor.heartbeat.feature.scheduler.api.HelperWorkBinding
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperCapacityRecoveryRecord
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperCapacityRecoverySource
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperMetadata
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HelperWorkRecoveryTest {
    @Test
    fun `matching request metadata cannot bypass a rejected exact slot proof`() = runTest {
        val helper = HelperId("legacy")
        val source = HelperCapacityRecoverySource {
            listOf(HelperCapacityRecoveryRecord(reservation, owner, null, helper, request))
        }
        val f = HelpersFixture(this, recovery = setOf(source))
        f.host.metadata[helper] = HelperMetadata(helper, owner, null, OTHER, request)
        var isChecked = false
        f.host.reconciliation = { _, _, _ ->
            isChecked = true
            false
        }
        assertFailsWith<IllegalStateException> {
            f.service.acquire(owner, existing = helper, reservation = reservation)
        }
        assertTrue(isChecked)
        assertEquals(1, f.active)
        assertTrue(f.host.cancellations.isEmpty())
    }

    @Test
    fun `a lease replaces forged caller work ownership with its own exact reservation`() = runTest {
        val f = HelpersFixture(this)
        val lease = f.service.acquire(owner, reservation = reservation)
        val helper = f.service.create(lease, null, TARGET, "Helper")
        val prompt = HelperPrompt(
            request,
            "Task",
            work = HelperWorkBinding(ActionId("forged-owner"), ActionId("forged-slot")),
        )
        f.service.prompt(helper, prompt)
        assertEquals(HelperWorkBinding(owner, reservation), f.host.prompts.single().work)
        f.host.results[request] = HelperResult(request, HelperOutcome.Completed, "Done")
        assertEquals(HelperReleaseResult.Released, lease.release())
        assertTrue(f.host.cancellations.isEmpty())
    }

    @Test
    fun `unknown attribution without a request journal never adopts work or frees its restored slot`() = runTest {
        val helper = HelperId("legacy")
        val source = HelperCapacityRecoverySource {
            listOf(HelperCapacityRecoveryRecord(reservation, owner, null, helper))
        }
        val f = HelpersFixture(this, recovery = setOf(source))
        f.host.metadata[helper] = HelperMetadata(helper, owner, null, OTHER, null, isRequestOwnershipResolved = false)
        assertFailsWith<IllegalStateException> {
            f.service.acquire(owner, existing = helper, reservation = reservation)
        }
        assertEquals(1, f.active)
        assertTrue(f.host.cancellations.isEmpty())
        assertTrue(f.host.prompts.isEmpty())
    }

    @Test
    fun `old exact work cleanup ignores the newer independent human turn`() = runTest {
        val helper = HelperId("legacy")
        val source = HelperCapacityRecoverySource {
            listOf(HelperCapacityRecoveryRecord(reservation, owner, null, helper, request))
        }
        val f = HelpersFixture(this, recovery = setOf(source))
        f.host.metadata[helper] = HelperMetadata(helper, owner, null, OTHER, null, isRequestOwnershipResolved = false)
        val proofs = mutableListOf<Pair<RequestId, HelperWorkBinding>>()
        f.host.reconciliation = { id, expected, binding ->
            assertEquals(helper, id)
            proofs += expected to binding
            f.host.metadata[id] = checkNotNull(f.host.metadata[id]).copy(isRequestOwnershipResolved = true)
            true
        }
        f.host.results[request] = HelperResult(request, HelperOutcome.Completed, "Old result")
        val lease = f.service.acquire(owner, existing = helper, reservation = reservation)
        assertEquals(listOf(request to HelperWorkBinding(owner, reservation)), proofs)
        assertEquals(HelperReleaseResult.Released, lease.release())
        assertEquals(listOf(request), f.host.resultReads)
        assertTrue(f.host.cancellations.isEmpty())
        assertTrue(f.host.prompts.isEmpty())
        assertEquals(0, f.active)
    }
}

private val owner = ActionId("work-owner")
private val reservation = ActionId("reservation")
private val request = RequestId("old-work-request")
