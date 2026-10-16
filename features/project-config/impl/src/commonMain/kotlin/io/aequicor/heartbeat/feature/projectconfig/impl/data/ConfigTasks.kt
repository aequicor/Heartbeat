package io.aequicor.heartbeat.feature.projectconfig.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigApplyContext
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigApplyResult
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigBinding
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigBundle
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigEntityRef
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigReadFailure
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigTaskFailure
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigTaskResult
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnit
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnitRef
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigEffect
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigIntent.Internal
import io.aequicor.heartbeat.feature.projectconfig.api.bundleProposals
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigBundles
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigRead
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigTree
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigWriteException
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigWriteFailure

/**
 * Long operations started by the user: writing entities into a project folder, exporting and opening bundles and
 * copying a bundle into a project. Each answers with one TaskDone (or BundleOpened); counts are logged, never paths.
 */
@Inject
internal class ConfigTasks(private val access: ConfigFolderAccess, private val bundles: ConfigBundles) {
    private val log = Log.tag("ConfigTasks")

    /**
     * Writes [effect]'s entities into the project folder under their default unit names and binds them. A unit that
     * already exists there is skipped: the folder is its source until the user resolves it. A project-scoped entity
     * (a learned instruction) is moved into the project first, so it applies there.
     */
    suspend fun save(effect: ProjectConfigEffect.SaveToProject): Internal {
        val folder = access.folder(effect.project) as? FolderRead.Read
            ?: return failed(effect.requestId, ConfigTaskFailure.Unreadable)
        val sections = access.reader.sections()
        val bindings = effect.entities.mapNotNull { save(effect, folder, sections, it) }
        val skipped = effect.entities.size - bindings.size
        log.i { "Saved into a project: ${bindings.size}, skipped $skipped" }
        val result = if (bindings.isEmpty()) {
            ConfigTaskResult.Failed(ConfigTaskFailure.Empty)
        } else {
            ConfigTaskResult.Saved(bindings.size, skipped)
        }
        return Internal.TaskDone(effect.requestId, result, bindings)
    }

    /** Runs a task effect; null for one that answers nothing. */
    suspend fun handle(effect: ProjectConfigEffect): Internal? = when (effect) {
        is ProjectConfigEffect.SaveToProject -> save(effect)

        is ProjectConfigEffect.Export -> export(effect)

        is ProjectConfigEffect.OpenBundle -> open(effect)

        is ProjectConfigEffect.ImportToProject -> import(effect)

        is ProjectConfigEffect.CloseBundle -> null.also { close(effect.id) }

        ProjectConfigEffect.Load, is ProjectConfigEffect.SaveBindings, is ProjectConfigEffect.Sync,
        is ProjectConfigEffect.Review, is ProjectConfigEffect.Apply, is ProjectConfigEffect.WriteBack,
        -> error("Not a task effect")
    }

    /** Binding of [ref] written into the folder, or null when it is skipped. */
    private suspend fun save(
        effect: ProjectConfigEffect.SaveToProject,
        folder: FolderRead.Read,
        sections: SectionsSnapshot,
        ref: ConfigEntityRef,
    ): ConfigBinding? {
        val section = sections[ref.section] ?: return null
        val entity = sections.entity(ref.section, ref.key) ?: return null
        if (folder.tree[ConfigUnitRef(ref.section, entity.name)] != null) return null
        val isMoved = !section.isProjectScoped || entity.project == effect.project ||
            section.encode(entity.key, entity.name)
                ?.let { section.apply(it, entity.key, ConfigApplyContext(effect.project)) } is ConfigApplyResult.Applied
        val unit = section.encode(entity.key, entity.name)?.takeIf { isMoved && write(effect, it) } ?: return null
        return ConfigBinding(effect.project, unit.ref, entity.key, unit.digest, unit.digest)
    }

    /** Writes [effect]'s entities into a bundle. */
    suspend fun export(effect: ProjectConfigEffect.Export): Internal {
        val units = encode(effect.entities)
        if (units.isEmpty()) return failed(effect.requestId, ConfigTaskFailure.Empty)
        return try {
            bundles.write(effect.path, ConfigTree.of(units.flatMap { it.files }))
            log.i { "Exported a bundle of ${units.size} units" }
            Internal.TaskDone(effect.requestId, ConfigTaskResult.Exported(units.size))
        } catch (e: ConfigWriteException) {
            log.w(e) { "Bundle export failed" }
            failed(effect.requestId, e.reason.taskFailure())
        }
    }

    /** Opens a bundle for import into the profile: its units become proposals for the profile's entities. */
    suspend fun open(effect: ProjectConfigEffect.OpenBundle): Internal {
        val tree = when (val read = bundles.read(effect.path)) {
            is ConfigRead.Read -> access.group(read.tree)
            is ConfigRead.Failed -> return failed(effect.requestId, read.failure.taskFailure())
            ConfigRead.Absent -> return failed(effect.requestId, ConfigTaskFailure.Unreadable)
        }
        val sections = access.reader.sections()
        val units = access.reader.scan(tree, sections, isUnavailableReported = true)
        val proposals = bundleProposals(effect.requestId, units, access.reader.entities(sections, null))
        access.keep(effect.requestId, tree)
        log.i { "Opened a bundle: ${units.size} units, ${proposals.size} proposals" }
        return Internal.BundleOpened(effect.requestId, ConfigBundle(effect.requestId, fileName(effect.path)), proposals)
    }

    /** Copies a bundle's units into a project folder; the next sync proposes them. */
    suspend fun import(effect: ProjectConfigEffect.ImportToProject): Internal {
        val tree = when (val read = bundles.read(effect.path)) {
            is ConfigRead.Read -> access.group(read.tree)
            is ConfigRead.Failed -> return failed(effect.requestId, read.failure.taskFailure())
            ConfigRead.Absent -> return failed(effect.requestId, ConfigTaskFailure.Unreadable)
        }
        if (tree.units.isEmpty()) return failed(effect.requestId, ConfigTaskFailure.Empty)
        return try {
            access.write(effect.project, tree.units.flatMap { it.files }, emptyList())
            log.i { "Imported a bundle into a project: ${tree.units.size} units" }
            Internal.TaskDone(effect.requestId, ConfigTaskResult.Imported(tree.units.size))
        } catch (e: ConfigWriteException) {
            log.w(e) { "Bundle import into a project failed" }
            failed(effect.requestId, e.reason.taskFailure())
        }
    }

    /** Forgets bundle [id]. */
    suspend fun close(id: String) {
        access.forget(id)
        log.d { "Closed a bundle" }
    }

    private suspend fun encode(entities: List<ConfigEntityRef>): List<ConfigUnit> {
        val sections = access.reader.sections()
        return entities.mapNotNull { ref ->
            val entity = sections.entity(ref.section, ref.key) ?: return@mapNotNull null
            sections[ref.section]?.encode(entity.key, entity.name)
        }.distinctBy { it.ref }
    }

    private suspend fun write(effect: ProjectConfigEffect.SaveToProject, unit: ConfigUnit): Boolean = try {
        access.write(effect.project, unit.files, emptyList())
        true
    } catch (e: ConfigWriteException) {
        log.w(e) { "Writing a ${unit.ref.section} unit into a project failed" }
        false
    }

    private fun failed(requestId: String, reason: ConfigTaskFailure) =
        Internal.TaskDone(requestId, ConfigTaskResult.Failed(reason))

    private fun fileName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

    private fun ConfigReadFailure.taskFailure(): ConfigTaskFailure = when (this) {
        ConfigReadFailure.Format -> ConfigTaskFailure.Format

        ConfigReadFailure.Version -> ConfigTaskFailure.Version

        ConfigReadFailure.Limit -> ConfigTaskFailure.Limit

        ConfigReadFailure.Unsupported -> ConfigTaskFailure.Unavailable

        ConfigReadFailure.Unavailable, ConfigReadFailure.NotDirectory, ConfigReadFailure.Io ->
            ConfigTaskFailure.Unreadable
    }

    private fun ConfigWriteFailure.taskFailure(): ConfigTaskFailure = when (this) {
        ConfigWriteFailure.Limit -> ConfigTaskFailure.Limit

        ConfigWriteFailure.Unsupported -> ConfigTaskFailure.Unavailable

        ConfigWriteFailure.Unavailable, ConfigWriteFailure.NotDirectory, ConfigWriteFailure.Io ->
            ConfigTaskFailure.Unwritable
    }
}
