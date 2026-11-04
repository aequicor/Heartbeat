package io.aequicor.heartbeat.feature.aiengine.connections.impl.domain

/** System picker for a local runtime executable. A cancelled picker returns null. */
internal interface RuntimeFilePicker {
    val isAvailable: Boolean
    suspend fun pick(): String?
}

/** Default for callers that do not provide platform UI. */
internal object UnavailableRuntimeFilePicker : RuntimeFilePicker {
    override val isAvailable: Boolean = false
    override suspend fun pick(): String? = null
}
