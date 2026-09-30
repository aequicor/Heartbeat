package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionEffect
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionIntent
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ChangesSessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfigurationChange
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SwitchesModels
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
class ManagedActiveSessionTest {
    private val handles = ActiveSessionRegistry()
    private var counter = 0

    private fun prompt(id: String) = PromptRequest(RequestId(id), listOf(ContentPart.Text("hello")))

    private fun TestScope.open(
        fixture: RouteFixture,
        native: FakeNativeSession = FakeNativeSession(),
    ): Pair<ActiveSession, TestHandleScope> {
        val policy = SessionPolicy(
            fixture.routes,
            EnabledEngines(fixture.registry, fixture.toggles, backgroundScope),
            handles,
            fixture.context,
        )
        val handle = TestHandleScope("h${++counter}", backgroundScope)
        val route = fixture.store.bindings.value.single().let { binding ->
            io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute(
                TestEngine,
                binding.id,
                fixture.source.info.id,
                fixture.source.info.revision,
            )
        }
        val session = ActiveSessionAssembler(policy, backgroundScope).assemble(native, route, ModelId("m1"), handle)
        runCurrent()
        return session to handle
    }

    private fun ActiveSession.sender() = (features.resolve(SendsPrompts) as FeatureAccess.Available).feature

    private class Configuration : ChangesSessionConfiguration {
        override val configuration = MutableStateFlow(SessionConfiguration(ModelId("m1"), trust = TrustLevel.Ask))
        val changes = mutableListOf<SessionConfigurationChange>()
        override suspend fun apply(operationId: String, change: SessionConfigurationChange): SessionConfiguration {
            changes += change
            val current = configuration.value
            configuration.value = when (change) {
                is SessionConfigurationChange.Model -> current.copy(model = change.model)
                is SessionConfigurationChange.Effort -> current.copy(reasoningEffort = change.effort)
                is SessionConfigurationChange.Trust -> current.copy(trust = change.trust)
            }
            return configuration.value
        }
    }

    @Test
    fun `live configuration preserves an accepted turn and pending permission`() = runTest {
        val configuration = Configuration()
        val native = FakeNativeSession(configuration = configuration)
        val (session, _) = open(RouteFixture(this), native)
        session.sender().send(prompt("live"))
        native.ask("permission")
        runCurrent()
        val before = session.state.value
        val feature = (session.features.resolve(ChangesSessionConfiguration) as FeatureAccess.Available).feature
        val changed = feature.apply("operation", SessionConfigurationChange.Trust(TrustLevel.Full))
        runCurrent()
        assertEquals(TrustLevel.Full, changed.trust)
        assertEquals(before, session.state.value)
        assertTrue(native.cancelled.isEmpty())
        assertTrue(native.decisions.isEmpty())
        assertEquals(1, native.sent.size)
    }

    @Test
    fun `live configuration refuses a different handle while the native session runs`() = runTest {
        val fixture = RouteFixture(this)
        val native = FakeNativeSession(configuration = Configuration())
        val (first, _) = open(fixture, native)
        val other = Configuration()
        val (second, _) = open(fixture, FakeNativeSession(native.ref, configuration = other))
        first.sender().send(prompt("owner"))
        runCurrent()
        val feature = (second.features.resolve(ChangesSessionConfiguration) as FeatureAccess.Available).feature
        val failure = assertFailsWith<EngineException> {
            feature.apply("other", SessionConfigurationChange.Trust(TrustLevel.Full))
        }
        assertEquals(EngineFailure.Session(SessionFailureReason.Busy), failure.failure)
        assertTrue(other.changes.isEmpty())
    }

    @Test
    fun `an acquired configuration capability revalidates rotated credentials`() = runTest {
        val fixture = RouteFixture(this)
        val configuration = Configuration()
        val (session, _) = open(fixture, FakeNativeSession(configuration = configuration))
        val feature = (session.features.resolve(ChangesSessionConfiguration) as FeatureAccess.Available).feature
        fixture.sources.remove(fixture.source.info.id)
        fixture.sources.add(managedKey(revision = AuthRevision.Known("new")))
        assertFailsWith<EngineException> {
            feature.apply("stale", SessionConfigurationChange.Trust(TrustLevel.Full))
        }
        assertTrue(configuration.changes.isEmpty())
    }

    @Test
    fun `send completes after native acceptance and the native outcome finishes the turn`() = runTest {
        val native = FakeNativeSession()
        val (session, _) = open(RouteFixture(this), native)

        val turn = session.sender().send(prompt("r1"))
        runCurrent()

        val running = assertIs<ActiveSessionState.Running>(session.state.value)
        assertEquals(turn, running.turn.id)
        assertEquals(RequestId("r1"), running.turn.request)
        assertEquals(1, native.sent.size)

        native.finish()
        runCurrent()
        assertEquals(TurnOutcome.Completed, assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.outcome)
    }

    @Test
    fun `a trust level is refused by a session that does not apply trust levels`() = runTest {
        val native = FakeNativeSession()
        val (session, _) = open(RouteFixture(this), native)

        val failure = assertFailsWith<EngineException> {
            session.sender().send(prompt("r1").copy(trust = TrustLevel.Full))
        }

        assertEquals(EngineFailure.Request(RequestFailureReason.Invalid), failure.failure)
        assertTrue(native.sent.isEmpty())
        assertIs<ActiveSessionState.Ready>(session.state.value)
    }

    @Test
    fun `a trust level reaches a session that applies trust levels`() = runTest {
        val native = FakeNativeSession(appliesTrust = true)
        val (session, _) = open(RouteFixture(this), native)

        session.sender().send(prompt("r1").copy(trust = TrustLevel.AutoEdits))
        runCurrent()

        assertEquals(TrustLevel.AutoEdits, native.sent.single().trust)
    }

    @Test
    fun `rejected submission fails send and reconciliation restores a ready handle`() = runTest {
        val native = FakeNativeSession()
        val (session, _) = open(RouteFixture(this), native)
        val invalid = EngineFailure.Request(RequestFailureReason.Invalid, RequestId("r1"))
        native.sendFailure = EngineException(invalid)

        assertEquals(invalid, assertFailsWith<EngineException> { session.sender().send(prompt("r1")) }.failure)
        runCurrent()
        assertIs<ActiveSessionState.Unavailable>(session.state.value)

        (session.features.resolve(ReconcilesSession) as FeatureAccess.Available).feature.synchronize()
        runCurrent()
        assertIs<ActiveSessionState.Ready>(session.state.value)
    }

    @Test
    fun `permission decisions reach the native turn and only native acknowledgement resolves them`() = runTest {
        val native = FakeNativeSession()
        val (session, _) = open(RouteFixture(this), native)
        val turn = session.sender().send(prompt("r1"))
        val request = native.ask("p1")
        runCurrent()
        assertIs<ActiveSessionState.AwaitingUserAction>(session.state.value)

        val permissions = (session.features.resolve(RequestsPermissions) as FeatureAccess.Available).feature
        permissions.respond(PermissionDecision(turn, request.id, PermissionOptionId("allow")))
        runCurrent()
        assertEquals(native.activeTurn.id, native.decisions.single().turn)
        assertIs<ActiveSessionState.AwaitingUserAction>(session.state.value)

        val unknown = PermissionDecision(turn, request.id, PermissionOptionId("maybe"))
        assertFailsWith<EngineException> { permissions.respond(unknown) }

        native.resolve(request)
        runCurrent()
        assertIs<ActiveSessionState.Running>(session.state.value)
    }

    @Test
    fun `cancellation waits for the native terminal outcome`() = runTest {
        val native = FakeNativeSession()
        val (session, _) = open(RouteFixture(this), native)
        val turn = session.sender().send(prompt("r1"))

        (session.features.resolve(CancelsTurns) as FeatureAccess.Available).feature.cancel(turn)
        runCurrent()
        assertIs<ActiveSessionState.Interrupting>(session.state.value)
        assertEquals(listOf(native.activeTurn.id), native.cancelled)

        native.finish(TurnOutcome.Cancelled)
        runCurrent()
        assertEquals(TurnOutcome.Cancelled, assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.outcome)
        assertFailsWith<EngineException> {
            (session.features.resolve(CancelsTurns) as FeatureAccess.Available).feature.cancel(TurnId("stale"))
        }
    }

    @Test
    fun `a busy native session rejects another turn from any handle`() = runTest {
        val fixture = RouteFixture(this)
        val native = FakeNativeSession()
        val (first, _) = open(fixture, native)
        val (second, _) = open(fixture, FakeNativeSession(native.ref))
        first.sender().send(prompt("r1"))

        val own = assertFailsWith<EngineException> { first.sender().send(prompt("r2")) }
        val other = assertFailsWith<EngineException> { second.sender().send(prompt("r3")) }
        assertEquals(EngineFailure.Session(SessionFailureReason.Busy), own.failure)
        assertEquals(EngineFailure.Session(SessionFailureReason.Busy), other.failure)
    }

    @Test
    fun `a rotated source blocks the next turn before anything is sent`() = runTest {
        val fixture = RouteFixture(this)
        val native = FakeNativeSession()
        val (session, _) = open(fixture, native)
        fixture.sources.remove(fixture.source.info.id)
        fixture.sources.add(managedKey(revision = AuthRevision.Known("r2")))

        val error = assertFailsWith<EngineException> { session.sender().send(prompt("r1")) }
        assertEquals(authFailure(AuthFailureReason.SourceChanged, fixture.source.info.id), error.failure)
        assertTrue(native.sent.isEmpty())
    }

    @Test
    fun `close releases the native lease and ends the handle scope`() = runTest {
        val native = FakeNativeSession()
        val (session, handle) = open(RouteFixture(this), native)
        assertTrue(handles.isInUse(session.route.binding))

        session.close()
        runCurrent()

        assertEquals(ActiveSessionState.Closed, session.state.value)
        assertEquals(1, native.closes)
        assertTrue(handle.isClosed)
        assertFalse(handles.isInUse(session.route.binding))
        assertEquals(
            FeatureAccess.Unavailable(EngineFailure.Lifecycle(LifecycleFailureReason.SessionClosed)),
            session.features.resolve(SendsPrompts),
        )
        session.close()
    }

    @Test
    fun `turns started elsewhere are adopted through reconciliation`() = runTest {
        val native = FakeNativeSession()
        val (session, _) = open(RouteFixture(this), native)

        native.native.value = ActiveSessionState.Running(
            io.aequicor.heartbeat.feature.aiengine.facade.api.Turn(TurnId("external"), null, TestTarget),
        )
        runCurrent()

        assertEquals(TurnId("external"), assertIs<ActiveSessionState.Running>(session.state.value).turn.id)
    }

    @Test
    fun `profile shutdown releases waiters instead of hanging them`() = runTest {
        val native = FakeNativeSession().apply { closeGate = kotlinx.coroutines.CompletableDeferred() }
        val (session, handle) = open(RouteFixture(this), native)

        val closing = async { assertFailsWith<EngineException> { session.close() } }
        runCurrent()
        handle.close()
        runCurrent()

        val failure = closing.await()
        assertEquals(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed), failure.failure)
    }

    @Test
    fun `model switches are serialized with turns of every handle`() = runTest {
        val fixture = RouteFixture(this)
        val native = FakeNativeSession()
        val (first, _) = open(fixture, native)
        val otherNative = FakeNativeSession(native.ref)
        val (second, _) = open(fixture, otherNative)
        val switcher = (second.features.resolve(SwitchesModels) as FeatureAccess.Available).feature

        first.sender().send(prompt("r1"))
        assertEquals(
            EngineFailure.Session(SessionFailureReason.Busy),
            assertFailsWith<EngineException> { switcher.switchTo(ModelId("m1")) }.failure,
        )

        native.finish()
        runCurrent()
        switcher.switchTo(ModelId("m1"))
        assertEquals(listOf(ModelId("m1")), otherNative.models)
    }

    @Test
    fun `a native turn published before send returns is not treated as lost while submitting`() = runTest {
        // Adapter without request correlation: the native turn is visible under its own id before send returns.
        val native = FakeNativeSession().apply { isCorrelationOnSend = false }
        val (session, _) = open(RouteFixture(this), native)

        val turn = session.sender().send(prompt("r1"))
        runCurrent()

        assertEquals(turn, assertIs<ActiveSessionState.Running>(session.state.value).turn.id)
        native.finish()
        runCurrent()
        assertEquals(turn, assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.id)
    }

    @Test
    fun `commands after profile shutdown fail as ProfileClosed instead of leaking a foreign cancellation`() = runTest {
        val commands = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        val effects = ActiveSessionEffects(FakeNativeSession(), commands)
        commands.cancel()
        val sent = mutableListOf<ActiveSessionIntent>()
        val machine = object : EffectScope<ActiveSessionIntent> {
            override suspend fun send(intent: ActiveSessionIntent): SendResult {
                sent += intent
                return SendResult.Accepted
            }
        }
        val request = prompt("r1")
        val turn = Turn(TurnId("t1"), request.id, TestTarget)

        val error = assertFailsWith<EngineException> {
            effects.handle(ActiveSessionEffect.Submit(request, turn), machine)
        }

        assertEquals(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed), error.failure)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a command still running when the profile scope ends fails as ProfileClosed`() = runTest {
        val commands = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        val native = FakeNativeSession().apply { closeGate = kotlinx.coroutines.CompletableDeferred() }
        val effects = ActiveSessionEffects(native, commands)
        val sent = mutableListOf<ActiveSessionIntent>()

        val releasing = async {
            assertFailsWith<EngineException> { effects.handle(ActiveSessionEffect.Release, recording(sent)) }
        }
        runCurrent()
        assertEquals(1, native.closes)
        commands.cancel()
        runCurrent()

        assertEquals(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed), releasing.await().failure)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a native failure coinciding with the end of the profile scope is reported as itself`() = runTest {
        val commands = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        val invalid = EngineFailure.Request(RequestFailureReason.Invalid)
        val native = FakeNativeSession().apply {
            onSend = { commands.cancel() }
            sendFailure = EngineException(invalid)
        }
        val effects = ActiveSessionEffects(native, commands)
        val sent = mutableListOf<ActiveSessionIntent>()
        val request = prompt("r1")
        val submit = ActiveSessionEffect.Submit(request, Turn(TurnId("t1"), request.id, TestTarget))

        val error = assertFailsWith<EngineException> { effects.handle(submit, recording(sent)) }

        assertEquals(invalid, error.failure)
        assertTrue(sent.isEmpty())
    }

    private fun recording(sent: MutableList<ActiveSessionIntent>) = object : EffectScope<ActiveSessionIntent> {
        override suspend fun send(intent: ActiveSessionIntent): SendResult {
            sent += intent
            return SendResult.Accepted
        }
    }
}
