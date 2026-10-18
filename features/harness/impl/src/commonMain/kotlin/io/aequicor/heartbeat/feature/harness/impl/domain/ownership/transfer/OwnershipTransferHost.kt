package io.aequicor.heartbeat.feature.harness.impl.domain.ownership.transfer

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.process.ExecutorHostId
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipCommand
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipPhase
import io.aequicor.heartbeat.feature.harness.api.process.PortableProcessCheckpoint
import io.aequicor.heartbeat.feature.harness.api.process.ProcessOwnership
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.OwnershipCoordinatorClient
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.OwnershipRead
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.OwnershipUnavailable
import kotlinx.coroutines.CancellationException
import kotlin.uuid.Uuid

/**
 * Bounded host effects over the persisted ownership reducer. There are no timers, takeover paths or internal retry
 * loops. A failed/unknown CAS returns immediately; a later call observes durable phase and only reconciles the same
 * local receipts. Native user consent is required for beginning, claiming and cancelling, and binds the source or
 * target snapshot. Automatic phase progress never manufactures human consent or native termination evidence.
 */
internal class OwnershipTransferHost(
    private val actor: ExecutorHostId,
    private val coordinator: OwnershipCoordinatorClient,
    private val consent: OwnershipTransferConsent,
    private val local: OwnershipTransferLocal,
    private val artifacts: OwnershipTransferArtifacts,
    private val digests: OwnershipTransferDigests,
    private val freshId: () -> String = { Uuid.random().toHexString() },
) {
    private val log = Log.tag("OwnershipTransfer")

    suspend fun begin(binding: OwnershipTransferBinding, target: ExecutorHostId): OwnershipTransferResult =
        withSnapshot(binding) { context ->
            val snapshot = context.snapshot
            when {
                snapshot.fence.host != actor || target == actor -> refused(OwnershipTransferRefusal.Owner)

                snapshot.phase != OwnershipPhase.Active -> refused(OwnershipTransferRefusal.Phase)

                !approved(OwnershipTransferAction.Begin, context, target) -> refused(OwnershipTransferRefusal.Consent)

                else -> coordinated(
                    snapshot,
                    OwnershipCommand.Prepare(actor, snapshot.fence, freshId(), target),
                )
            }
        }

    suspend fun claim(binding: OwnershipTransferBinding): OwnershipTransferResult = withSnapshot(binding) { context ->
        val snapshot = context.snapshot
        val transfer = snapshot.transfer
        when {
            snapshot.phase != OwnershipPhase.Exported || transfer == null -> refused(OwnershipTransferRefusal.Phase)
            transfer.target != actor -> refused(OwnershipTransferRefusal.Owner)
            else -> claimExport(context)
        }
    }

    private suspend fun claimExport(context: OwnershipTransferContext): OwnershipTransferResult {
        val snapshot = context.snapshot
        val transfer = checkNotNull(snapshot.transfer)
        val manifest = artifacts.load(checkNotNull(transfer.checkpoint))
            ?: return OwnershipTransferResult.Unavailable(OwnershipUnavailable.Transport)
        return when {
            !context.binding.matches(manifest) -> refused(OwnershipTransferRefusal.Checkpoint)

            !approved(OwnershipTransferAction.Claim, context, actor, manifest) ->
                refused(OwnershipTransferRefusal.Consent)

            else -> coordinated(snapshot, OwnershipCommand.Claim(actor, transfer.source, transfer.id))
        }
    }

    suspend fun cancel(binding: OwnershipTransferBinding): OwnershipTransferResult = withSnapshot(binding) { context ->
        val snapshot = context.snapshot
        val transfer = snapshot.transfer
        when {
            snapshot.fence.host != actor -> refused(OwnershipTransferRefusal.Owner)

            transfer == null || snapshot.phase !in cancellable -> refused(OwnershipTransferRefusal.Phase)

            !approved(OwnershipTransferAction.Cancel, context, transfer.target) ->
                refused(OwnershipTransferRefusal.Consent)

            else -> coordinated(snapshot, OwnershipCommand.Cancel(actor, snapshot.fence, transfer.id))
        }
    }

    suspend fun advance(binding: OwnershipTransferBinding): OwnershipTransferResult = withSnapshot(binding) { context ->
        val snapshot = context.snapshot
        when {
            snapshot.fence.host != actor -> refused(OwnershipTransferRefusal.Owner)

            else -> when (snapshot.phase) {
                OwnershipPhase.Active, OwnershipPhase.Exported -> OwnershipTransferResult.Waiting(snapshot)
                OwnershipPhase.Preparing -> closeAdmissions(context)
                OwnershipPhase.Quiescing -> drain(context)
                OwnershipPhase.Reconciling -> reconcile(context)
            }
        }
    }

    private suspend fun closeAdmissions(context: OwnershipTransferContext): OwnershipTransferResult = when (
        val closed = local.closeAdmissions(context)
    ) {
        OwnershipLocalResult.Pending -> OwnershipTransferResult.Waiting(context.snapshot)

        is OwnershipLocalResult.Unavailable -> OwnershipTransferResult.Unavailable(closed.reason)

        is OwnershipLocalResult.Confirmed -> if (closed.receipt != context.snapshot) {
            refused(OwnershipTransferRefusal.Receipt)
        } else {
            coordinated(
                context.snapshot,
                OwnershipCommand.Quiesce(actor, context.snapshot.fence, context.transfer),
            )
        }
    }

    private suspend fun drain(context: OwnershipTransferContext): OwnershipTransferResult = when (
        val drained = local.drainAndSeal(context)
    ) {
        OwnershipLocalResult.Pending -> OwnershipTransferResult.Waiting(context.snapshot)
        is OwnershipLocalResult.Unavailable -> OwnershipTransferResult.Unavailable(drained.reason)
        is OwnershipLocalResult.Confirmed -> export(context, drained.receipt)
    }

    private suspend fun export(
        context: OwnershipTransferContext,
        receipt: OwnershipDrainReceipt,
    ): OwnershipTransferResult {
        val snapshot = receipt.snapshot
        return when {
            !context.accepts(snapshot) || !context.binding.matches(receipt.checkpoint) ->
                refused(OwnershipTransferRefusal.Receipt)

            receipt.executors != 0 || receipt.calls != 0 || snapshot.permits.isNotEmpty() ->
                OwnershipTransferResult.Waiting(snapshot)

            else -> storeExport(context, receipt)
        }
    }

    private suspend fun storeExport(
        context: OwnershipTransferContext,
        receipt: OwnershipDrainReceipt,
    ): OwnershipTransferResult {
        val snapshot = receipt.snapshot
        val pointer = artifacts.store(receipt.checkpoint)
            ?: return OwnershipTransferResult.Unavailable(OwnershipUnavailable.Transport)
        val digest = digests.checkpoint(receipt.checkpoint)
        return if (pointer.digest != digest) {
            refused(OwnershipTransferRefusal.Checkpoint)
        } else {
            coordinated(
                snapshot,
                OwnershipCommand.Export(
                    actor,
                    snapshot.fence,
                    context.transfer,
                    pointer,
                    receipt.executors,
                    receipt.calls,
                ),
            )
        }
    }

    private suspend fun reconcile(context: OwnershipTransferContext): OwnershipTransferResult =
        if (checkNotNull(context.snapshot.transfer).source.host == actor) {
            reconciliationResult(context, local.reconcileSource(context))
        } else {
            reconcileRecipient(context)
        }

    private suspend fun reconcileRecipient(context: OwnershipTransferContext): OwnershipTransferResult {
        val pointer = checkNotNull(checkNotNull(context.snapshot.transfer).checkpoint)
        val manifest = artifacts.load(pointer)
            ?: return OwnershipTransferResult.Unavailable(OwnershipUnavailable.Transport)
        return if (!context.binding.matches(manifest)) {
            refused(OwnershipTransferRefusal.Checkpoint)
        } else {
            reconciliationResult(context, local.reconcileRecipient(context, manifest))
        }
    }

    private suspend fun reconciliationResult(
        context: OwnershipTransferContext,
        result: OwnershipLocalResult<OwnershipReconciliationReceipt>,
    ): OwnershipTransferResult = when (result) {
        OwnershipLocalResult.Pending -> OwnershipTransferResult.Waiting(context.snapshot)
        is OwnershipLocalResult.Unavailable -> OwnershipTransferResult.Unavailable(result.reason)
        is OwnershipLocalResult.Confirmed -> activate(context, result.receipt)
    }

    private suspend fun activate(
        context: OwnershipTransferContext,
        receipt: OwnershipReconciliationReceipt,
    ): OwnershipTransferResult {
        val snapshot = receipt.snapshot
        val pointer = snapshot.transfer?.checkpoint
        return when {
            !context.accepts(snapshot) || receipt.checkpoint != pointer -> refused(OwnershipTransferRefusal.Receipt)

            receipt.unresolved != 0 || snapshot.permits.isNotEmpty() -> OwnershipTransferResult.Waiting(snapshot)

            else -> coordinated(
                snapshot,
                OwnershipCommand.Activate(actor, snapshot.fence, context.transfer, pointer, receipt.unresolved),
            )
        }
    }

    private suspend fun approved(
        action: OwnershipTransferAction,
        context: OwnershipTransferContext,
        target: ExecutorHostId,
        checkpoint: PortableProcessCheckpoint? = null,
    ): Boolean = consent.confirm(OwnershipTransferConsentRequest(action, context, target, checkpoint)) &&
        local.verifyBinding(context.binding)

    private suspend fun coordinated(snapshot: ProcessOwnership, command: OwnershipCommand): OwnershipTransferResult =
        OwnershipTransferResult.Coordinated(coordinator.apply(snapshot, command))

    private suspend fun withSnapshot(
        binding: OwnershipTransferBinding,
        block: suspend (OwnershipTransferContext) -> OwnershipTransferResult,
    ): OwnershipTransferResult = safely {
        if (binding.key != digests.key(binding.identity) || !local.verifyBinding(binding)) {
            refused(OwnershipTransferRefusal.Binding)
        } else {
            when (val read = coordinator.read(binding.key)) {
                is OwnershipRead.Unavailable -> OwnershipTransferResult.Unavailable(read.reason)

                is OwnershipRead.Current -> read.record?.let { block(OwnershipTransferContext(binding, it)) }
                    ?: OwnershipTransferResult.Unavailable(OwnershipUnavailable.NotConfigured)
            }
        }
    }

    private suspend fun safely(block: suspend () -> OwnershipTransferResult): OwnershipTransferResult = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(IllegalStateException("Process transfer is unavailable")) {
            "process transfer failed (${error::class.simpleName.orEmpty()})"
        }
        OwnershipTransferResult.Unavailable(OwnershipUnavailable.Transport)
    }
}

private val cancellable = setOf(OwnershipPhase.Preparing, OwnershipPhase.Quiescing, OwnershipPhase.Exported)

private fun refused(reason: OwnershipTransferRefusal): OwnershipTransferResult = OwnershipTransferResult.Refused(reason)

private fun OwnershipTransferContext.accepts(snapshot: ProcessOwnership): Boolean =
    snapshot.fence == this.snapshot.fence && snapshot.phase == this.snapshot.phase &&
        snapshot.transfer == this.snapshot.transfer && snapshot.revision >= this.snapshot.revision
