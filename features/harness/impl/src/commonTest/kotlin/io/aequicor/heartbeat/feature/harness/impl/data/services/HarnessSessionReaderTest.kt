package io.aequicor.heartbeat.feature.harness.impl.data.services

import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCursor
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCursor
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceTarget
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HarnessSessionReaderTest {
    @Test
    fun `listing filters every page and never invents a model target`() = runTest {
        val fixture = HarnessScriptSchedulerFixture(this)
        val active = fixture.activate()
        val owner = HarnessInstanceTarget(active.request, active)
        val other = fixture.summary.value.copy(ref = dispatchSession.copy(nativeId = "other"), title = "private")
        val second = fixture.summary.value.copy(ref = dispatchSession.copy(nativeId = "second"), title = "visible")
        fixture.facade.summaries[other.ref] = MutableStateFlow(other)
        fixture.facade.summaries[second.ref] = MutableStateFlow(second)
        fixture.allowsTarget = { it?.session != other.ref }
        val next = SessionCursor("page2")
        fixture.facade.page = {
            when (it.cursor) {
                null -> SessionPage(listOf(other, fixture.summary.value), next, emptyList())
                next -> SessionPage(listOf(second), null, emptyList())
                else -> error("Unexpected cursor")
            }
        }
        val result = fixture.reader.list(owner)
        assertEquals(listOf(dispatchSession, second.ref), result.map { it.session })
        assertEquals("visible", result.last().title)
        assertEquals(WorkspaceRef("checkout"), result.last().workspace)
        result.forEach { assertNull(it.target) }
        assertEquals(listOf(null, next), fixture.facade.pageRequests.map { it.cursor })
    }

    @Test
    fun `scope change during listing rejects the entire earlier snapshot`() = runTest {
        val fixture = HarnessScriptSchedulerFixture(this)
        val active = fixture.activate()
        val owner = HarnessInstanceTarget(active.request, active)
        val next = SessionCursor("next")
        fixture.facade.page = {
            if (it.cursor == null) {
                SessionPage(listOf(fixture.summary.value), next, emptyList())
            } else {
                fixture.admissionRevision++
                SessionPage(emptyList(), null, emptyList())
            }
        }
        assertFailsWith<IllegalStateException> { fixture.reader.list(owner) }
    }

    @Test
    fun `repeated cursor fails instead of returning a silently truncated listing`() = runTest {
        val fixture = HarnessScriptSchedulerFixture(this)
        val active = fixture.activate()
        fixture.facade.page = { SessionPage(emptyList(), SessionCursor("same"), emptyList()) }
        assertFailsWith<IllegalStateException> {
            fixture.reader.list(HarnessInstanceTarget(active.request, active))
        }
        assertEquals(2, fixture.facade.pageRequests.size)
    }

    @Test
    fun `history preserves bounded request cursors checkpoint and partial coverage`() = runTest {
        val fixture = HarnessScriptSchedulerFixture(this)
        val active = fixture.activate()
        val history = HistoryFixture()
        fixture.facade.history = history
        val request = HistoryPageRequest(HistoryCursor("older-page"), 7)
        val result = fixture.reader.history(HarnessInstanceTarget(active.request, active), dispatchSession, request)
        assertEquals(dispatchSession, result.session)
        assertEquals(listOf(request), history.requests)
        assertSame(history.result, result.page)
        assertTrue(fixture.facade.pageRequests.isEmpty())
    }

    @Test
    fun `inaccessible history is rejected before transcript IO and unsupported is not empty history`() = runTest {
        val fixture = HarnessScriptSchedulerFixture(this)
        val active = fixture.activate()
        val owner = HarnessInstanceTarget(active.request, active)
        val history = HistoryFixture()
        fixture.facade.history = history
        fixture.allowsTarget = { it == null }
        assertFailsWith<IllegalStateException> { fixture.reader.history(owner, dispatchSession, HistoryPageRequest()) }
        assertTrue(history.requests.isEmpty())
        fixture.allowsTarget = { true }
        fixture.facade.history = null
        assertFailsWith<IllegalStateException> { fixture.reader.history(owner, dispatchSession, HistoryPageRequest()) }
    }

    @Test
    fun `route or activation change during history IO discards private response`() = runTest {
        repeat(3) { change ->
            val fixture = HarnessScriptSchedulerFixture(this)
            val active = fixture.activate()
            val owner = HarnessInstanceTarget(active.request, active)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.facade.history = HistoryFixture().apply {
                beforePage = {
                    entered.complete(Unit)
                    release.await()
                }
            }
            val read = async {
                assertFailsWith<IllegalStateException> {
                    fixture.reader.history(owner, dispatchSession, HistoryPageRequest())
                }
            }
            entered.await()
            when (change) {
                0 -> fixture.summary.value = fixture.summary.value.copy(workspace = WorkspaceRef("changed"))
                1 -> fixture.admissionRevision++
                else -> fixture.runtime.isEnabled = false
            }
            release.complete(Unit)
            read.await()
        }
    }
}

private class HistoryFixture : SessionHistory {
    val requests = mutableListOf<HistoryPageRequest>()
    val result = HistoryPage(
        emptyList(),
        HistoryCursor("older"),
        HistoryCursor("newer"),
        HistoryCheckpoint("checkpoint"),
        HistoryCoverage.Partial,
    )
    var beforePage: suspend () -> Unit = {}
    override suspend fun page(request: HistoryPageRequest): HistoryPage {
        requests += request
        beforePage()
        return result
    }
    override fun watch(after: HistoryCheckpoint): Flow<SessionEvent> = error("Unexpected watch")
}
