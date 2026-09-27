package io.aequicor.heartbeat.feature.aiengine.connections.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsIntent.Internal
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsIntent.Public
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsState.Active
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsState.Idle
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsState.LoadError
import io.aequicor.heartbeat.feature.aiengine.connections.api.TestData.connection
import io.aequicor.heartbeat.feature.aiengine.connections.api.TestData.koog
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import kotlin.test.Test

class EngineConnectionsMachineTest {
    private val spec = EngineConnectionsMachineSpec
    private val snapshot = ConnectionsSnapshot(listOf(koog), emptyList(), emptyMap(), ModelSelection())
    private val disconnect = ConnectionOperation.Disconnect(connection.binding)
    private val failure = EngineFailure.Transport(TransportFailureReason.NetworkUnavailable)

    @Test
    fun `start observes and snapshots update in place`() {
        spec.assertTransition(Idle, Public.Start, Active(), effects = listOf(EngineConnectionsEffect.Observe))
        spec.assertTransition(Active(), Internal.Snapshot(snapshot), Active(snapshot))
    }

    @Test
    fun `one change runs at a time and only after the first snapshot`() {
        spec.assertIgnored(Active(), Public.Apply(disconnect))
        spec.assertTransition(
            Active(snapshot),
            Public.Apply(disconnect),
            Active(snapshot, pending = disconnect),
            effects = listOf(EngineConnectionsEffect.Execute(disconnect)),
        )
        spec.assertIgnored(Active(snapshot, pending = disconnect), Public.Apply(disconnect))
        spec.assertTransition(Active(snapshot, pending = disconnect), Internal.Applied, Active(snapshot))
    }

    @Test
    fun `failed change is kept until retried or dismissed`() {
        val failed = Active(snapshot, failed = FailedOperation(disconnect, failure))
        spec.assertTransition(Active(snapshot, pending = disconnect), Internal.ApplyFailed(failure), failed)
        spec.assertTransition(
            failed,
            Public.RetryFailed,
            Active(snapshot, pending = disconnect),
            effects = listOf(EngineConnectionsEffect.Execute(disconnect)),
        )
        spec.assertTransition(failed, Public.DismissError, Active(snapshot))
        spec.assertIgnored(Active(snapshot), Public.RetryFailed)
    }

    @Test
    fun `observation failure is retried explicitly`() {
        spec.assertTransition(Active(snapshot), Internal.ObserveFailed(failure), LoadError(failure))
        spec.assertTransition(
            LoadError(failure),
            Public.RetryLoad,
            Active(),
            effects = listOf(EngineConnectionsEffect.Observe),
        )
    }
}
