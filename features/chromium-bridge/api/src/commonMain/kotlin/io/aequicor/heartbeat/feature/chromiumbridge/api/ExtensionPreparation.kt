package io.aequicor.heartbeat.feature.chromiumbridge.api

/** First-use extension setup; leaving the enabled bridge cancels work without publishing a partial folder. */
public sealed interface ExtensionPreparation {
    /** No setup is in progress. */
    public data object Idle : ExtensionPreparation

    /** Download bytes accepted by the verified artifact cache. */
    public data class Downloading(public val received: Long = 0, public val total: Long? = null) : ExtensionPreparation

    /** Checking and extracting the verified archive. */
    public data object Extracting : ExtensionPreparation

    /** Setup failed; the same explicit action retries it. */
    public data object Failed : ExtensionPreparation
}
