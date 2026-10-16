package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store

/** An import of the engine's own configuration into the profile. */
sealed interface ImportIntent : EngineControlPanelIntent {
    /** Looks up what an import would bring. */
    data object Preview : ImportIntent

    /** Includes or leaves out the part [item]. */
    data class Toggle(val item: String) : ImportIntent

    /** Chooses whether imported files replace the profile's or merge into them. */
    data class SetReplacing(val isReplacing: Boolean) : ImportIntent

    /** Imports the chosen parts. */
    data object Import : ImportIntent

    /** Closes the preview or the report. */
    data object Dismiss : ImportIntent
}
