@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnStop
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCursor
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionQuery
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.StopsOwnedTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OwnedTurnStopFacadeTest {
    @Test
    fun `stop carries exact reference request workspace and turn without opening or indexing a session`() = runTest {
        val fixture = StopFixture(this)
        runCurrent()
        val capability = fixture.capability()
        val access = OwnedTurnAccess(fixture.routes.target, WorkspaceRef("project"), TurnId("turn"))
        fixture.result = OwnedTurnStop.Confirmed(Turn(TurnId("turn"), Request, access.target, TurnOutcome.Unknown))
        assertEquals(fixture.result, capability.stop(Ref, Request, access))
        assertEquals(Triple(Ref, Request, access), fixture.calls.single())
        assertEquals(1, fixture.factory.createdRuntimes.size)
        assertTrue(fixture.index.entries.isEmpty())
        assertEquals(OwnedTurnStop.Unconfirmed, capability.stop(Ref, RequestId("other"), access))
        assertEquals(1, fixture.factory.createdRuntimes.size)
    }

    @Test
    fun `model selection changes cannot prevent cancelling an earlier accepted turn`() = runTest {
        val fixture = StopFixture(this)
        fixture.factory.accepts = { _, context -> context.model == null }
        runCurrent()
        val original = Turn(TurnId("turn"), Request, fixture.routes.target, TurnOutcome.Completed)
        fixture.result = OwnedTurnStop.Confirmed(original)
        val access = OwnedTurnAccess(fixture.routes.target.copy(model = ModelId("new-selection")))
        assertEquals(OwnedTurnStop.Confirmed(original), fixture.capability().stop(Ref, Request, access))
    }

    @Test
    fun `undeclared and unsupported runtime capabilities never fall back to resume`() = runTest {
        val undeclared = StopFixture(this, isDeclared = false)
        runCurrent()
        assertEquals(
            FeatureAccess.Unsupported,
            undeclared.capabilities.engine(undeclared.routes.registry.require(TestEngine)).resolve(StopsOwnedTurns),
        )
        assertTrue(undeclared.factory.createdRuntimes.isEmpty())
        val unsupported = StopFixture(this, isSupported = false)
        runCurrent()
        val failure = assertFailsWith<EngineException> {
            unsupported.capability().stop(Ref, Request, OwnedTurnAccess(unsupported.routes.target))
        }
        assertEquals(EngineFailure.Engine(EngineFailureReason.UnsupportedCapability), failure.failure)
        assertTrue(unsupported.index.entries.isEmpty())
    }

    @Test
    fun `foreign engine missing binding or disabled route cannot reach the stop adapter`() = runTest {
        val fixture = StopFixture(this)
        runCurrent()
        val capability = fixture.capability()
        val access = OwnedTurnAccess(fixture.routes.target)
        assertFailsWith<EngineException> { capability.stop(Ref.copy(engine = EngineId("other")), Request, access) }
        assertFailsWith<EngineException> {
            capability.stop(Ref, Request, access.copy(target = access.target.copy(engine = EngineId("other"))))
        }
        val missing = access.copy(target = access.target.copy(binding = EngineBindingId("missing")))
        assertFailsWith<EngineException> { capability.stop(Ref, Request, missing) }
        fixture.routes.store.bindings.value = listOf(fixture.routes.binding.copy(isEnabled = false))
        assertFailsWith<EngineException> { capability.stop(Ref, Request, access) }
        fixture.routes.store.bindings.value = listOf(fixture.routes.binding)
        fixture.routes.toggles.disabled.value = setOf(TestEngine)
        assertFailsWith<EngineException> { capability.stop(Ref, Request, access) }
        assertTrue(fixture.calls.isEmpty())
        assertTrue(fixture.factory.createdRuntimes.isEmpty())
    }

    @Test
    fun `credential revision changed while creating runtime is rejected before stop`() = runTest {
        val fixture = StopFixture(this)
        runCurrent()
        val gate = CompletableDeferred<Unit>().also { fixture.factory.createGate = it }
        val stopping = async {
            assertFailsWith<EngineException> {
                fixture.capability().stop(Ref, Request, OwnedTurnAccess(fixture.routes.target))
            }
        }
        runCurrent()
        fixture.routes.sources.remove(fixture.routes.source.info.id)
        fixture.routes.sources.add(managedKey(revision = AuthRevision.Known("r2")))
        gate.complete(Unit)
        stopping.await()
        assertTrue(fixture.calls.isEmpty())
        assertTrue(fixture.index.entries.isEmpty())
    }

    @Test
    fun `an adapter cannot confirm another request turn or target`() = runTest {
        val fixture = StopFixture(this)
        runCurrent()
        val access = OwnedTurnAccess(fixture.routes.target, expectedTurn = TurnId("expected"))
        val expected = Turn(TurnId("expected"), Request, access.target, TurnOutcome.Completed)
        for (wrong in listOf(
            expected.copy(request = RequestId("foreign")),
            expected.copy(id = TurnId("foreign")),
            expected.copy(target = access.target.copy(binding = EngineBindingId("foreign"))),
        )) {
            fixture.result = OwnedTurnStop.Confirmed(wrong)
            val error = assertFailsWith<EngineException> { fixture.capability().stop(Ref, Request, access) }
            assertEquals(EngineFailure.Transport(TransportFailureReason.ProtocolViolation), error.failure)
        }
    }

    @Test
    fun `source reference and workspace are passed unchanged for adapter ownership checks`() = runTest {
        val fixture = StopFixture(this)
        runCurrent()
        val access = OwnedTurnAccess(fixture.routes.target, WorkspaceRef("foreign"))
        val ref = Ref.copy(source = SessionSourceId("foreign"))
        assertEquals(OwnedTurnStop.Unconfirmed, fixture.capability().stop(ref, Request, access))
        assertEquals(Triple(ref, Request, access), fixture.calls.single())
        assertTrue(fixture.index.entries.isEmpty())
    }

    private class StopFixture(scope: TestScope, isDeclared: Boolean = true, isSupported: Boolean = true) {
        val factory = FakeEngineFactory()
        val routes = RouteFixture(
            scope,
            factory,
            registration(factory, features = if (isDeclared) setOf(StopsOwnedTurns.id) else emptySet()),
        )
        val index = RecordingSessionIndex()
        val calls = mutableListOf<Triple<SessionRef, RequestId, OwnedTurnAccess>>()
        var result: OwnedTurnStop = OwnedTurnStop.Unconfirmed
        val capabilities: FacadeCapabilities
        init {
            val enabled = EnabledEngines(routes.registry, routes.toggles, scope.backgroundScope)
            val sessions = SessionCatalogService(enabled, index, UnusedCursors, routes.context) { _, stored -> stored }
            val handles = ActiveSessionRegistry()
            val pool = RuntimePool(routes.context, handles::hasActiveTurn, handles::closeHandles)
            val launcher = SessionLauncher(
                routes.routes,
                pool,
                sessions,
                ActiveSessionHost { _, _, _, _ -> error("Stop must not open a handle") },
                routes.context,
            )
            capabilities = FacadeCapabilities(sessions, lazyOf(launcher), handles)
            factory.runtime = { identity ->
                object : EngineRuntime by FakeRuntime(identity, supports = false) {
                    override val features = FeatureTable(
                        if (isSupported) {
                            mapOf(
                                StopsOwnedTurns.id to available(object : StopsOwnedTurns {
                                    override suspend fun stop(
                                        ref: SessionRef,
                                        request: RequestId,
                                        access: OwnedTurnAccess,
                                    ): OwnedTurnStop {
                                        calls += Triple(ref, request, access)
                                        return if (ref == Ref && request == Request) {
                                            result
                                        } else {
                                            OwnedTurnStop.Unconfirmed
                                        }
                                    }
                                }),
                            )
                        } else {
                            emptyMap()
                        },
                    )
                }
            }
        }

        fun capability(): StopsOwnedTurns = assertIs<FeatureAccess.Available<StopsOwnedTurns>>(
            capabilities.engine(routes.registry.require(TestEngine)).resolve(StopsOwnedTurns),
        ).feature
    }

    private object UnusedCursors : SessionCursors {
        override fun encode(query: SessionQuery, revision: Long, position: IndexPosition): SessionCursor =
            error("Unused")
        override fun decode(cursor: SessionCursor, query: SessionQuery): DecodedCursor? = error("Unused")
    }

    private companion object {
        val Ref = sessionRef("stored")
        val Request = RequestId("request")
    }
}
