package io.aequicor.heartbeat.feature.projectconfig.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReviewer
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigApplyContext
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigApplyResult
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigBinding
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigCheck
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigProposal
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigRejection
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigSource
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnit
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigWriteBack
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigEffect
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigIntent
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigIntent.Internal
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectSnapshot
import io.aequicor.heartbeat.feature.projectconfig.api.ProposalKind
import io.aequicor.heartbeat.feature.projectconfig.api.ProposalReview
import io.aequicor.heartbeat.feature.projectconfig.api.SyncRequest
import io.aequicor.heartbeat.feature.projectconfig.api.spi.ConfigSection
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigBindingStorage
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigReviews
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigWriteException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/**
 * Executes the machine's effects: reads folders through [ConfigFolderAccess], checks and applies units only through
 * their sections, reviews with the profile's default model and writes bindings. Every effect answers with one result
 * per element; only counts, kinds and verdicts are logged, never texts, paths or review reasons.
 */
@Inject
internal class ProjectConfigEffects(
    private val access: ConfigFolderAccess,
    private val tasks: ConfigTasks,
    private val storage: ConfigBindingStorage,
    private val reviewer: Lazy<AgentSafetyReviewer>,
    private val selections: ModelSelections,
    private val dispatchers: DispatcherProvider,
) : EffectHandler<ProjectConfigEffect, ProjectConfigIntent> {
    private val log = Log.tag("ProjectConfigEffects")
    private val saving = Mutex()
    private var savedRevision = -1L

    // Sections check, compile and encode units (interface specs up to 2 MiB): the work stays off the main thread.
    override suspend fun handle(effect: ProjectConfigEffect, machine: EffectScope<ProjectConfigIntent>): Unit =
        withContext(dispatchers.default) { dispatch(effect, machine) }

    private suspend fun dispatch(effect: ProjectConfigEffect, machine: EffectScope<ProjectConfigIntent>) {
        when (effect) {
            ProjectConfigEffect.Load -> {
                val bindings = storage.load()
                log.d { "Configuration bindings loaded: ${bindings.size}" }
                machine.send(Internal.Loaded(bindings))
            }

            is ProjectConfigEffect.SaveBindings -> save(effect)

            is ProjectConfigEffect.Sync -> effect.requests.forEach { machine.send(sync(it)) }

            is ProjectConfigEffect.Review -> effect.proposals.forEach { machine.send(review(it)) }

            is ProjectConfigEffect.Apply -> machine.send(apply(effect.proposal))

            is ProjectConfigEffect.WriteBack -> effect.writeBacks.forEach { machine.send(writeBack(it)) }

            is ProjectConfigEffect.SaveToProject, is ProjectConfigEffect.Export, is ProjectConfigEffect.OpenBundle,
            is ProjectConfigEffect.ImportToProject, is ProjectConfigEffect.CloseBundle,
            -> tasks.handle(effect)?.let { machine.send(it) }
        }
    }

    private suspend fun save(effect: ProjectConfigEffect.SaveBindings) = saving.withLock {
        if (effect.revision <= savedRevision) {
            log.v { "Stale bindings save skipped: ${effect.revision} <= $savedRevision" }
        } else {
            storage.save(effect.bindings)
            savedRevision = effect.revision
            log.v { "Bindings saved: ${effect.bindings.size}, revision ${effect.revision}" }
        }
    }

    private suspend fun sync(request: SyncRequest): Internal {
        val folder = when (val read = access.folder(request.project)) {
            is FolderRead.Failed -> return Internal.SyncFailed(listOf(request), read.failure)
            is FolderRead.Read -> read
        }
        val read = access.reader.sections()
        val entities = access.reader.entities(read, request.project, request.bindings)
        val sections = read.without(entities.unreadable)
        val units = access.reader.scan(folder.tree, sections, request.bindings)
        val snapshot = ProjectSnapshot(
            request.project,
            request.generation,
            units,
            entities.digests,
            folder.tree.unclaimed,
            sections.unavailable,
        )
        log.v { "Synced project: ${units.size} units, ${entities.digests.size} entities" }
        return Internal.Synced(snapshot, folder.hasFolder)
    }

    private suspend fun review(proposal: ConfigProposal): Internal {
        val digest = checkNotNull(proposal.digest) { "Removals are not reviewed" }
        val unit = access.unit(proposal) ?: return Internal.Reviewed(proposal.id, digest, ProposalReview.Unavailable)
        val section = access.reader.sections()[unit.ref.section]
        val current = proposal.target?.let { target ->
            section?.encode(target, proposal.unit.name)
        }
        val change = ConfigReviews.change(proposal, unit, current, Uuid.random().toHexString())
        val target = selections.observe().first().defaultTarget
        val result = if (section == null || change == null || target == null) {
            ProposalReview.Unavailable
        } else {
            ConfigReviews.result(reviewer.value.review(target, ConfigReviews.policy(section.reviewPolicy), change))
        }
        log.i { "Reviewed ${proposal.kind} of a ${proposal.unit.section} unit: $result" }
        return Internal.Reviewed(proposal.id, digest, result)
    }

    private suspend fun apply(proposal: ConfigProposal): Internal {
        val sections = access.reader.sections()
        val section = sections[proposal.unit.section]
        if (section == null || proposal.unit.section in sections.unavailable) {
            return Internal.ApplyFailed(proposal.id, ConfigRejection.Unavailable)
        }
        if (proposal.kind == ProposalKind.Remove) return remove(proposal, section)
        // Exactly the reviewed files: a unit changed since then waits for its new proposal.
        val unit = access.unit(proposal) ?: return Internal.ApplyFailed(proposal.id, ConfigRejection.Conflict)
        val project = (proposal.source as? ConfigSource.Project)?.project
        val parent = section.parent?.let { parentSection ->
            sections.withIdentity(parentSection, section.parentOf(unit.ref.name).orEmpty())?.key
                ?: return Internal.ApplyFailed(proposal.id, ConfigRejection.MissingParent)
        }
        val result = section.apply(unit, proposal.target, ConfigApplyContext(project, parent))
        log.i { "Applied ${proposal.kind} of a ${proposal.unit.section} unit: ${result::class.simpleName.orEmpty()}" }
        return when (result) {
            is ConfigApplyResult.Rejected -> Internal.ApplyFailed(proposal.id, result.reason)

            is ConfigApplyResult.Applied -> Internal.Applied(
                proposal.id,
                project?.let { ref ->
                    // The entity as applied, not as it may already be edited again: such an edit is written back.
                    val canonical = (section.check(unit) as? ConfigCheck.Valid)?.canonical ?: unit
                    ConfigBinding(ref, unit.ref, result.key, unit.digest, canonical.digest)
                },
            )
        }
    }

    /** Removes the target of [proposal]; an entity that is already gone counts as removed. */
    private suspend fun remove(proposal: ConfigProposal, section: ConfigSection): Internal {
        val result = proposal.target?.let { section.remove(it) }
        log.i { "Removed a ${proposal.unit.section} entity: ${result?.let { it::class.simpleName }.orEmpty()}" }
        val reason = (result as? ConfigApplyResult.Rejected)?.reason?.takeUnless { it == ConfigRejection.NotFound }
        return if (reason == null) Internal.Applied(proposal.id, null) else Internal.ApplyFailed(proposal.id, reason)
    }

    private suspend fun writeBack(writeBack: ConfigWriteBack): Internal {
        val folder = access.folder(writeBack.project) as? FolderRead.Read
        val section = access.reader.sections()[writeBack.unit.section]
        val encoded = writeBack.key?.let { section?.encode(it, writeBack.unit.name) }
        val isWritable = folder != null && section != null && (writeBack.key == null || encoded?.ref == writeBack.unit)
        if (!isWritable) return Internal.WriteBackFailed(listOf(writeBack))
        val current = folder.tree[writeBack.unit]?.files.orEmpty().map { it.path }
        val put = encoded?.files.orEmpty()
        return try {
            access.write(writeBack.project, put, current - put.map { it.path }.toSet())
            log.i { "Wrote back a ${writeBack.unit.section} unit: ${if (encoded == null) "deleted" else "updated"}" }
            Internal.WrittenBack(writeBack, encoded?.let { binding(writeBack, it) })
        } catch (e: ConfigWriteException) {
            log.w(e.redactedFailure()) { "Write-back of a ${writeBack.unit.section} unit failed" }
            Internal.WriteBackFailed(listOf(writeBack))
        }
    }

    private fun binding(writeBack: ConfigWriteBack, unit: ConfigUnit): ConfigBinding =
        ConfigBinding(writeBack.project, writeBack.unit, checkNotNull(writeBack.key), unit.digest, unit.digest)
}
