package io.aequicor.heartbeat.feature.aiengine.connections.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckGeneration
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckReceipt
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckRevision
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelection
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.uuid.Uuid

/**
 * Profile-local authority over model-check runs, separate from persisted historical observations. Revocation is
 * synchronous and never waits for storage/adapter cleanup. A newer run may replace only its targets, preserving
 * unrelated models. Unknown credential revisions use a fresh profile nonce, never one recovered from a receipt.
 * All read methods are pure; they cannot discover models, resolve secrets or grant session admission.
 */
internal class ModelCheckOwner {
    private val log = Log.tag("ModelCheckOwner")
    private val profileAuth = generation()
    private val state = MutableStateFlow(Ownership())
    val changes: Flow<Unit> = state.map { Unit }

    fun begin(targets: Set<EngineTarget>): ModelCheckGeneration {
        val run = generation()
        val bindings = targets.map { it.binding }.toSet()
        state.update {
            it.copy(
                runs = it.runs + targets.associateWith { run },
                runSources = it.runSources + targets.associateWith { _ -> it.availableSources() },
                epochs = it.epochs + bindings.associateWith { run },
            )
        }
        log.v { "Model check ownership replaced targets=${targets.size}" }
        return run
    }

    fun revoke(targets: Set<EngineTarget>) {
        val epoch = generation()
        state.update {
            it.copy(
                runs = it.runs + targets.associateWith { null },
                epochs = it.epochs + targets.associate { target -> target.binding to epoch },
            )
        }
        log.v { "Model check ownership revoked targets=${targets.size}" }
    }

    /** Disconnect, binding enablement changes and source edits invalidate every model of that connection. */
    fun revoke(binding: EngineBindingId) {
        val auth = generation()
        state.update {
            it.copy(
                runs = it.runs.filterKeys { target -> target.binding != binding },
                runSources = it.runSources.filterKeys { target -> target.binding != binding },
                blocked = it.blocked + binding,
                auth = it.auth + (binding to auth),
                epochs = it.epochs + (binding to auth),
            )
        }
        log.v { "Model check binding ownership revoked" }
    }

    /** Fence every route of a source before editing, including bindings created concurrently. */
    fun beginSourceEdit(source: AuthSourceId): ModelCheckSourceEdit {
        val token = generation()
        state.update {
            it.copy(
                sourceVersions = it.sourceVersions + (source to token),
                editing = it.editing + (source to (it.editing[source].orEmpty() + token)),
            )
        }
        log.v { "Model check source edit started" }
        return ModelCheckSourceEdit(source, token)
    }

    /** Release only this edit; overlapping writes keep the source fenced until all finish. */
    fun finishSourceEdit(edit: ModelCheckSourceEdit) {
        state.update {
            val remaining = it.editing[edit.source].orEmpty() - edit.token
            val editing = if (remaining.isEmpty()) {
                it.editing - edit.source
            } else {
                it.editing + (edit.source to remaining)
            }
            it.copy(editing = editing)
        }
        log.v { "Model check source edit finished" }
    }

    /** Wait within the batch deadline for overlapping writes, unless a newer edit has superseded this one. */
    suspend fun awaitSource(edit: ModelCheckSourceEdit): Boolean {
        val current = state.first { it.sourceVersions[edit.source] != edit.token || edit.source !in it.editing }
        return current.sourceVersions[edit.source] == edit.token
    }

    /** Reserve known bindings and snapshot other ownership before a source write. */
    fun reserve(bindings: Set<EngineBindingId>, edit: ModelCheckSourceEdit? = null): ModelCheckReservation {
        val run = generation()
        while (true) {
            val previous = state.value
            val next = previous.copy(epochs = previous.epochs + bindings.associateWith { run })
            if (state.compareAndSet(previous, next)) {
                log.v { "Model check bindings reserved count=${bindings.size}" }
                val sources = previous.availableSources().toMutableMap()
                if (edit != null && previous.sourceVersions[edit.source] == edit.token &&
                    edit.token in previous.editing[edit.source].orEmpty()
                ) {
                    sources[edit.source] = edit.token
                }
                return ModelCheckReservation(run, bindings, previous.epochs, sources)
            }
        }
    }

    /** Claim only bindings whose ownership did not change since this save started. */
    fun claim(reservation: ModelCheckReservation, targets: Set<EngineTarget>): Set<EngineTarget> {
        while (true) {
            val previous = state.value
            val accepted = targets.filterTo(mutableSetOf()) {
                val expected = if (it.binding in
                    reservation.bindings
                ) {
                    reservation.run
                } else {
                    reservation.epochs[it.binding]
                }
                previous.epochs[it.binding] == expected
            }
            val discovered = accepted.map { it.binding }.toSet() - reservation.bindings
            val next = previous.copy(
                runs = previous.runs + accepted.associateWith { reservation.run },
                runSources = previous.runSources + accepted.associateWith { reservation.sources },
                epochs = previous.epochs + accepted.associate { it.binding to reservation.run },
                // A newly discovered binding did not receive the source-wide revocation before the write.
                blocked = previous.blocked + discovered,
                auth = previous.auth + discovered.associateWith { reservation.run },
            )
            if (state.compareAndSet(previous, next)) {
                log.v { "Model check targets claimed count=${accepted.size}" }
                return accepted
            }
        }
    }

    fun revision(target: EngineTarget, context: ModelCheckContext): ModelCheckRevision? =
        revision(target, context, state.value)

    fun isCurrent(run: ModelCheckGeneration, revision: ModelCheckRevision, context: ModelCheckContext): Boolean {
        val owner = state.value
        return owner.runs[revision.target] == run && owner.acceptsSource(revision.target, revision.source.info.id) &&
            revision == revision(revision.target, context, owner)
    }

    /**
     * Known-revision receipts can survive reload when the full context and selection generation still match.
     * Once this owner begins/revokes a target, only its explicitly current run is accepted. A binding-wide revoke
     * also hides targets that were never checked by this owner, including after disable/re-enable with the same id.
     */
    fun accepts(receipt: ModelCheckReceipt, context: ModelCheckContext): Boolean {
        val owner = state.value
        val target = receipt.revision.target
        val isOwned = if (target in owner.runs) {
            owner.runs[target] == receipt.run
        } else {
            target.binding !in owner.blocked
        }
        return isOwned && owner.acceptsSource(target, receipt.revision.source.info.id) &&
            receipt.revision == revision(target, context, owner)
    }

    private fun revision(target: EngineTarget, context: ModelCheckContext, owner: Ownership): ModelCheckRevision? {
        val selection = context.selection.checkGeneration(target) ?: return null
        val engine = context.engines.singleOrNull { it.descriptor.id == target.engine } ?: return null
        if (engine.availability != EngineAvailability.Available) return null
        val binding = context.bindings.singleOrNull { it.id == target.binding } ?: return null
        if (!binding.isEnabled || binding.engine != target.engine) return null
        val source =
            context.sources.singleOrNull { it.info.id == binding.authSource }?.modelCheckContext() ?: return null
        return if (isBounded(target, source)) {
            ModelCheckRevision(
                target,
                ModelCheckGeneration(selection),
                source,
                ModelCheckRevision.CURRENT_DEFINITION,
                if (source.info.revision == AuthRevision.Unknown) {
                    combineGeneration(owner.auth[target.binding] ?: profileAuth, owner.sourceVersions[source.info.id])
                } else {
                    null
                },
            )
        } else {
            null
        }
    }

    private fun isBounded(target: EngineTarget, source: AuthSource): Boolean {
        val identifiers = listOf(
            target.engine.value,
            target.binding.value,
            target.model.value,
            source.scope.origin.value,
        )
        val revision = source.info.revision
        return identifiers.all { it.length <= MAX_ID_CHARS } &&
            (revision !is AuthRevision.Known || revision.fingerprint.length <= MAX_REVISION_CHARS)
    }
}

internal data class ModelCheckReservation(
    val run: ModelCheckGeneration,
    val bindings: Set<EngineBindingId>,
    val epochs: Map<EngineBindingId, ModelCheckGeneration>,
    val sources: Map<AuthSourceId, ModelCheckGeneration>,
)

internal data class ModelCheckSourceEdit(val source: AuthSourceId, val token: ModelCheckGeneration)

/** Canonical cached route data and the actual storage-owned selection; no discovery is performed to obtain it. */
internal data class ModelCheckContext(
    val selection: ModelSelection,
    val engines: List<EngineInfo>,
    val bindings: List<EngineBinding>,
    val sources: List<AuthSource>,
) {
    override fun toString(): String = "ModelCheckContext"
}

private data class Ownership(
    val runs: Map<EngineTarget, ModelCheckGeneration?> = emptyMap(),
    val blocked: Set<EngineBindingId> = emptySet(),
    val auth: Map<EngineBindingId, ModelCheckGeneration> = emptyMap(),
    val epochs: Map<EngineBindingId, ModelCheckGeneration> = emptyMap(),
    val sourceVersions: Map<AuthSourceId, ModelCheckGeneration> = emptyMap(),
    val editing: Map<AuthSourceId, Set<ModelCheckGeneration>> = emptyMap(),
    val runSources: Map<EngineTarget, Map<AuthSourceId, ModelCheckGeneration>> = emptyMap(),
) {
    fun availableSources(): Map<AuthSourceId, ModelCheckGeneration> = sourceVersions - editing.keys

    fun acceptsSource(target: EngineTarget, source: AuthSourceId): Boolean = source !in editing &&
        if (target in runs) runSources[target]?.get(source) == sourceVersions[source] else source !in sourceVersions

    override fun toString(): String = "ModelCheckOwnership"
}

private fun generation(): ModelCheckGeneration = ModelCheckGeneration(Uuid.random().toString())
private fun combineGeneration(a: ModelCheckGeneration, b: ModelCheckGeneration?): ModelCheckGeneration {
    if (b == null) return a
    val value = a.value.zip(b.value) { left, right ->
        if (left == '-') '-' else (left.digitToInt(16) xor right.digitToInt(16)).toString(16).single()
    }.joinToString("")
    return ModelCheckGeneration(value)
}
private const val MAX_ID_CHARS = 1024
private const val MAX_REVISION_CHARS = 256
