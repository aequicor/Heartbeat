package io.aequicor.heartbeat.feature.harness.impl.data.services

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HARNESS_WAKE_OWNER
import io.aequicor.heartbeat.feature.scheduler.api.HelperReleaseResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HarnessSessionSpawnerTest {
    @Test
    fun `published sessions expose reads sends and parent helper with durable unmodified ancestry`() = runTest {
        val fixture = HarnessScriptSchedulerFixture(this)
        val active = fixture.activate()
        val origin = HarnessCallOrigin(sendChain = mapOf(active.request.harness.id to 2))
        fixture.runtime.origins.origin = origin
        assertEquals(dispatchSession, fixture.script.sessions.list().single().session)
        fixture.script.sessions.send(dispatchSession, "visible")
        assertTrue(fixture.port.scheduled.single().isNoteVisible)
        val helper = fixture.script.sessions.spawn(dispatchSession, "private title", "private prompt")
        val record = fixture.spawns.journal.records.values.single()
        val prompt = fixture.spawns.helpers.prompts.single()
        assertEquals(record.request, helper.request)
        assertEquals(record.request, prompt.request)
        assertEquals(record.attachRequest, fixture.spawns.bindings.bound.single().attachRequest)
        assertEquals<List<WorkspaceRef?>>(listOf(WorkspaceRef("checkout")), fixture.spawns.helpers.workspaces)
        assertEquals(origin, record.origin)
        assertEquals(HARNESS_WAKE_OWNER, prompt.handoff?.ownerFeature)
        assertEquals(origin, assertNotNull(HarnessOwnedContext().decode(prompt.handoff?.ownerContext)).origin())
        assertEquals("private prompt", prompt.text)
        assertEquals(0, fixture.spawns.helpers.releases)
        assertFalse(record.toString().contains("private"))
    }

    @Test
    fun `hooks and unpublished scripts cannot look up or create helpers`() = runTest {
        val fixture = HarnessScriptSchedulerFixture(this)
        fixture.runtime.evaluate = {
            assertFailsWith<IllegalStateException> { it.sessions.spawn(title = "title", prompt = "prompt") }
        }
        fixture.activate()
        fixture.runtime.origins.origin = HarnessCallOrigin(isHookRestricted = true)
        assertFailsWith<IllegalStateException> {
            fixture.script.sessions.spawn(dispatchSession, "title", "prompt")
        }
        assertEquals(0, fixture.externalReads)
        assertTrue(fixture.spawns.helpers.acquisitions.isEmpty())
    }

    @Test
    fun `parent scope denial cannot fall back to parentless defaults`() = runTest {
        val fixture = HarnessScriptSchedulerFixture(this)
        fixture.activate()
        fixture.allowsTarget = { it == null }
        assertFailsWith<IllegalStateException> {
            fixture.script.sessions.spawn(dispatchSession, "title", "prompt")
        }
        assertTrue(fixture.spawns.helpers.acquisitions.isEmpty())
        fixture.script.sessions.spawn(title = "default", prompt = "prompt")
        assertEquals<List<WorkspaceRef?>>(listOf(null), fixture.spawns.helpers.workspaces)
        assertEquals(null, fixture.spawns.helpers.acquisitions.single().parent)
    }

    @Test
    fun `ancestry is captured before a suspended permit check and each spawn gets fresh identities`() = runTest {
        val fixture = HarnessScriptSchedulerFixture(this)
        val active = fixture.activate()
        val origin = HarnessCallOrigin(sendChain = mapOf(active.request.harness.id to 3))
        fixture.runtime.origins.origin = origin
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.beforePermit = {
            entered.complete(Unit)
            release.await()
        }
        val first = async { fixture.script.sessions.spawn(title = "first", prompt = "prompt") }
        entered.await()
        fixture.runtime.origins.origin = HarnessCallOrigin()
        release.complete(Unit)
        first.await()
        val old = fixture.spawns.journal.records.values.single()
        assertEquals(origin, old.origin)
        fixture.script.sessions.spawn(title = "second", prompt = "prompt")
        val next = fixture.spawns.journal.records.values.last()
        assertNotEquals(old.reservation, next.reservation)
        assertNotEquals(old.action, next.action)
        assertNotEquals(old.request, next.request)
        assertNotEquals(old.attachRequest, next.attachRequest)
    }

    @Test
    fun `route mutation during acquisition releases granted capacity without creating or prompting`() = runTest {
        val fixture = HarnessScriptSchedulerFixture(this)
        fixture.activate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.spawns.helpers.beforeAcquire = {
            entered.complete(Unit)
            release.await()
        }
        val work = async {
            assertFailsWith<IllegalStateException> {
                fixture.script.sessions.spawn(dispatchSession, "title", "prompt")
            }
        }
        entered.await()
        fixture.summary.value = fixture.summary.value.copy(workspace = WorkspaceRef("changed"))
        release.complete(Unit)
        work.await()
        runCurrent()
        assertEquals(0, fixture.spawns.helpers.creates)
        assertTrue(fixture.spawns.helpers.prompts.isEmpty())
        assertEquals(1, fixture.spawns.helpers.releases)
        assertTrue(fixture.spawns.journal.records.isEmpty())
    }

    @Test
    fun `revocation during the final permit check cannot prompt an already created helper`() = runTest {
        val fixture = HarnessScriptSchedulerFixture(this)
        fixture.activate()
        fixture.spawns.helpers.beforeCreate = {
            fixture.beforePermit = { fixture.runtime.isEnabled = false }
        }
        assertFailsWith<IllegalStateException> { fixture.script.sessions.spawn(title = "title", prompt = "prompt") }
        runCurrent()
        assertEquals(1, fixture.spawns.helpers.creates)
        assertTrue(fixture.spawns.helpers.prompts.isEmpty())
        assertEquals(1, fixture.spawns.helpers.releases)
        assertTrue(fixture.spawns.journal.records.isEmpty())
    }

    @Test
    fun `replacement closes old facade and retains its helper until release is confirmed`() = runTest {
        val fixture = HarnessScriptSchedulerFixture(this)
        fixture.activate()
        val old = fixture.script.sessions
        old.spawn(title = "title", prompt = "prompt")
        fixture.spawns.helpers.release = { HelperReleaseResult.Unconfirmed }
        fixture.runtime.desired = fixture.runtime.desired.copy(generation = 2)
        fixture.activate()
        runCurrent()
        assertFailsWith<IllegalStateException> { old.spawn(title = "stale", prompt = "prompt") }
        assertTrue(fixture.spawns.helpers.releases > 0)
        assertFalse(fixture.spawns.operations.isQuiescent())
        fixture.spawns.helpers.release = { HelperReleaseResult.Released }
        advanceTimeBy(5_001)
        runCurrent()
        assertTrue(fixture.spawns.operations.isQuiescent())
        fixture.script.sessions.spawn(title = "new", prompt = "prompt")
        assertEquals(2, fixture.spawns.helpers.creates)
    }
}
