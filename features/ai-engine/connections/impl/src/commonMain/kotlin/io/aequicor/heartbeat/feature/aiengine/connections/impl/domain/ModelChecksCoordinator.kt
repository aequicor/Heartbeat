package io.aequicor.heartbeat.feature.aiengine.connections.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckGeneration
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckReceipt
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckReport
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckRevision
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelection
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Shared profile owner used by both connection machines. Save/check execution has one bounded UI wait; historical
 * publication/cleanup runs separately in the profile and cannot extend that wait. Only [observe] exposes persisted
 * receipts as current: all execution results, including partial reports, remain provisional. Reading never writes,
 * starts a runtime, discovers models or sends a request. Failures of diagnostic persistence never undo model choices.
 */
internal class ModelChecksCoordinator(
    private val services: EngineServices,
    private val selections: ModelSelections,
    private val reports: ModelCheckHistory,
    private val scope: CoroutineScope,
    private val batch: ModelCheckBatch,
) : ModelChecks {
    private val owner = ModelCheckOwner()
    private val log = Log.tag("ModelChecksCoordinator")

    override fun observe(): Flow<List<ModelCheckReceipt>> {
        val facade = services.facade
        val contexts = combine(
            selections.observe(),
            facade.engines.state,
            facade.bindings.state,
            services.sources.state,
        ) { selection, engines, bindings, sources -> ModelCheckContext(selection, engines, bindings, sources) }
        return combine(contexts, reports.observe(), owner.changes) { context, history, _ ->
            current(history, context)
        }
    }

    override fun current(receipts: List<ModelCheckReceipt>, context: ModelCheckContext): List<ModelCheckReceipt> =
        receipts.filter { owner.accepts(it, context) }

    override suspend fun saveAndCheck(
        targets: Set<EngineTarget>,
        onSaved: suspend () -> Unit,
        onFinished: () -> Unit,
        save: suspend () -> ModelSelection,
    ): ModelCheckBatchResult<ModelSelection> {
        val run = owner.begin(targets)
        val result = batch.execute(
            ModelCheckBatchRequest(
                run = run,
                save = save,
                onSaved = onSaved,
                onFinished = onFinished,
                revisions = { saved ->
                    targets.mapNotNull { target ->
                        owner.revision(target, currentContext(target))?.takeIf {
                            saved.checkGeneration(target) == it.selection.value
                        }
                    }
                },
                isCurrent = { isCurrent(run, it) },
            ),
        )
        val completed = when (result) {
            is ModelCheckBatchResult.Completed -> result.reports
            is ModelCheckBatchResult.TimedOut -> result.reports
            is ModelCheckBatchResult.Failed -> result.reports
        }
        publish(completed)
        return result
    }

    /** Call before disabling/removing a model or before replacing its selection, even when persistence is pending. */
    override fun revoke(targets: Set<EngineTarget>) {
        owner.revoke(targets)
        clearObsolete(targets.map { it.binding }.toSet())
    }

    /** Call before disconnect/rollback, enablement changes or a key/endpoint/source edit. */
    override fun revoke(binding: EngineBindingId) {
        owner.revoke(binding)
        clearObsolete(setOf(binding))
    }

    private suspend fun currentContext(target: EngineTarget): ModelCheckContext {
        val selection = selections.observe().first()
        val facade = services.facade
        val bindings = facade.bindings.state.value
        // Source observation may lag a just-confirmed edit. Explicit checks must use authoritative snapshots;
        // the passive observer remains flow-only and will display receipts once its own snapshot catches up.
        val sourceId = bindings.singleOrNull { it.id == target.binding }?.authSource
        val sources = listOfNotNull(sourceId?.let { services.sources.get(it) })
        return ModelCheckContext(
            selection,
            facade.engines.state.value,
            bindings,
            sources,
        )
    }

    private suspend fun isCurrent(run: ModelCheckGeneration, revision: ModelCheckRevision): Boolean =
        owner.isCurrent(run, revision, currentContext(revision.target))

    private fun publish(completed: List<ModelCheckReport>) {
        scope.launch {
            try {
                for (report in completed) reports.put(report) { isCurrent(report.run, report.revision) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.w(error.redactedFailure()) { "Model check observations could not be persisted" }
            }
        }
    }

    private fun clearObsolete(bindings: Set<EngineBindingId>) {
        scope.launch {
            try {
                for (binding in bindings) {
                    reports.remove(binding) { owner.accepts(it, currentContext(it.revision.target)) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.w(error.redactedFailure()) { "Obsolete model check observations could not be removed" }
            }
        }
    }
}
