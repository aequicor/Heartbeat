package io.aequicor.heartbeat.feature.projectconfig.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigFile
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigPath
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigProposal
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigReadFailure
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigSource
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnit
import io.aequicor.heartbeat.feature.projectconfig.api.spi.ConfigSection
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigRead
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigTree
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigWriteException
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigWriteFailure
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.GroupedTree
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ProjectFolders
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.groupUnits
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A project folder grouped into units, or why it was not read; a missing folder reads as empty. */
internal sealed interface FolderRead {
    /** The folder's units; [hasFolder] is false when the project has no `.heartbeat` folder. */
    data class Read(val tree: GroupedTree, val hasFolder: Boolean) : FolderRead

    /** Nothing was read. */
    data class Failed(val failure: ConfigReadFailure) : FolderRead
}

/**
 * Project folders and opened bundles as the effects see them: a project is resolved through [LocalWorkspaces] on
 * every access (paths never leave this class), and a proposal's unit is handed out only when its files still have
 * the reviewed digest, so a unit is applied exactly as the user trusted it. Bundles stay in memory while open.
 */
@SingleIn(ProfileScope::class)
@Inject
internal class ConfigFolderAccess(
    private val workspaces: LocalWorkspaces,
    private val folders: ProjectFolders,
    private val sections: Lazy<Set<ConfigSection>>,
) {
    /** Reader over the profile's sections. */
    val reader: ConfigReader = ConfigReader { sections.value }

    private val lock = Mutex()
    private val bundles = mutableMapOf<String, GroupedTree>()

    /** The folder of [project]. */
    suspend fun folder(project: WorkspaceRef): FolderRead {
        val root = workspaces.resolve(project) ?: return FolderRead.Failed(ConfigReadFailure.Unavailable)
        return when (val read = folders.read(root)) {
            ConfigRead.Absent -> FolderRead.Read(group(ConfigTree(emptyList())), hasFolder = false)
            is ConfigRead.Failed -> FolderRead.Failed(read.failure)
            is ConfigRead.Read -> FolderRead.Read(group(read.tree), hasFolder = true)
        }
    }

    /**
     * Writes [put] and deletes [delete] in the folder of [project].
     * @throws ConfigWriteException when the project is unknown or the folder cannot be written.
     */
    suspend fun write(project: WorkspaceRef, put: List<ConfigFile>, delete: List<ConfigPath>) {
        val root = workspaces.resolve(project) ?: throw ConfigWriteException(ConfigWriteFailure.Unavailable)
        folders.write(root, put, delete)
    }

    /** The unit of [proposal] as its source holds it now, or null when it is gone or no longer the reviewed one. */
    suspend fun unit(proposal: ConfigProposal): ConfigUnit? {
        val tree = when (val source = proposal.source) {
            is ConfigSource.Project -> (folder(source.project) as? FolderRead.Read)?.tree
            is ConfigSource.Bundle -> lock.withLock { bundles[source.id] }
        }
        return tree?.get(proposal.unit)?.takeIf { it.digest == proposal.digest }
    }

    /** Groups [tree] into the units of the profile's sections. */
    fun group(tree: ConfigTree): GroupedTree = groupUnits(tree, sections.value)

    /** Keeps the units of bundle [id] for its proposals. */
    suspend fun keep(id: String, tree: GroupedTree): Unit = lock.withLock { bundles[id] = tree }

    /** Forgets bundle [id]. */
    suspend fun forget(id: String) {
        lock.withLock { bundles.remove(id) }
    }
}
