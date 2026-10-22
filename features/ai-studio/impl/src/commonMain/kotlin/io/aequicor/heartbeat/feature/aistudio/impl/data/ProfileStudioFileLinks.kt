package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.api.StudioFilePreview
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioFileContent
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioFileLinks
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioFileReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Resolves chat file links against the checkout the chat's agent works in; reading stays with [reader]. */
@Inject
@ContributesBinding(ProfileScope::class)
internal class ProfileStudioFileLinks(
    private val access: Lazy<StudioConfigurationAccess>,
    private val workspaces: LocalWorkspaces,
    private val reader: StudioFileReader,
    toggles: FeatureToggles,
) : StudioFileLinks {
    private val log = Log.tag("StudioFileLinks")

    override val availability: Flow<Boolean> = toggles.observe(StudioFilePreview).map { it && reader.isAvailable }

    override suspend fun resolve(path: String, sessionId: String?, projectId: String?): String? {
        val base = checkout(sessionId, projectId)
        val resolved = reader.resolve(path, base)
        log.d { "Resolve file link base=${base != null} resolved=${resolved != null}" }
        return resolved
    }

    override suspend fun resolveIn(path: String, directory: String): String? = reader.resolve(path, directory)

    override suspend fun read(path: String): StudioFileContent = reader.read(path)

    override suspend fun modifiedAt(path: String): Long? = reader.modifiedAt(path)

    /**
     * A worktree chat reads its own checkout; a chat without a record (new or demo) falls back to its project. A
     * sandbox (virtual) workspace has no folder of its sources on the host, so its relative links do not resolve.
     */
    private suspend fun checkout(sessionId: String?, projectId: String?): String? {
        val workspace = sessionId?.let { id -> record(id)?.resolvedExecutionWorkspace() }
            ?: projectId?.let(::WorkspaceRef)
        return workspace?.let { ref ->
            try {
                when {
                    !workspaces.isAvailable -> null
                    workspaces.virtual(ref) != null -> null
                    else -> workspaces.resolve(ref)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e) { "Chat checkout is unavailable for file links" }
                null
            }
        }
    }

    private suspend fun record(id: String): StudioChatRecord? = try {
        access.value.configurationRecord(id)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "Chat record is unavailable for file links" }
        null
    }
}
