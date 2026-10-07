package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.BackgroundCapacityState
import io.aequicor.heartbeat.feature.scheduler.api.HelperCancellation
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperOutcome
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import io.aequicor.heartbeat.feature.scheduler.api.HelperReleaseResult
import io.aequicor.heartbeat.feature.scheduler.api.HelperResult
import io.aequicor.heartbeat.feature.scheduler.api.HelperSubmission
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperCreateRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperMetadata
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledSessionHost
import io.aequicor.heartbeat.feature.scheduler.impl.data.ProfileHelperAgents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HelperAgentsTest {
    private val owner = ActionId("wf_run")
    private val prompt = HelperPrompt(RequestId("step1"), "Private prompt")

    @Test
    fun `creation persists an empty helper and prompting requires its lease`() = runTest {
        val fixture = HelpersFixture(this)
        val lease = fixture.service.acquire(owner)
        val helper = fixture.service.create(lease, PROJECT, TARGET, "Private title", TrustLevel.Ask)
        assertTrue(fixture.host.prompts.isEmpty())
        assertEquals(owner, fixture.host.metadata[helper]?.owner)
        assertEquals(HelperSubmission.Accepted(prompt.request, OTHER), fixture.service.prompt(helper, prompt))
        assertEquals(1, fixture.active)
        assertEquals(HelperSubmission.Accepted(prompt.request, OTHER), fixture.service.prompt(helper, prompt))
        assertEquals(1, fixture.host.prompts.size)
        assertFailsWith<IllegalStateException> { fixture.service.prompt(helper, prompt.copy(text = "changed")) }
        assertFailsWith<IllegalStateException> {
            fixture.service.prompt(helper, prompt.copy(request = RequestId("next")))
        }
        fixture.host.results[prompt.request] = HelperResult(prompt.request, HelperOutcome.Completed, "answer")
        assertEquals("answer", fixture.service.result(helper, prompt.request)?.answer)
        assertEquals(HelperReleaseResult.Released, lease.release())
        assertEquals(HelperReleaseResult.Released, lease.release())
        assertEquals(0, fixture.active)
        assertFailsWith<IllegalStateException> { fixture.service.prompt(helper, prompt) }
        assertTrue(fixture.service.isHelper(OTHER))
    }

    @Test
    fun `cancelled caller retains submission until cleanup revokes the pending send`() = runTest {
        val fixture = HelpersFixture(this)
        val gate = CompletableDeferred<Unit>()
        fixture.host.beforePrompt = { gate.await() }
        val lease = fixture.service.acquire(owner)
        val helper = fixture.service.create(lease, null, TARGET, "Helper")
        val waiting = async { fixture.service.prompt(helper, prompt) }
        runCurrent()
        waiting.cancelAndJoin()
        val closing = async { lease.release() }
        runCurrent()
        assertEquals(HelperReleaseResult.Released, closing.await())
        assertFalse(gate.isCompleted)
        assertEquals(listOf(prompt.request), fixture.host.cancellations)
        assertEquals(0, fixture.active)
    }

    @Test
    fun `uncertain submission retains capacity until a matching cancellation barrier`() = runTest {
        for (isSelfCancellation in listOf(false, true)) {
            val fixture = HelpersFixture(this)
            fixture.host.beforePrompt = {
                if (isSelfCancellation) throw CancellationException("Host stopped waiting")
                error("Acceptance unknown")
            }
            fixture.host.cancellation = { HelperCancellation.Unconfirmed(it) }
            val lease = fixture.service.acquire(owner)
            val helper = fixture.service.create(lease, null, TARGET, "Helper")
            assertFailsWith<Exception> { fixture.service.prompt(helper, prompt) }
            assertEquals(HelperReleaseResult.Unconfirmed, lease.release())
            assertEquals(1, fixture.active)
            assertFailsWith<IllegalStateException> { fixture.service.prompt(helper, prompt) }
            fixture.host.cancellation = {
                HelperCancellation.Terminal(HelperResult(it, HelperOutcome.Cancelled, ""))
            }
            assertEquals(HelperReleaseResult.Released, lease.release())
            assertEquals(0, fixture.active)
        }
    }

    @Test
    fun `unknown terminal releases capacity and recovery explicitly journals a different request`() = runTest {
        val fixture = HelpersFixture(this)
        val lease = fixture.service.acquire(owner)
        val helper = fixture.service.create(lease, null, TARGET, "Helper")
        fixture.service.prompt(helper, prompt)
        fixture.host.results[prompt.request] = HelperResult(prompt.request, HelperOutcome.Unknown, "")
        assertEquals(HelperReleaseResult.Released, lease.release())
        assertEquals(0, fixture.active)
        val restored = fixture.service.acquire(owner, existing = helper)
        assertEquals(HelperOutcome.Unknown, fixture.service.result(helper, prompt.request)?.outcome)
        val recovery = prompt.copy(request = RequestId("recovery"), isRecovery = true)
        fixture.service.prompt(helper, recovery)
        assertEquals(listOf(prompt, recovery), fixture.host.prompts)
        assertEquals(HelperReleaseResult.Released, restored.release())
        assertEquals(0, fixture.active)
    }

    @Test
    fun `cleanup continues after its caller stops waiting`() = runTest {
        val fixture = HelpersFixture(this)
        val gate = CompletableDeferred<Unit>()
        fixture.host.cancellation = {
            gate.await()
            HelperCancellation.Terminal(HelperResult(it, HelperOutcome.Cancelled, ""))
        }
        val lease = fixture.service.acquire(owner)
        val helper = fixture.service.create(lease, null, TARGET, "Helper")
        fixture.service.prompt(helper, prompt)
        val closing = async { lease.release() }
        runCurrent()
        closing.cancelAndJoin()
        assertEquals(1, fixture.active)
        gate.complete(Unit)
        runCurrent()
        assertEquals(0, fixture.active)
        assertEquals(HelperReleaseResult.Released, lease.release())
    }

    @Test
    fun `release racing creation waits without permitting a later prompt`() = runTest {
        val fixture = HelpersFixture(this)
        val gate = CompletableDeferred<Unit>()
        fixture.host.beforeCreate = { gate.await() }
        val lease = fixture.service.acquire(owner)
        val creating = async { fixture.service.create(lease, null, TARGET, "Helper") }
        runCurrent()
        val closing = async { lease.release() }
        runCurrent()
        assertFalse(closing.isCompleted)
        gate.complete(Unit)
        val helper = creating.await()
        assertEquals(HelperReleaseResult.Released, closing.await())
        assertTrue(fixture.host.prompts.isEmpty())
        assertFailsWith<IllegalStateException> { fixture.service.prompt(helper, prompt) }
    }

    @Test
    fun `recovery verifies ownership prevents duplicate leases and retains unresolved requests`() = runTest {
        val fixture = HelpersFixture(this)
        val helper = HelperId("persisted")
        fixture.host.metadata[helper] = HelperMetadata(helper, owner, SESSION, OTHER, prompt.request)
        assertFailsWith<IllegalStateException> { fixture.service.acquire(ActionId("wrong"), SESSION, helper) }
        assertFailsWith<IllegalStateException> { fixture.service.acquire(owner, null, helper) }
        assertEquals(0, fixture.active)
        val lease = fixture.service.acquire(owner, SESSION, helper)
        assertFailsWith<IllegalStateException> { fixture.service.acquire(owner, SESSION, helper) }
        assertEquals(1, fixture.active)
        assertFailsWith<IllegalStateException> { fixture.service.prompt(helper, prompt) }
        fixture.service.cancel(helper, prompt.request)
        fixture.service.prompt(helper, prompt.copy(request = RequestId("recovery"), isRecovery = true))
        assertEquals(HelperReleaseResult.Released, lease.release())
    }

    @Test
    fun `cancel reaches a host waiting for permission before joining its submission`() = runTest {
        val fixture = HelpersFixture(this)
        val gate = CompletableDeferred<Unit>()
        fixture.host.beforePrompt = { gate.await() }
        fixture.host.cancellation = { HelperCancellation.NotSubmitted(it) }
        val lease = fixture.service.acquire(owner)
        val helper = fixture.service.create(lease, null, TARGET, "Helper")
        val pending = async { fixture.service.prompt(helper, prompt) }
        runCurrent()
        assertEquals(HelperCancellation.NotSubmitted(prompt.request), fixture.service.cancel(helper, prompt.request))
        assertFailsWith<CancellationException> { pending.await() }
        assertFalse(gate.isCompleted)
        assertEquals(HelperReleaseResult.Released, lease.release())
    }

    @Test
    fun `release joins the original submission after an earlier unconfirmed cancel`() = runTest {
        val fixture = HelpersFixture(this)
        val gate = CompletableDeferred<Unit>()
        fixture.host.beforePrompt = { gate.await() }
        fixture.host.cancellation = { HelperCancellation.Unconfirmed(it) }
        val lease = fixture.service.acquire(owner)
        val helper = fixture.service.create(lease, null, TARGET, "Helper")
        val pending = async { fixture.service.prompt(helper, prompt) }
        runCurrent()
        assertEquals(HelperCancellation.Unconfirmed(prompt.request), fixture.service.cancel(helper, prompt.request))
        assertFalse(pending.isCompleted)
        fixture.host.cancellation = { HelperCancellation.NotSubmitted(it) }
        assertEquals(HelperReleaseResult.Released, lease.release())
        assertFailsWith<CancellationException> { pending.await() }
        assertEquals(0, fixture.active)
    }

    @Test
    fun `creation reporting native work retains it for cleanup instead of freeing the slot`() = runTest {
        val fixture = HelpersFixture(this)
        fixture.host.createdRequest = prompt.request
        fixture.host.cancellation = { HelperCancellation.Unconfirmed(it) }
        val lease = fixture.service.acquire(owner)
        assertFailsWith<IllegalStateException> { fixture.service.create(lease, null, TARGET, "Unexpected run") }
        assertEquals(HelperReleaseResult.Unconfirmed, lease.release())
        assertEquals(1, fixture.active)
        assertEquals(listOf(prompt.request), fixture.host.cancellations)
        fixture.host.cancellation = { HelperCancellation.NotSubmitted(it) }
        assertEquals(HelperReleaseResult.Released, lease.release())
        assertEquals(0, fixture.active)
    }

    @Test
    fun `creation with temporarily unreadable metadata can reconcile cleanup later`() = runTest {
        val fixture = HelpersFixture(this)
        fixture.host.beforeMetadata = { error("Metadata unavailable") }
        val lease = fixture.service.acquire(owner)
        assertFailsWith<IllegalStateException> { fixture.service.create(lease, null, TARGET, "Helper") }
        assertEquals(HelperReleaseResult.Unconfirmed, lease.release())
        assertEquals(1, fixture.active)
        fixture.host.beforeMetadata = {}
        assertEquals(HelperReleaseResult.Released, lease.release())
        assertEquals(0, fixture.active)
    }

    @Test
    fun `closing refuses new cancellation operations while cleanup owns the barrier`() = runTest {
        val fixture = HelpersFixture(this)
        fixture.host.cancellation = { HelperCancellation.Unconfirmed(it) }
        val lease = fixture.service.acquire(owner)
        val helper = fixture.service.create(lease, null, TARGET, "Helper")
        fixture.service.prompt(helper, prompt)
        assertEquals(HelperReleaseResult.Unconfirmed, lease.release())
        assertFailsWith<IllegalStateException> { fixture.service.cancel(helper, prompt.request) }
        assertEquals(1, fixture.host.cancellations.size)
    }

    @Test
    fun `recovery refreshes the native attempt after waiting for capacity`() = runTest {
        val fixture = HelpersFixture(this)
        val helper = HelperId("persisted")
        fixture.host.metadata[helper] = HelperMetadata(helper, owner, null, null, null)
        repeat(8) {
            fixture.actions.capacity.acquireHelper(ActionId("held$it"), ActionId("other${it / 4}"), null)
        }
        val recovery = async { fixture.service.acquire(owner, null, helper) }
        runCurrent()
        assertFalse(recovery.isCompleted)
        fixture.host.metadata[helper] = checkNotNull(fixture.host.metadata[helper]).copy(lastRequest = prompt.request)
        fixture.actions.capacity.release(ActionId("held0"))
        val lease = recovery.await()
        assertFailsWith<IllegalStateException> { fixture.service.prompt(helper, prompt) }
        assertEquals(HelperReleaseResult.Released, lease.release())
        assertEquals(listOf(prompt.request), fixture.host.cancellations)
    }

    @Test
    fun `cancelling queued recovery removes its claim so the same helper can retry`() = runTest {
        val fixture = HelpersFixture(this)
        val helper = HelperId("persisted")
        fixture.host.metadata[helper] = HelperMetadata(helper, owner, null, null, null)
        repeat(8) {
            fixture.actions.capacity.acquireHelper(ActionId("held$it"), ActionId("other${it / 4}"), null)
        }
        val waiting = async { fixture.service.acquire(owner, null, helper) }
        runCurrent()
        waiting.cancelAndJoin()
        fixture.actions.capacity.release(ActionId("held0"))
        val lease = fixture.service.acquire(owner, null, helper)
        assertEquals(8, fixture.active)
        assertEquals(HelperReleaseResult.Released, lease.release())
        assertEquals(7, fixture.active)
    }

    @Test
    fun `ambiguous recovery and foreign leases are refused before native work`() = runTest {
        val first = HelperHostFake()
        val second = HelperHostFake()
        val helper = HelperId("ambiguous")
        val metadata = HelperMetadata(helper, owner, null, null, null)
        first.metadata[helper] = metadata
        second.metadata[helper] = metadata
        val fixture = HelpersFixture(this, first, setOf(first, second))
        assertFailsWith<IllegalStateException> { fixture.service.acquire(owner, null, helper) }
        assertEquals(0, fixture.active)
        val other = HelpersFixture(this)
        val foreign = other.service.acquire(owner)
        assertFailsWith<IllegalStateException> { fixture.service.create(foreign, null, TARGET, "Invalid") }
        assertEquals(HelperReleaseResult.Released, foreign.release())
    }

    @Test
    fun `failed creation does not fall through to another host`() = runTest {
        val first = HelperHostFake()
        first.beforeCreate = { error("Creation failed") }
        val second = HelperHostFake()
        val preferred = object : ScheduledSessionHost by first {
            override val priority = 200
        }
        val fixture = HelpersFixture(this, first, setOf(second, preferred))
        val lease = fixture.service.acquire(owner)
        assertFailsWith<IllegalStateException> { fixture.service.create(lease, null, TARGET, "Helper") }
        assertTrue(second.metadata.isEmpty())
        assertEquals(HelperReleaseResult.Released, lease.release())
    }

    @Test
    fun `different-request results or barriers cannot release unresolved work`() = runTest {
        val fixture = HelpersFixture(this)
        val lease = fixture.service.acquire(owner)
        val helper = fixture.service.create(lease, null, TARGET, "Helper")
        fixture.service.prompt(helper, prompt)
        fixture.host.results[prompt.request] = HelperResult(RequestId("other"), HelperOutcome.Completed, "unrelated")
        assertEquals(HelperReleaseResult.Unconfirmed, lease.release())
        assertEquals(1, fixture.active)
        fixture.host.results.clear()
        fixture.host.cancellation = { HelperCancellation.NotSubmitted(RequestId("other")) }
        assertEquals(HelperReleaseResult.Unconfirmed, lease.release())
        assertEquals(1, fixture.active)
    }
}

internal class HelpersFixture(
    scope: TestScope,
    val host: HelperHostFake = HelperHostFake(),
    hosts: Set<ScheduledSessionHost> = setOf(host),
) {
    private val profile = TestScopeHandle(
        CoroutineScope(
            scope.backgroundScope.coroutineContext + SupervisorJob(scope.backgroundScope.coroutineContext[Job]),
        ),
    )
    val actions = ActionsFixture(scope, SpecMachine(), profile = profile)
    val service = ProfileHelperAgents(profile, lazyOf(hosts), actions.capacity, actions.results)
    val active: Int get() = (actions.capacityMachine.state.value as BackgroundCapacityState.Ready).active.size
}

internal class HelperHostFake : ScheduledSessionHost by FakeHost(100) {
    val metadata = mutableMapOf<HelperId, HelperMetadata>()
    val prompts = mutableListOf<HelperPrompt>()
    val cancellations = mutableListOf<RequestId>()
    val results = mutableMapOf<RequestId, HelperResult>()
    var createdRequest: RequestId? = null
    var beforeMetadata: suspend () -> Unit = {}
    var beforeCreate: suspend () -> Unit = {}
    var beforePrompt: suspend () -> Unit = {}
    var beforeResult: suspend () -> Unit = {}
    var cancellation: suspend (RequestId) -> HelperCancellation = {
        HelperCancellation.Terminal(HelperResult(it, HelperOutcome.Cancelled, ""))
    }

    override suspend fun canHostHelper(parent: io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef?) = true

    override suspend fun createHelper(request: HelperCreateRequest): HelperId {
        beforeCreate()
        val id = HelperId("helper${metadata.size}")
        metadata[id] = HelperMetadata(id, request.owner, request.parent, null, createdRequest)
        return id
    }

    override suspend fun helperMetadata(helper: HelperId): HelperMetadata? {
        beforeMetadata()
        return metadata[helper]
    }

    override suspend fun helperMetadata(
        session: io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef,
    ): HelperMetadata? = metadata.values.singleOrNull { it.session == session }

    override suspend fun ownedHelpers(owner: ActionId, after: HelperId?, limit: Int): List<HelperMetadata> =
        metadata.values.filter { it.owner == owner && (after == null || it.id.value > after.value) }
            .sortedBy { it.id.value }.take(limit)

    override suspend fun promptHelper(helper: HelperId, prompt: HelperPrompt): HelperSubmission {
        metadata[helper] = checkNotNull(metadata[helper]).copy(session = OTHER, lastRequest = prompt.request)
        prompts += prompt
        beforePrompt()
        return HelperSubmission.Accepted(prompt.request, OTHER)
    }

    override suspend fun helperResult(helper: HelperId, request: RequestId): HelperResult? {
        beforeResult()
        return results[request]
    }

    override suspend fun cancelHelper(helper: HelperId, request: RequestId): HelperCancellation {
        cancellations += request
        return cancellation(request)
    }

    override suspend fun isHelper(session: io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef): Boolean =
        metadata.values.any { it.session == session }
}
