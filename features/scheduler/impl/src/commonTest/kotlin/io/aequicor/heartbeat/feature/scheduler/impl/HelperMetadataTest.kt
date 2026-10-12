package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperMetadata
import io.aequicor.heartbeat.feature.scheduler.api.HelperMetadataLimits
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledSessionHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class HelperMetadataTest {
    private val owner = ActionId("script_owner")

    @Test
    fun `reverse lookup sees durable identity before prompt acknowledgement without consuming capacity`() = runTest {
        val fixture = HelpersFixture(this)
        val lease = fixture.service.acquire(owner)
        val helper = fixture.service.create(lease, null, null, "Helper")
        val gate = CompletableDeferred<Unit>()
        fixture.host.beforePrompt = { gate.await() }
        val prompt = HelperPrompt(RequestId("request"), "Private")
        val pending = async { fixture.service.prompt(helper, prompt) }
        runCurrent()
        assertFalse(pending.isCompleted)
        assertEquals(HelperMetadata(helper, owner, null, OTHER, prompt.request), fixture.service.metadata(OTHER))
        assertNull(fixture.service.metadata(OTHER.copy(source = SessionSourceId("different"))))
        assertEquals(1, fixture.active)
        gate.complete(Unit)
        pending.await()
        lease.release()
    }

    @Test
    fun `owner pages merge hosts with a stable bound and retain historical entries`() = runTest {
        val a = HelperHostFake()
        val b = HelperHostFake()
        repeat(140) { index ->
            val id = HelperId(index.toString().padStart(3, '0'))
            val host = if (index % 2 == 0) a else b
            host.metadata[id] = HelperMetadata(id, owner, null, null, RequestId("old"))
        }
        a.metadata[HelperId("foreign")] = HelperMetadata(HelperId("foreign"), ActionId("other"), null, null, null)
        val fixture = HelpersFixture(this, a, setOf(a, b))
        val first = fixture.service.owned(owner, limit = 128)
        val second = fixture.service.owned(owner, after = first.last().id, limit = 128)
        assertEquals(128, first.size)
        assertEquals(12, second.size)
        assertEquals(140, (first + second).map { it.id }.distinct().size)
        assertEquals("139", second.last().id.value)
        assertEquals(0, fixture.active)
        assertFailsWith<IllegalArgumentException> { fixture.service.owned(owner, limit = 0) }
        assertFailsWith<IllegalArgumentException> {
            fixture.service.owned(owner, limit = HelperMetadataLimits.MAX_PAGE_SIZE + 1)
        }
    }

    @Test
    fun `ambiguous hosts and mismatched reverse identities never grant ownership`() = runTest {
        val a = HelperHostFake()
        val b = HelperHostFake()
        val value = HelperMetadata(HelperId("helper"), owner, null, OTHER, null)
        a.metadata[value.id] = value
        b.metadata[value.id] = value
        val ambiguous = HelpersFixture(this, a, setOf(a, b))
        assertFailsWith<IllegalStateException> { ambiguous.service.metadata(OTHER) }
        assertFailsWith<IllegalStateException> { ambiguous.service.owned(owner) }
        val wrong = object : ScheduledSessionHost by a {
            override suspend fun helperMetadata(session: SessionRef): HelperMetadata = value.copy(session = SESSION)
        }
        val mismatched = HelpersFixture(this, a, setOf(wrong))
        assertFailsWith<IllegalStateException> { mismatched.service.metadata(OTHER) }
    }

    @Test
    fun `invalid host page cannot expose a foreign owner or skip the requested boundary`() = runTest {
        val value = HelperMetadata(HelperId("b"), owner, null, null, null)
        val base = HelperHostFake()
        val invalidPages = listOf(
            listOf(value.copy(owner = ActionId("foreign"))),
            listOf(value.copy(id = HelperId("a"))),
            listOf(value.copy(id = HelperId("c")), value),
            listOf(value, value),
        )
        for (page in invalidPages) {
            val host = object : ScheduledSessionHost by base {
                override suspend fun ownedHelpers(owner: ActionId, after: HelperId?, limit: Int) = page
            }
            val fixture = HelpersFixture(this, base, setOf(host))
            assertFailsWith<IllegalStateException> {
                fixture.service.owned(owner, after = HelperId("a"), limit = 2)
            }
        }
    }
}
