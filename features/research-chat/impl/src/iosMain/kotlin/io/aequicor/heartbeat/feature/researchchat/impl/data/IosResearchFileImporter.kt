package io.aequicor.heartbeat.feature.researchchat.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.researchchat.impl.domain.ImportedResearchFile
import io.aequicor.heartbeat.feature.researchchat.impl.domain.ResearchFileImporter

/** This platform accepts pasted documents and image URLs through the common resource form. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class IosResearchFileImporter : ResearchFileImporter {
    override val isAvailable: Boolean = false
    override suspend fun pick(): ImportedResearchFile? = null
}
