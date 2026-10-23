package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioFileContent
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioFileReader

/** Chat file links stay inert: this platform has no local project files to preview. */
@Inject
@ContributesBinding(ProfileScope::class)
internal class UnsupportedStudioFileReader : StudioFileReader {
    override val isAvailable: Boolean = false
    override suspend fun resolve(path: String, base: String?): String? = null
    override suspend fun read(path: String): StudioFileContent = StudioFileContent.Unavailable
    override suspend fun modifiedAt(path: String): Long? = null
}
