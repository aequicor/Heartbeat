package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PiRuntimeTest {
    @Test
    fun `foreign engine store and requested engine are not resumable before native IO`() = runTest {
        val fixture = runtimeFixture(this)
        val foreign = EngineId("foreign")
        listOf(
            RuntimeRef.copy(engine = foreign) to RuntimeRequest,
            RuntimeRef.copy(source = SessionSourceId("foreign")) to RuntimeRequest,
            RuntimeRef to RuntimeRequest.copy(target = RuntimeTarget.copy(engine = foreign)),
        ).forEach { (ref, request) ->
            val error = assertFailsWith<EngineException> { fixture.runtime.attach(ref, request) }
            assertEquals(EngineFailure.Session(SessionFailureReason.NotResumable), error.failure)
        }
        assertEquals(0, fixture.processes.transcriptReads)
        assertEquals(emptyList(), fixture.processes.connections)
        fixture.runtime.close()
    }

    @Test
    fun `missing transcript releases reservation so a later attach can succeed`() = runTest {
        val fixture = runtimeFixture(this)
        fixture.processes.transcript = { null }
        val error = assertFailsWith<EngineException> { fixture.runtime.attach(RuntimeRef, RuntimeRequest) }
        assertEquals(EngineFailure.Session(SessionFailureReason.NotFound), error.failure)
        assertEquals(emptyList(), fixture.processes.connections)

        fixture.processes.transcript = { "native.jsonl" }
        val session = fixture.runtime.attach(RuntimeRef, RuntimeRequest)
        assertEquals(RuntimeRef, session.ref)
        assertTrue("switch_session" in fixture.processes.connections.single().commands)
        fixture.runtime.close()
    }

    @Test
    fun `concurrent attachment reserves the transcript before lookup and startup`() = runTest {
        val fixture = runtimeFixture(this)
        val lookup = CompletableDeferred<String?>()
        fixture.processes.transcript = { lookup.await() }
        val first = async { fixture.runtime.attach(RuntimeRef, RuntimeRequest) }
        runCurrent()

        val second = assertFailsWith<EngineException> { fixture.runtime.attach(RuntimeRef, RuntimeRequest) }
        assertEquals(EngineFailure.Session(SessionFailureReason.Busy), second.failure)
        assertEquals(1, fixture.processes.transcriptReads)
        assertEquals(emptyList(), fixture.processes.connections)
        lookup.complete("native.jsonl")
        assertEquals(RuntimeRef, first.await().ref)
        assertEquals(1, fixture.processes.connections.size)
        fixture.runtime.close()
    }

    @Test
    fun `reservation remains held while process startup is suspended`() = runTest {
        val fixture = runtimeFixture(this)
        val startup = CompletableDeferred<Unit>()
        fixture.processes.beforeStart = { startup.await() }
        val first = async { fixture.runtime.attach(RuntimeRef, RuntimeRequest) }
        runCurrent()
        val second = assertFailsWith<EngineException> { fixture.runtime.attach(RuntimeRef, RuntimeRequest) }
        assertEquals(EngineFailure.Session(SessionFailureReason.Busy), second.failure)
        assertEquals(1, fixture.processes.transcriptReads)
        startup.complete(Unit)
        first.await()
        assertEquals(1, fixture.processes.connections.size)
        fixture.runtime.close()
    }

    @Test
    fun `detached session keeps its transcript busy until the accepted turn settles`() = runTest {
        val fixture = runtimeFixture(this)
        val first = fixture.runtime.attach(RuntimeRef, RuntimeRequest)
        val connection = fixture.processes.connections.single()
        connection.promptAck.complete(JsonObject(emptyMap()))
        val prompts = assertIs<FeatureAccess.Available<SendsPrompts>>(first.features.resolve(SendsPrompts)).feature
        prompts.send(prompt("accepted"))
        first.close()
        val second = assertFailsWith<EngineException> { fixture.runtime.attach(RuntimeRef, RuntimeRequest) }
        assertEquals(EngineFailure.Session(SessionFailureReason.Busy), second.failure)
        assertEquals(1, fixture.processes.connections.size)
        assertFalse(connection.isClosed)

        connection.event(record("""{"type":"agent_settled"}"""))
        assertTrue(connection.isClosed)
        assertEquals(RuntimeRef, fixture.runtime.attach(RuntimeRef, RuntimeRequest).ref)
        assertEquals(2, fixture.processes.connections.size)
        fixture.runtime.close()
    }

    @Test
    fun `a live session is shared with later attaches instead of refusing them`() = runTest {
        val fixture = runtimeFixture(this)
        fixture.runtime.attach(RuntimeRef, RuntimeRequest)
        val firstBorrow = fixture.runtime.attach(RuntimeRef, RuntimeRequest)
        val secondBorrow = fixture.runtime.attach(RuntimeRef, RuntimeRequest)
        assertEquals(RuntimeRef, firstBorrow.ref)
        assertEquals(RuntimeRef, secondBorrow.ref)
        assertEquals(1, fixture.processes.connections.size)

        firstBorrow.close()
        secondBorrow.close()
        // The owner still holds the process: a later attach borrows the same live session.
        assertEquals(RuntimeRef, fixture.runtime.attach(RuntimeRef, RuntimeRequest).ref)
        assertEquals(1, fixture.processes.connections.size)
        fixture.runtime.close()
    }

    @Test
    fun `the owner keeps the process until its borrower is gone`() = runTest {
        val fixture = runtimeFixture(this)
        val owner = fixture.runtime.attach(RuntimeRef, RuntimeRequest)
        val borrow = fixture.runtime.attach(RuntimeRef, RuntimeRequest)
        owner.close()
        // The owner is detached, but the borrower keeps the process alive: a later attach still shares it.
        val later = fixture.runtime.attach(RuntimeRef, RuntimeRequest)
        assertEquals(RuntimeRef, later.ref)
        assertEquals(1, fixture.processes.connections.size)

        borrow.close()
        later.close()
        assertEquals(RuntimeRef, fixture.runtime.attach(RuntimeRef, RuntimeRequest).ref)
        assertEquals(2, fixture.processes.connections.size)
        fixture.runtime.close()
    }

    @Test
    fun `failed switch closes the process and releases the reservation`() = runTest {
        val fixture = runtimeFixture(this)
        val failure = EngineFailure.Session(SessionFailureReason.Changed)
        fixture.processes.configure = { it.switchFailure = EngineException(failure) }
        val error = assertFailsWith<EngineException> { fixture.runtime.attach(RuntimeRef, RuntimeRequest) }
        assertEquals(failure, error.failure)
        assertTrue(fixture.processes.connections.single().isClosed)

        fixture.processes.configure = {}
        assertEquals(RuntimeRef, fixture.runtime.attach(RuntimeRef, RuntimeRequest).ref)
        fixture.runtime.close()
    }

    @Test
    fun `cancelled transcript lookup releases reservation for another caller`() = runTest {
        val fixture = runtimeFixture(this)
        val lookup = CompletableDeferred<String?>()
        fixture.processes.transcript = { lookup.await() }
        val cancelled = async { fixture.runtime.attach(RuntimeRef, RuntimeRequest) }
        runCurrent()
        cancelled.cancelAndJoin()

        fixture.processes.transcript = { "native.jsonl" }
        assertEquals(RuntimeRef, fixture.runtime.attach(RuntimeRef, RuntimeRequest).ref)
        assertEquals(1, fixture.processes.connections.size)
        fixture.runtime.close()
    }

    @Test
    fun `cancelled startup closes the late process and releases its reservation`() = runTest {
        val fixture = runtimeFixture(this)
        val startup = CompletableDeferred<Unit>()
        fixture.processes.beforeStart = { startup.await() }
        val cancelled = async { fixture.runtime.attach(RuntimeRef, RuntimeRequest) }
        runCurrent()
        cancelled.cancel()
        startup.complete(Unit)
        cancelled.join()
        assertTrue(fixture.processes.connections.single().isClosed)

        fixture.processes.beforeStart = {}
        assertEquals(RuntimeRef, fixture.runtime.attach(RuntimeRef, RuntimeRequest).ref)
        assertEquals(2, fixture.processes.connections.size)
        fixture.runtime.close()
    }

    @Test
    fun `close during startup rejects attachment and shuts down the late process`() = runTest {
        val fixture = runtimeFixture(this)
        val startup = CompletableDeferred<Unit>()
        fixture.processes.beforeStart = { startup.await() }
        val pending = async {
            assertFailsWith<EngineException> { fixture.runtime.attach(RuntimeRef, RuntimeRequest) }
        }
        runCurrent()
        fixture.runtime.close()
        startup.complete(Unit)
        assertEquals(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed), pending.await().failure)
        assertTrue(fixture.processes.connections.single().isClosed)
    }

    @Test
    fun `another explicit binding of the same source is used without a fallback`() = runTest {
        val fixture = runtimeFixture(this)
        val binding = EngineBindingId("other")
        fixture.settings.bind(binding, RuntimeSource)
        val session = fixture.runtime.attach(
            RuntimeRef,
            RuntimeRequest.copy(target = RuntimeTarget.copy(binding = binding)),
        )
        assertEquals(binding, session.route.binding)
        assertEquals(RuntimeSource.info.id, session.route.authSource)
        fixture.runtime.close()
    }

    @Test
    fun `foreign binding changed credentials and isClosed runtime are rejected before transcript IO`() = runTest {
        val fixture = runtimeFixture(this)
        val binding = EngineBindingId("foreign")
        fixture.settings.bind(binding, RuntimeSource.copy(info = RuntimeSource.info.copy(id = AuthSourceId("other"))))
        val foreign = assertFailsWith<EngineException> {
            fixture.runtime.attach(RuntimeRef, RuntimeRequest.copy(target = RuntimeTarget.copy(binding = binding)))
        }
        assertEquals(
            AuthFailureReason.AuthMismatch,
            assertIs<EngineFailure.Authentication>(foreign.failure).reason.reason,
        )
        fixture.processes.fingerprint = "rotated"
        val changed = assertFailsWith<EngineException> { fixture.runtime.attach(RuntimeRef, RuntimeRequest) }
        assertEquals(
            AuthFailureReason.SourceChanged,
            assertIs<EngineFailure.Authentication>(changed.failure).reason.reason,
        )
        fixture.runtime.close()
        val closedError = assertFailsWith<EngineException> { fixture.runtime.attach(RuntimeRef, RuntimeRequest) }
        assertEquals(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed), closedError.failure)
        assertEquals(0, fixture.processes.transcriptReads)
        assertEquals(emptyList(), fixture.processes.connections)
    }
}
