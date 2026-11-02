package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCursor
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionAuthority
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionInstance
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionQuery
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.AttachesSessions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionExecutionIntegrationTest {
    private val binding = SessionExecutionBinding(SessionExecutionInstance("host", "first"))

    @Test
    fun `original payload and actual model are checked before native request entry`() = runTest {
        val env = ExecutionEnvironment(this)
        val session = env.launcher.create(CreateSessionRequest(env.routes.target, execution = binding))
        val sender = assertIs<FeatureAccess.Available<SendsPrompts>>(session.features.resolve(SendsPrompts)).feature
        val intended = PromptRequest(RequestId("original"), listOf(ContentPart.Text("frozen work")))
        val checked = mutableListOf<EngineTarget>()
        env.permitsPrompt = { target, request ->
            checked += target
            request == intended && target == env.routes.target
        }
        assertFailsWith<IllegalStateException> {
            sender.send(intended.copy(parts = listOf(ContentPart.Text("changed work"))))
        }
        assertEquals(0, env.entries)
        assertTrue(env.runtimes.single().sessions.single().sent.isEmpty())
        sender.send(intended)
        assertEquals(listOf(env.routes.target, env.routes.target), checked)
        assertEquals(1, env.entries)
        assertEquals(1, env.runtimes.single().sessions.single().sent.size)
    }

    @Test
    fun `cached handles and metadata-free resume cannot revive a retired executor`() = runTest {
        val env = ExecutionEnvironment(this)
        val session = env.launcher.create(CreateSessionRequest(env.routes.target, execution = binding))
        env.admissions.fence(binding) { env.isPermitted = false }
        val sender = assertIs<FeatureAccess.Available<SendsPrompts>>(session.features.resolve(SendsPrompts)).feature
        assertFailsWith<IllegalStateException> {
            sender.send(PromptRequest(RequestId("new"), listOf(ContentPart.Text("work"))))
        }
        assertFailsWith<IllegalStateException> {
            env.launcher.resume(session.ref, ResumeSessionRequest(env.routes.target))
        }
        assertTrue(env.runtimes.single().sessions.single().sent.isEmpty())
        assertEquals(1, env.runtimes.single().sessions.size)
    }

    @Test
    fun `late open remains accounted until its handle is closed after retirement`() = runTest {
        val env = ExecutionEnvironment(this)
        val opening = CompletableDeferred<Unit>()
        env.beforeOpen = opening
        val result = async {
            assertFailsWith<IllegalStateException> {
                env.launcher.create(CreateSessionRequest(env.routes.target, execution = binding))
            }
        }
        runCurrent()
        assertEquals(1, env.runtimes.single().sessions.size)
        env.admissions.fence(binding) { env.isPermitted = false }
        val drained = async { env.admissions.awaitAdmissions(binding) }
        runCurrent()
        assertFalse(drained.isCompleted)
        opening.complete(Unit)
        result.await()
        drained.await()
        val native = env.runtimes.single().sessions.single()
        assertEquals(1, native.closes)
        assertEquals(binding, env.bindings.find(native.ref))
        assertTrue(native.sent.isEmpty())
    }

    @Test
    fun `binding write and native cleanup failure retain authority until durable retry`() = runTest {
        val env = ExecutionEnvironment(this)
        env.bindings.completeFailure = IllegalStateException("disk unavailable")
        env.bindings.onComplete = {
            env.runtimes.single().sessions.single().closeFailure = IllegalStateException("close unavailable")
        }
        assertFailsWith<IllegalStateException> {
            env.launcher.create(CreateSessionRequest(env.routes.target, execution = binding))
        }
        val native = env.runtimes.single().sessions.single()
        assertEquals(setOf(native.ref), env.access.references(binding))
        assertEquals(1, env.bindings.pending().size)
        assertFailsWith<IllegalStateException> {
            env.launcher.resume(native.ref, ResumeSessionRequest(env.routes.target))
        }
        env.access.fence(binding) { env.isPermitted = false }
        assertFailsWith<IllegalStateException> { env.access.awaitAdmissions(binding) }
        env.bindings.completeFailure = null
        env.bindings.onComplete = {}
        env.access.awaitAdmissions(binding)
        assertEquals(binding, env.bindings.find(native.ref))
        assertTrue(env.bindings.pending().isEmpty())
        assertFailsWith<IllegalStateException> {
            env.launcher.resume(native.ref, ResumeSessionRequest(env.routes.target))
        }
    }

    @Test
    fun `missing authority rejects creation before runtime launch and replacement has a different binding`() = runTest {
        val env = ExecutionEnvironment(this)
        env.isPermitted = false
        assertFailsWith<IllegalStateException> {
            env.launcher.create(CreateSessionRequest(env.routes.target, execution = binding))
        }
        assertTrue(env.runtimes.isEmpty())
        env.admissions.fence(binding) { Unit }
        env.isPermitted = true
        val replacement = binding.copy(instance = binding.instance.copy(id = "second"))
        val session = env.launcher.create(CreateSessionRequest(env.routes.target, execution = replacement))
        assertEquals(replacement, env.bindings.find(session.ref))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class ExecutionEnvironment(scope: TestScope) {
    var isPermitted = true
    var permitsPrompt: (EngineTarget, PromptRequest) -> Boolean = { _, _ -> true }
    var entries = 0
    var beforeOpen: CompletableDeferred<Unit>? = null
    val runtimes = mutableListOf<FakeRuntime>()
    val bindings = ExecutionTestBindings()
    val admissions = SessionExecutionAdmissions(
        lazyOf(
            setOf(object : SessionExecutionAuthority {
                override val provider = "host"
                override suspend fun permits(binding: SessionExecutionBinding) = isPermitted
                override suspend fun permitsPrompt(
                    binding: SessionExecutionBinding,
                    session: SessionRef,
                    target: EngineTarget,
                    request: PromptRequest,
                ) = permitsPrompt(target, request)

                override suspend fun enterPrompt(
                    binding: SessionExecutionBinding,
                    session: SessionRef,
                    request: RequestId,
                ): Boolean {
                    entries++
                    return isPermitted
                }
            }),
        ),
    )
    val access = SessionExecutionAccess(bindings, admissions)
    private val factory = FakeEngineFactory().apply {
        runtime = { FakeRuntime(it).also { native -> runtimes += native } }
    }
    val routes = RouteFixture(
        scope,
        factory,
        registration(factory, features = setOf(CreatesSessions.id, AttachesSessions.id)),
    )
    private val enabled = EnabledEngines(routes.registry, routes.toggles, scope.backgroundScope)
    private val sessions = SessionCatalogService(
        enabled,
        RecordingSessionIndex(),
        ExecutionCursors,
        routes.context,
    ) { _, stored -> stored }
    private val handles = ActiveSessionRegistry()
    private val policy = SessionPolicy(routes.routes, enabled, handles, routes.context, executions = access)
    private val assembler = ActiveSessionAssembler(policy, scope.backgroundScope)
    private var nextHandle = 0
    private val host = ActiveSessionHost { native, route, model, options ->
        beforeOpen?.await()
        assembler.assemble(
            native,
            route,
            model,
            TestHandleScope("execution-${++nextHandle}", scope.backgroundScope),
            options.areSessionHooksEnabled,
            options.logicalSession,
            options.execution,
        )
    }
    private val pool = RuntimePool(routes.context, handles::hasActiveTurn, handles::closeHandles)
    val launcher = SessionLauncher(routes.routes, pool, sessions, host, routes.context, access)
}

private object ExecutionCursors : SessionCursors {
    override fun encode(query: SessionQuery, revision: Long, position: IndexPosition) = error("No paging")
    override fun decode(cursor: SessionCursor, query: SessionQuery) = error("No paging")
}
