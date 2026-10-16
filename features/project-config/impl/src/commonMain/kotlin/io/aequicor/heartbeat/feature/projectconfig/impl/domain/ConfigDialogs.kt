package io.aequicor.heartbeat.feature.projectconfig.impl.domain

/** System file dialogs for bundles; null when the user cancels or the platform has none. */
internal interface ConfigDialogs {
    /** Whether bundles can be chosen here. */
    val isAvailable: Boolean

    /** A `.hbai` bundle to read. */
    suspend fun pickBundle(): String?

    /** Where to write a bundle, starting from [suggestedName]. */
    suspend fun pickBundleTarget(suggestedName: String): String?
}
