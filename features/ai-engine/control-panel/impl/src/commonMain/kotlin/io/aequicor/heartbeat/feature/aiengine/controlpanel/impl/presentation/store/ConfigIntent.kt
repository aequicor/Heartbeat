package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store

/** Configuration pages of the panel's engine: moving between pages and editing the profile draft. */
sealed interface ConfigIntent : EngineControlPanelIntent {
    /** Shows the configuration page [page]. */
    data class OpenPage(val page: String) : ConfigIntent

    /** Returns from a page to the panel overview. */
    data object ClosePage : ConfigIntent

    /** Changes the value of [field] in the draft. */
    data class EditField(val field: String, val input: FieldInputUi) : ConfigIntent

    /** Saves the draft when it has changes and no blocking problems. */
    data object SaveConfig : ConfigIntent

    /** Drops the draft and shows the saved configuration. */
    data object DiscardConfig : ConfigIntent
}
