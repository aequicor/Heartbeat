package io.aequicor.heartbeat.feature.aiengine.connections.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsIntent.Internal
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsIntent.Public
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsState.Active
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsState.Idle
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
            Active(snapshot, pending = disconnect, operationSequence = 1),
            effects = listOf(EngineConnectionsEffect.Execute(disconnect)),
        )
        spec.assertIgnored(Active(snapshot, pending = disconnect, operationSequence = 1), Public.Apply(disconnect))
        spec.assertTransition(
            Active(snapshot, pending = disconnect, operationSequence = 1),
            Internal.Applied(ConnectionOperationId(1)),
            Active(snapshot, operationSequence = 1),
        )
    }

    @Test
    fun `failed change is kept until retried or dismissed`() {
        val failed = Active(snapshot, failed = FailedOperation(disconnect, failure), operationSequence = 1)
        spec.assertTransition(
            Active(snapshot, pending = disconnect, operationSequence = 1),
            Internal.ApplyFailed(failure, ConnectionOperationId(1)),
            failed,
        )
        spec.assertTransition(
            failed,
            Public.RetryFailed,
            Active(snapshot, pending = disconnect, operationSequence = 2),
            effects = listOf(EngineConnectionsEffect.Execute(disconnect, ConnectionOperationId(2))),
        )
        spec.assertTransition(failed, Public.DismissError, Active(snapshot, operationSequence = 1))
        spec.assertIgnored(Active(snapshot), Public.RetryFailed)
    }

    @Test
    fun `observation failure is retried explicitly`() {
        val broken = Active(loadFailure = failure)
        spec.assertTransition(Active(snapshot), Internal.ObserveFailed(failure), broken)
        spec.assertIgnored(broken, Public.Apply(disconnect))
        spec.assertTransition(broken, Public.RetryLoad, Active(), effects = listOf(EngineConnectionsEffect.Observe))
        spec.assertIgnored(Active(snapshot), Public.RetryLoad)
        spec.assertTransition(broken, Internal.Snapshot(snapshot), Active(snapshot))
    }

    @Test
    fun `observation failure keeps a running change and its outcome`() {
        val running = Active(snapshot, pending = disconnect, operationSequence = 1)
        val broken = Active(pending = disconnect, loadFailure = failure, operationSequence = 1)
        spec.assertTransition(running, Internal.ObserveFailed(failure), broken)
        spec.assertTransition(
            broken,
            Internal.ApplyFailed(failure, ConnectionOperationId(1)),
            Active(failed = FailedOperation(disconnect, failure), loadFailure = failure, operationSequence = 1),
        )
        spec.assertTransition(
            broken,
            Internal.Applied(ConnectionOperationId(1)),
            Active(loadFailure = failure, operationSequence = 1),
        )
        spec.assertIgnored(
            Active(failed = FailedOperation(disconnect, failure), loadFailure = failure, operationSequence = 1),
            Public.RetryFailed,
        )
    }
}
