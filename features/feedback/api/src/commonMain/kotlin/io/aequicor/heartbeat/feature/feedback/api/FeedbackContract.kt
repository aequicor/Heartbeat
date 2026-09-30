package io.aequicor.heartbeat.feature.feedback.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineState

/** Profile-owned feedback history; storage is restored separately from the machine lifetime. */
public sealed interface FeedbackState : MachineState {
    /** Records arriving before the journal has been read, in publication order. */
    public data class Loading(val queued: List<FeedbackRecord> = emptyList()) : FeedbackState

    /** Ordered entries; an operation update replaces its original entry without moving it. */
    public data class Ready(val records: List<FeedbackRecord> = emptyList()) : FeedbackState
}

/** Reports from configuration executors and journal lifecycle results. */
public sealed interface FeedbackIntent : MachineIntent {
    /** Reports accepted through [FeedbackMachineKey]. */
    public sealed interface Public : FeedbackIntent {
        /** Publishes a new operation, or a newer revision of an operation of the same source. */
        public data class Publish(val record: FeedbackRecord) : Public
    }

    /** Journal lifecycle results; ordinary consumers cannot send these through the registry. */
    public sealed interface Internal : FeedbackIntent {
        /** Restored records, also used after a failed initial read recovers. */
        public data class Loaded(val records: List<FeedbackRecord>) : Internal

        /** Stored history could not be read; new reports remain usable in memory. */
        public data object LoadFailed : Internal

        /** A journal snapshot could not be saved; live records remain authoritative. */
        public data object SaveFailed : Internal
    }
}

/** The pure reporting machine performs no IO; its profile journal owns persistence. */
public sealed interface FeedbackEffect : MachineEffect

/** Transient observations; [FeedbackState] remains authoritative for late subscribers. */
public sealed interface FeedbackOutput : MachineOutput {
    /** Storage is currently unavailable; this does not change the outcome of the configuration operation. */
    public data object StorageFailed : FeedbackOutput
}

/** Address of the profile machine, registered eagerly before any studio screen is opened. */
public object FeedbackMachineKey : MachineKey<
    FeedbackState,
    FeedbackIntent,
    FeedbackIntent.Public,
    FeedbackEffect,
    FeedbackOutput,
> {
    override val name: String = "feedback"
}

/** Controls report publication and transcript rendering; stored records are retained while disabled. */
public val FeedbackEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    key = "feedback.enabled",
    description = "Обратная связь об изменениях параметров сессии",
    default = false,
)
