package io.aequicor.heartbeat.feature.researchchat.impl.domain

import io.aequicor.heartbeat.feature.researchchat.api.ResearchResourceKind

/** A user-selected local file, never a path that a model can ask the application to read. */
internal data class ImportedResearchFile(
    val title: String,
    val value: String,
    val kind: ResearchResourceKind,
    val mediaType: String,
) {
    override fun toString(): String = "ImportedResearchFile(kind=$kind, mediaType=$mediaType)"
}

/** Platform-owned file selection. Text paste and HTTPS image inputs remain available without a picker. */
internal interface ResearchFileImporter {
    val isAvailable: Boolean
    suspend fun pick(): ImportedResearchFile?
}
