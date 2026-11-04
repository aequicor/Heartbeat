package io.aequicor.heartbeat.feature.aiengine.connections.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceInfo
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsIntent.Internal
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsIntent.Public
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsState.Active
import io.aequicor.heartbeat.feature.aiengine.connections.api.TestData.connection
import io.aequicor.heartbeat.feature.aiengine.connections.api.TestData.koog
import io.aequicor.heartbeat.feature.aiengine.connections.api.TestData.local
import io.aequicor.heartbeat.feature.aiengine.connections.api.TestData.model
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionEdit
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class EngineConnectionsChecksTest {
    private val spec = EngineConnectionsMachineSpec
    private val a = model("a").target
    private val b = model("b").target
    private val c = model("c").target
    private val concurrent = model("concurrent").target
    private val source = AuthSource.NoAuth(
        AuthSourceInfo(connection.source, "Local", AuthRevision.Unknown),
        AuthScope(local.provider.id, local.origin),
    )
    private val binding = EngineBinding(connection.binding, koog.descriptor.id, connection.source)
    private val snapshot = ConnectionsSnapshot(
        listOf(koog.copy(availability = EngineAvailability.Available, bindings = listOf(binding))),
        listOf(source),
        emptyMap(),
        ModelSelection().withEnabled(binding.id, setOf(a.model, c.model, concurrent.model)).withDefault(concurrent),
    )

    @Test
    fun `source edit keeps credentials out of state and checks enabled routes`() {
        val request = ConnectionEdit(binding.id, local.id, source, "Local", local.origin)
        val pending = ConnectionOperation.Edit(binding.id)
        spec.assertTransition(
            Active(snapshot),
            Public.Edit(request),
            Active(snapshot, pending = pending, operationSequence = 1, phase = ModelSavePhase.Saving),
            effects = listOf(
                EngineConnectionsEffect.Edit(
                    request,
                    ConnectionOperationId(1),
                    setOf(a, c, concurrent),
                    setOf(binding.id),
                ),
            ),
        )
        spec.assertIgnored(Active(snapshot), Public.Apply(pending))
        val failed = Active(snapshot, failed = FailedOperation(pending, EngineFailure.Unknown()))
        spec.assertIgnored(failed, Public.RetryFailed)
    }

    @Test
    fun `bulk save checks the user addition and revokes the removal without touching concurrent choices`() {
        val operation = ConnectionOperation.SetModelsEnabled(
            binding.id,
            setOf(a.model, b.model),
            setOf(a.model, c.model),
        )
        spec.assertTransition(
            Active(snapshot),
            Public.Apply(operation),
            Active(snapshot, pending = operation, operationSequence = 1, phase = ModelSavePhase.Saving),
            effects = listOf(
                EngineConnectionsEffect.Execute(operation, checkTargets = setOf(b), removedTargets = setOf(c)),
            ),
        )
    }

    @Test
    fun `checking stays inside the same operation and late results cannot clear another pending operation`() {
        val operation = ConnectionOperation.SetModelEnabled(b, true)
        val current = Active(snapshot, pending = operation, operationSequence = 4, phase = ModelSavePhase.Saving)
        spec.assertTransition(
            current,
            Internal.Checking(ConnectionOperationId(4)),
            current.copy(phase = ModelSavePhase.Checking),
        )
        assertFalse(requireNotNull(spec.resolve(current, Internal.Checking(ConnectionOperationId(4)))).isStateChange)
        spec.assertIgnored(current, Internal.Checking(ConnectionOperationId(3)))
        spec.assertIgnored(current, Internal.Applied(ConnectionOperationId(3)))
        spec.assertIgnored(current, Internal.ApplyFailed(EngineFailure.Unknown(), ConnectionOperationId(3)))
        spec.assertIgnored(Active(snapshot, operationSequence = 4), Internal.Applied(ConnectionOperationId(4)))
    }

    @Test
    fun `read only retry checks an existing selected route and enters Checking immediately`() {
        val operation = ConnectionOperation.CheckModel(a)
        spec.assertTransition(
            Active(snapshot),
            Public.Apply(operation),
            Active(snapshot, pending = operation, operationSequence = 1, phase = ModelSavePhase.Checking),
            effects = listOf(EngineConnectionsEffect.Execute(operation, checkTargets = setOf(a))),
        )
    }

    @Test
    fun `retry cannot enable a missing disabled foreign or unavailable route`() {
        val operation = ConnectionOperation.CheckModel(a)
        val variants = listOf(
            snapshot.copy(selection = snapshot.selection.withModel(a, false)),
            snapshot.copy(sources = emptyList()),
            snapshot.copy(engines = emptyList()),
            snapshot.copy(engines = snapshot.engines.map { it.copy(availability = EngineAvailability.Unknown) }),
            snapshot.copy(
                engines = snapshot.engines.map { it.copy(bindings = listOf(binding.copy(isEnabled = false))) },
            ),
            snapshot.copy(engines = snapshot.engines.map { it.copy(bindings = listOf(binding, binding)) }),
        )
        variants.forEach {
            spec.assertIgnored(Active(it), Public.Apply(operation))
            spec.assertIgnored(
                Active(it, failed = FailedOperation(operation, EngineFailure.Unknown())),
                Public.RetryFailed,
            )
        }
        spec.assertIgnored(
            Active(snapshot),
            Public.Apply(ConnectionOperation.CheckModel(a.copy(engine = EngineId("foreign")))),
        )
    }

    @Test
    fun `pending accepted save is explicit and retains the sequence after the bounded wait`() {
        val operation = ConnectionOperation.SetModelEnabled(b, true)
        spec.assertTransition(
            Active(snapshot, pending = operation, operationSequence = 2, phase = ModelSavePhase.Saving),
            Internal.Applied(ConnectionOperationId(2), isSavePending = true),
            Active(snapshot, operationSequence = 2, isSavePending = true),
        )
    }

    @Test
    fun `no op model choice and default only changes do not request another probe`() {
        listOf(
            ConnectionOperation.SetModelEnabled(a, true),
            ConnectionOperation.SetDefaultModel(a),
            ConnectionOperation.SetDefaultModel(null),
        ).forEach { operation ->
            val result = requireNotNull(spec.resolve(Active(snapshot), Public.Apply(operation)))
            assertEquals(listOf(EngineConnectionsEffect.Execute(operation)), result.effects)
        }
    }

    @Test
    fun `effect failure keeps the exact operation id`() {
        val operation = ConnectionOperation.CheckModel(a)
        assertEquals(
            Internal.ApplyFailed(EngineFailure.Unknown(), ConnectionOperationId(7)),
            spec.onEffectFailure(
                EngineConnectionsEffect.Execute(operation, ConnectionOperationId(7)),
                IllegalStateException(),
            ),
        )
    }
}
