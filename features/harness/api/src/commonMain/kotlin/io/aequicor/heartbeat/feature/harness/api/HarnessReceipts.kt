package io.aequicor.heartbeat.feature.harness.api

internal fun HarnessState.Ready.feedback(intent: HarnessIntent.Internal): LibraryTransition? = when (intent) {
    is HarnessIntent.Internal.Storage -> storageFeedback(intent)
    is HarnessIntent.Internal.Runtime -> runtimeFeedback(intent)
    HarnessIntent.Internal.Suspended -> if (isSuspended) null else suspendLibrary(true)
    HarnessIntent.Internal.Resumed -> if (isSuspended) suspendLibrary(false) else null
    HarnessIntent.Internal.Start -> null
}

private fun HarnessState.Ready.storageFeedback(intent: HarnessIntent.Internal.Storage): LibraryTransition? =
    when (intent) {
        is HarnessIntent.Internal.Saved -> saved(intent.receipt)
        is HarnessIntent.Internal.SaveFailed -> failedSave(intent.receipt)
        is HarnessIntent.Internal.Removed -> removed(intent.receipt)
        is HarnessIntent.Internal.RemoveFailed -> failedRemove(intent.receipt)
        is HarnessIntent.Internal.AttachmentsSaved -> attachmentsSaved(intent.write)
        is HarnessIntent.Internal.AttachmentsFailed -> attachmentsFailed(intent.write)
        is HarnessIntent.Internal.ApprovalSaved -> approvalSaved(intent.write)
        is HarnessIntent.Internal.ApprovalFailed -> approvalFailed(intent.write)
        is HarnessIntent.Internal.Loaded -> reloaded(intent)
        is HarnessIntent.Internal.LoadFailed -> reloadFailed(intent.load)
    }

private fun HarnessState.Ready.runtimeFeedback(intent: HarnessIntent.Internal.Runtime): LibraryTransition? =
    when (intent) {
        is HarnessIntent.Internal.ItemActivated -> activated(
            intent.id,
            intent.item,
            intent.revision,
            intent.generation,
            false,
        )

        is HarnessIntent.Internal.ItemActivationFailed -> activated(
            intent.id,
            intent.item,
            intent.revision,
            intent.generation,
            true,
        )

        is HarnessIntent.Internal.ItemRuntimeFailed -> runtimeFailed(intent)

        is HarnessIntent.Internal.ActivationBatchFailed -> activationBatchFailed(intent.items)
    }

private fun HarnessState.Ready.saved(receipt: HarnessReceipt): LibraryTransition? {
    val mutation = pending[receipt.id] as? HarnessMutation.Save ?: return null
    return if (mutation.receipt == receipt) commitSave(mutation) else null
}

private fun HarnessState.Ready.failedSave(receipt: HarnessReceipt): LibraryTransition? {
    val mutation = pending[receipt.id] as? HarnessMutation.Save ?: return null
    return if (mutation.receipt == receipt) storageFailure(receipt) else null
}

private fun HarnessState.Ready.removed(receipt: HarnessReceipt): LibraryTransition? {
    val mutation = pending[receipt.id] as? HarnessMutation.Remove ?: return null
    if (mutation.receipt != receipt) return null
    return LibraryTransition(
        copy(
            revision = revision + 1,
            pending = pending - receipt.id,
            harnesses = harnesses.filterNot { it.harness.id == receipt.id },
            attachments = attachments.mapValues { it.value - receipt.id }.filterValues { it.isNotEmpty() },
        ),
        output = HarnessOutput.Deleted(receipt.requestId, receipt.id),
    )
}

private fun HarnessState.Ready.failedRemove(receipt: HarnessReceipt): LibraryTransition? {
    val mutation = pending[receipt.id] as? HarnessMutation.Remove ?: return null
    return if (mutation.receipt == receipt) storageFailure(receipt) else null
}

private fun HarnessState.Ready.storageFailure(receipt: HarnessReceipt) = LibraryTransition(
    copy(pending = pending - receipt.id),
    output = HarnessOutput.StorageFailed(receipt.requestId),
)

private fun HarnessState.Ready.attachmentsSaved(write: HarnessAttachmentWrite): LibraryTransition? {
    if (attachmentWrite != write) return null
    val output = if (write.isAttached) {
        HarnessOutput.Attached(write.requestId, write.id, write.session)
    } else {
        HarnessOutput.Detached(write.requestId, write.id, write.session)
    }
    return LibraryTransition(
        copy(attachments = write.attachments, attachmentWrite = null, revision = revision + 1),
        output = output,
    )
}

private fun HarnessState.Ready.attachmentsFailed(write: HarnessAttachmentWrite): LibraryTransition? =
    if (attachmentWrite == write) {
        LibraryTransition(
            copy(attachmentWrite = null),
            output = HarnessOutput.StorageFailed(write.requestId),
        )
    } else {
        null
    }

private fun HarnessState.Ready.approvalSaved(write: HarnessApprovalWrite): LibraryTransition? =
    if (approvalWrite == write) {
        LibraryTransition(
            copy(
                approval = write.level,
                approvalRevision = write.revision,
                approvalWrite = null,
                revision = revision + 1,
            ),
            output = HarnessOutput.ApprovalChanged(write.requestId, write.level),
        )
    } else {
        null
    }

private fun HarnessState.Ready.approvalFailed(write: HarnessApprovalWrite): LibraryTransition? =
    if (approvalWrite == write) {
        LibraryTransition(
            copy(approvalWrite = null),
            output = HarnessOutput.StorageFailed(write.requestId),
        )
    } else {
        null
    }

internal fun HarnessIntent.Internal.Loaded.isValid(): Boolean = approvalRevision >= 0 &&
    isHarnessLibraryWithinLimits(harnesses) && validAttachments(harnesses, attachments)

internal fun HarnessIntent.Internal.Loaded.ready(
    isSuspended: Boolean,
    revision: Long = 0,
    generation: Long = 1,
): HarnessState.Ready {
    val next = HarnessState.Ready(
        attachments = attachments,
        approval = approval,
        approvalRevision = approvalRevision,
        isRuntimeAvailable = isRuntimeAvailable,
        isSuspended = isSuspended,
        revision = revision,
        loadGeneration = load.generation,
        activationGeneration = generation,
    )
    return next.copy(harnesses = harnesses.sortedBy { it.name.value }.map { next.project(it, generation) })
}

private fun HarnessState.Ready.reloaded(intent: HarnessIntent.Internal.Loaded): LibraryTransition? {
    if (reload != intent.load) return null
    if (!intent.load.isApplicable || revision != intent.load.revision || hasWrites()) {
        return LibraryTransition(
            copy(reload = null),
        )
    }
    if (!intent.isValid()) return LibraryTransition(copy(reload = null), output = HarnessOutput.StorageFailed(null))
    val next = intent.ready(isSuspended, revision + 1, activationGeneration + 1).copy(writeGeneration = writeGeneration)
    return LibraryTransition(
        next,
        listOfNotNull(
            codeItems().takeIf { it.isNotEmpty() }?.let {
                HarnessEffect.Deactivate(
                    it,
                    false,
                    generation = activationGeneration,
                )
            },
            next.activations().takeIf { it.isNotEmpty() }?.let(HarnessEffect::Activate),
        ),
    )
}

private fun HarnessState.Ready.reloadFailed(load: HarnessLoad): LibraryTransition? =
    if (reload == load) LibraryTransition(copy(reload = null), output = HarnessOutput.StorageFailed(null)) else null
