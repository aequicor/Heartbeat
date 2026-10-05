package io.aequicor.heartbeat.feature.checklist.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Embedded card route; its host controls placement inside a message. */
@Serializable
@SerialName("checklist")
public data class ChecklistRoute(val id: String) : Route

/** Interactive user checklists and session readiness. */
public val ChecklistEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "checklist.enabled",
    "Чеклисты в сообщениях и готовность сессии",
    default = false,
)

/** Atomic durable snapshot: a completed card cannot be stored without its outgoing event. */
@Serializable
public data class ChecklistJournal(
    val cards: List<Checklist> = emptyList(),
    val outbox: List<ChecklistEvent> = emptyList(),
    val revision: Long = 0,
    val generations: Map<String, RequestId> = emptyMap(),
    val generationRevisions: Map<String, Long> = emptyMap(),
)

/** Profile-owned machine, independent of open screens. */
public sealed interface ChecklistState : MachineState {
    /** Loading is retriable and never silently replaces an unread journal. */
    public data class Loading(val hasFailed: Boolean = false) : ChecklistState

    /** Optimistic drafts; savedRevision prevents UI from reporting an unpersisted completion as saved. */
    public data class Ready(
        val journal: ChecklistJournal,
        val savedRevision: Long = journal.revision,
        val hasFailed: Boolean = false,
    ) : ChecklistState
}

/** Commands and persistence responses. Inter-feature notifications use SchedulerBus. */
public sealed interface ChecklistIntent : MachineIntent {
    /** Sources and user-interface commands. */
    public sealed interface Public : ChecklistIntent {
        /** Creates a card; repeating the same identity is harmless. */
        public data class Create(val card: Checklist) : Public

        /** Edits a user answer; single-choice-only cards may complete in this transition. */
        public data class Answer(val id: String, val field: String, val answer: ChecklistAnswer) : Public

        /** Explicit completion for cards containing text or checkboxes. */
        public data class Complete(val id: String) : Public

        /** Retries a failed save/load. */
        public data object Retry : Public

        /** Explicitly retries a failed continuation with a new delivery attempt. */
        public data class RetryDelivery(val id: String) : Public
    }

    /** Effect and bus responses. */
    public sealed interface Internal : ChecklistIntent {
        /** Starts loading. */
        public data object Start : Internal

        /** Restored durable snapshot. */
        public data class Loaded(val journal: ChecklistJournal) : Internal

        /** Storage write completed. */
        public data class Saved(val revision: Long) : Internal

        /** Storage failed; the draft remains available. */
        public data object Failed : Internal

        /** Studio acknowledged an event on the scheduler bus. */
        public data class Acknowledged(val eventId: String) : Internal

        /** Host started a new request; old readiness checklists are superseded. */
        public data class Started(val session: SessionRef, val request: RequestId, val revision: Long = 0) : Internal

        /** Scheduler delivered or refused the wake of this attempt. */
        public data class Delivered(val id: String, val attempt: Int, val isSuccessful: Boolean) : Internal
    }
}

/** Storage operations; publication observes only saved snapshots. */
public sealed interface ChecklistEffect : MachineEffect {
    /** Reads the journal. */
    public data object Load : ChecklistEffect

    /** Writes one monotonically ordered snapshot. */
    public data class Save(val journal: ChecklistJournal) : ChecklistEffect
}

/** No inter-feature outputs: all notifications travel through SchedulerBus. */
public sealed interface ChecklistOutput : MachineOutput

/** Address of the profile checklist machine. */
public object ChecklistMachineKey :
    MachineKey<ChecklistState, ChecklistIntent, ChecklistIntent.Public, ChecklistEffect, ChecklistOutput> {
    override val name: String = "checklist"
}
