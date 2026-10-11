package io.aequicor.heartbeat.feature.browser.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Opens the profile's embedded browser without contacting an initial website. */
@Serializable
@SerialName("browser")
public data object BrowserRoute : Route

/** Experimental embedded browser; registration belongs to the implementation. */
public val BrowserEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    key = "browser.enabled",
    description = "Встроенный браузер",
    default = false,
)

/** Safe error categories, without native diagnostics or potentially sensitive URLs. */
public enum class BrowserError {
    InvalidAddress,
    LoadFailed,
    UnsupportedAddress,
    EngineUnavailable,
}

/** Current main frame; native history belongs to the currently attached surface. */
public data class BrowserPage(
    val url: String = "",
    val title: String = "",
    val isLoading: Boolean = false,
    val isBackAvailable: Boolean = false,
    val isForwardAvailable: Boolean = false,
    val error: BrowserError? = null,
)

/** The feature keeps no process-persistent browsing data. */
public sealed interface BrowserState : MachineState {
    /** Observation begins when the retained screen model starts. */
    public data object Idle : BrowserState

    /**
     * Availability and the latest main-frame projection; disabled browsers accept no navigation.
     */
    public data class Running(
        val isConfigured: Boolean = false,
        val isEnabled: Boolean = false,
        val page: BrowserPage = BrowserPage(),
        val tabs: List<BrowserTab> = emptyList(),
        val selectedTabId: String? = null,
        val display: BrowserDisplay = BrowserDisplay.Hidden,
    ) : BrowserState
}

/** Browser actions and native feedback. */
public sealed interface BrowserIntent : MachineIntent {
    /** Actions allowed from screens and feature clients. */
    public sealed interface Public : BrowserIntent {
        /** Shows or hides the retained browser without destroying tabs. */
        public data class Display(val mode: BrowserDisplay) : Public

        /** Selects a retained tab. */
        public data class SelectTab(val id: String) : Public

        /** Executes a correlated desktop operation. */
        public data class Perform(val request: BrowserRequest) : Public

        /** Revokes an outstanding operation before the owning turn is released. */
        public data class CancelOperation(val id: String) : Public

        /** Starts the availability and native state observers once. */
        public data object Start : Public

        /** Validates and opens a typed address. */
        public data class Open(val address: String) : Public

        /** Visits the previous native history item when available. */
        public data object Back : Public

        /** Visits the next native history item when available. */
        public data object Forward : Public

        /** Reloads the current page, including a failed page. */
        public data object Reload : Public

        /** Stops the current main-frame load. */
        public data object Stop : Public
    }

    /** Results delivered by feature effects. */
    public sealed interface Internal : BrowserIntent {
        /** Cancellation finished, including native input cleanup. */
        public data class OperationCancelled(val id: String) : Internal

        /** Native tab inventory, with no document or image bodies. */
        public data class TabsChanged(val tabs: List<BrowserTab>, val selected: String?) : Internal

        /** Exactly one terminal result for an operation. */
        public data class Completed(val result: BrowserResult) : Internal

        /** Resolved live toggle value. */
        public data class AvailabilityChanged(val isEnabled: Boolean) : Internal

        /** Validated state from the current native controller. */
        public data class PageChanged(val page: BrowserPage) : Internal

        /** An effect could not be completed; runtime logs the exception. */
        public data object Failed : Internal
    }
}

/** IO requests executed through the implementation's domain ports. */
public sealed interface BrowserEffect : MachineEffect {
    /** Performs one tab operation and returns a correlated result. */
    public data class Perform(val request: BrowserRequest) : BrowserEffect

    /** Selects the native tab. */
    public data class SelectTab(val id: String) : BrowserEffect

    /** Cancels a pending operation. */
    public data class CancelOperation(val id: String) : BrowserEffect

    /** Observes availability and native state for the entire running state lifetime. */
    public data object Observe : BrowserEffect

    /** Loads an already normalized HTTP(S) address. */
    public data class Load(val url: String) : BrowserEffect

    /** Native history and loading commands. */
    public data object Back : BrowserEffect

    /** Advances native history. */
    public data object Forward : BrowserEffect

    /** Repeats the current navigation. */
    public data object Reload : BrowserEffect

    /** Cancels a native load. */
    public data object Stop : BrowserEffect
}

/** All browser feedback is retained state rather than one-off outputs. */
public sealed interface BrowserOutput : MachineOutput {
    /** Acknowledges completed cancellation. */
    public data class Cancelled(val id: String) : BrowserOutput

    /** Operation results are transient and collected before submission. */
    public data class Completed(val result: BrowserResult) : BrowserOutput
}

/** Public address of the feature-scoped browser machine. */
public object BrowserMachineKey :
    MachineKey<BrowserState, BrowserIntent, BrowserIntent.Public, BrowserEffect, BrowserOutput> {
    override val name: String = "browser"
}
