package io.aequicor.heartbeat.feature.projectconfig.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigApplyContext
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigApplyResult
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigBinding
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigBundle
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigEntityRef
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigLayout
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigReadFailure
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigTaskFailure
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigTaskResult
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnit
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnitRef
import io.aequicor.heartbeat.feature.projectconfig.api.ExistingUnits
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
     * (a learned instruction) is moved only after its files were written successfully. If the owner rejects the
     * move, the files remain unbound for the next sync to propose, and the original entity is unchanged.
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

        ProjectConfigEffect.Load, is ProjectConfigEffect.SaveStorage, is ProjectConfigEffect.SaveBindings,
        is ProjectConfigEffect.Sync, is ProjectConfigEffect.Apply,
        is ProjectConfigEffect.WriteBack,
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
        val unit = section.encode(entity.key, entity.name) ?: return null
        if (!write(effect, unit)) return null
        val isMoveRequired = section.isProjectScoped && entity.project != effect.project
        // Writing may suspend while the user edits the lesson: never move an obsolete copy over that edit.
        if (isMoveRequired && section.encode(entity.key, entity.name)?.digest != unit.digest) return null
        val isMoved = !isMoveRequired ||
            section.apply(unit, entity.key, ConfigApplyContext(effect.project)) is ConfigApplyResult.Applied
        if (!isMoved) return null
        return ConfigBinding(effect.project, unit.ref, entity.key, unit.digest, unit.digest)
    }

    /** Writes [effect]'s entities into a bundle; only a `.hbai` file is written, never another file at that path. */
    suspend fun export(effect: ProjectConfigEffect.Export): Internal {
        if (!effect.path.endsWith(".${ConfigLayout.BUNDLE_EXTENSION}", ignoreCase = true)) {
            return failed(effect.requestId, ConfigTaskFailure.Unwritable)
        }
        val units = encode(effect.entities)
        if (units.isEmpty()) return failed(effect.requestId, ConfigTaskFailure.Empty)
        return try {
            bundles.write(effect.path, ConfigTree.of(units.flatMap { it.files }))
            log.i { "Exported a bundle of ${units.size} units" }
            Internal.TaskDone(effect.requestId, ConfigTaskResult.Exported(units.size))
        } catch (e: ConfigWriteException) {
            log.w(e.redactedFailure()) { "Bundle export failed" }
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
        val read = access.reader.sections()
        val entities = access.reader.entities(read, null)
        val units = access.reader.scan(tree, read.without(entities.unreadable), isUnavailableReported = true)
        val proposals = bundleProposals(effect.requestId, units, entities.digests)
        access.keep(effect.requestId, tree)
        log.i { "Opened a bundle: ${units.size} units, ${proposals.size} proposals" }
        return Internal.BundleOpened(effect.requestId, ConfigBundle(effect.requestId, fileName(effect.path)), proposals)
    }

    /**
     * Copies a bundle's units into a project folder; the next sync proposes them. Units the folder already has with
     * the same files are skipped; units it has with other files follow [ProjectConfigEffect.ImportToProject.existing]:
     * asking writes nothing and names them, overwriting replaces their files, skipping keeps them.
     */
    suspend fun import(effect: ProjectConfigEffect.ImportToProject): Internal {
        val tree = when (val read = bundles.read(effect.path)) {
            is ConfigRead.Read -> access.group(read.tree)
            is ConfigRead.Failed -> return failed(effect.requestId, read.failure.taskFailure())
            ConfigRead.Absent -> return failed(effect.requestId, ConfigTaskFailure.Unreadable)
        }
        if (tree.units.isEmpty()) return failed(effect.requestId, ConfigTaskFailure.Empty)
        val folder = access.folder(effect.project) as? FolderRead.Read
            ?: return failed(effect.requestId, ConfigTaskFailure.Unreadable)
        val changed = tree.units.filterNot { folder.tree[it.ref]?.digest == it.digest }
        val existing = changed.filter { folder.tree[it.ref] != null }
        if (existing.isNotEmpty() && effect.existing == ExistingUnits.Ask) {
            log.i { "A bundle import into a project found ${existing.size} existing units" }
            return Internal.TaskDone(effect.requestId, ConfigTaskResult.ImportConflicts(existing.map { it.ref }))
        }
        val written = if (effect.existing == ExistingUnits.Skip) changed - existing.toSet() else changed
        // A replaced unit keeps no file of its old version: the bundle's files are the whole unit.
        val stale = written.flatMap { unit ->
            val paths = unit.files.map { it.path }.toSet()
            folder.tree[unit.ref]?.files.orEmpty().mapNotNull { file -> file.path.takeUnless { it in paths } }
        }
        return try {
            if (written.isNotEmpty()) access.write(effect.project, written.flatMap { it.files }, stale)
            val skipped = tree.units.size - written.size
            log.i { "Imported a bundle into a project: ${written.size} units, skipped $skipped" }
            Internal.TaskDone(effect.requestId, ConfigTaskResult.Imported(written.size, skipped))
        } catch (e: ConfigWriteException) {
            log.w(e.redactedFailure()) { "Bundle import into a project failed" }
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
        log.w(e.redactedFailure()) { "Writing a ${unit.ref.section} unit into a project failed" }
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

        ConfigWriteFailure.Name -> ConfigTaskFailure.Name

        ConfigWriteFailure.Unavailable, ConfigWriteFailure.NotDirectory, ConfigWriteFailure.Io ->
            ConfigTaskFailure.Unwritable
    }
}
