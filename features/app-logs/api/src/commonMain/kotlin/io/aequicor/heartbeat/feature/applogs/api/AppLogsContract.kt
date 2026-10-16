package io.aequicor.heartbeat.feature.applogs.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Desktop application logs; independent of the selected profile. The settings host owns the embedded header. */
@Serializable
@SerialName("app-logs")
public data class AppLogsRoute(val isEmbedded: Boolean = true) : Route

/** Enables the completed Desktop log tools in settings; native operations also check this switch. */
public val AppLogsEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    key = "app_logs.enabled",
    description = "Копирование и экспорт логов приложения",
    default = true,
)

/** An explicit user operation; no log contents or filesystem paths enter the machine contract. */
public enum class AppLogsOperation {
    /** Copies the snapshot of the last ten minutes to the system clipboard. */
    CopyRecent,

    /** Saves the snapshot of the last ten minutes as UTF-8 text through the native save dialog. */
    SaveRecent,

    /** Saves a ZIP snapshot of all retained logs, up to five days. */
    SaveArchive,

    /** Opens the managed log directory in the system file manager. */
    OpenFolder,
}

/** Safe status of an operation, without errors or user-controlled strings. */
public enum class AppLogsOutcome {
    /** The requested operation completed. */
    Success,

    /** The user dismissed the save dialog without writing a destination. */
    Cancelled,

    /** The selected time window contains no log records. */
    Empty,

    /** An operation failed; the user can retry it. */
    Failure,

    /** The feature switch is disabled. */
    Disabled,
}

/** A feature instance serializes operations while it is open and never retains log contents. */
public sealed interface AppLogsState : MachineState {
    /** Ready for a new operation, optionally retaining its last result for feedback and retry. */
    public data class Ready(val operation: AppLogsOperation? = null, val outcome: AppLogsOutcome? = null) :
        AppLogsState

    /** One native operation is running; further requests are ignored until it completes. */
    public data class Working(val operation: AppLogsOperation) : AppLogsState
}

/** User requests and results from the current operation. */
public sealed interface AppLogsIntent : MachineIntent {
    /** Requests accepted from the screen or another feature. */
    public sealed interface Public : AppLogsIntent {
        /** Starts an operation when the feature is ready. */
        public data class Execute(val operation: AppLogsOperation) : Public

        /** Repeats the last failed operation with a fresh snapshot and a fresh save dialog. */
        public data object Retry : Public
    }

    /** Results accepted only from the current feature instance. */
    public sealed interface Internal : AppLogsIntent {
        /** Finishes the pending operation and makes the controls available again. */
        public data class Finished(val outcome: AppLogsOutcome) : Internal
    }
}

/** Commands executed by the Desktop backend. */
public sealed interface AppLogsEffect : MachineEffect {
    /** Takes a snapshot and performs the explicitly requested native action. */
    public data class Execute(val operation: AppLogsOperation) : AppLogsEffect
}

/** Feedback is durable in Ready; this feature does not emit one-shot outputs. */
public sealed interface AppLogsOutput : MachineOutput

/** Address of the most recently opened log-tools instance. */
public object AppLogsMachineKey :
    MachineKey<AppLogsState, AppLogsIntent, AppLogsIntent.Public, AppLogsEffect, AppLogsOutput> {
    override val name: String = "app-logs"
}
