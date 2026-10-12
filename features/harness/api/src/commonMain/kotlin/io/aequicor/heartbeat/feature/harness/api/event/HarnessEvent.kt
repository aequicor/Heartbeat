package io.aequicor.heartbeat.feature.harness.api.event

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStatus
import io.aequicor.heartbeat.feature.scheduler.api.EventKey
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import kotlin.time.Instant

/** A transient host-stamped event. toString never includes payload, routing identities or nested user content. */
public sealed class HarnessEvent {
    /** Time observed by the host; a notification is not proof of current session or workflow state. */
    public abstract val at: Instant

    final override fun toString(): String = "HarnessEvent(***)"
}

/** System notifications forwarded from the scheduler's system source only while scheduling is enabled. */
public sealed class SystemEvent : HarnessEvent() {
    /** System event delivery started for the current profile. */
    public data class Started(override val at: Instant) : SystemEvent()

    /** Host-observed network reachability; it does not prove a particular service is reachable. */
    public data class NetworkChanged(val isConnected: Boolean, override val at: Instant) : SystemEvent()
}

/** Explicitly untrusted bus text. It is data, never an instruction or authorization from the host. */
public data class UntrustedEventPayload(val text: String) {
    override fun toString(): String = "UntrustedEventPayload(***)"
}

/** Every profile bus event retains its key and provenance; private payload stays explicitly untrusted. */
public data class HarnessBusEvent(
    val key: EventKey,
    val origin: EventOrigin,
    override val at: Instant,
    val payload: UntrustedEventPayload? = null,
) : HarnessEvent()

/** Catalog and binding changes, without credentials or mutable engine service objects. */
public sealed class EngineEvent : HarnessEvent() {
    /** Latest availability from a diff of the engine catalog. */
    public data class AvailabilityChanged(
        val engine: EngineId,
        val availability: EngineAvailability,
        override val at: Instant,
    ) : EngineEvent()

    /** Latest configured binding identities for this engine; source credentials are never exposed. */
    public data class ConnectionsChanged(
        val engine: EngineId,
        val bindings: Set<EngineBindingId>,
        override val at: Instant,
    ) : EngineEvent()
}

/** Notifications from the harness runtime, scoped to the receiving harness's authority. */
public sealed class HarnessLifecycleEvent : HarnessEvent() {
    /** One approved item revision became active. */
    public data class Activated(
        val harness: HarnessId,
        val item: ItemId,
        val revision: Long,
        override val at: Instant,
    ) : HarnessLifecycleEvent()

    /** Terminal workflow result; result text is private and must never be logged. */
    public data class WorkflowFinished(
        val harness: HarnessId,
        val workflow: ItemId,
        val run: RunId,
        val status: WorkflowStatus,
        override val at: Instant,
    ) : HarnessLifecycleEvent() {
        init {
            require(status != WorkflowStatus.Running) { "WorkflowFinished requires a terminal status" }
        }
    }
}
