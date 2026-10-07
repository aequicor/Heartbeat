package io.aequicor.heartbeat.feature.harness.api

internal fun HarnessItem.isCode(): Boolean = this is HarnessItem.Script || this is HarnessItem.Workflow

internal fun HarnessState.Ready.project(harness: Harness, generation: Long): HarnessEntry = HarnessEntry(
    harness,
    harness.items.associate { item ->
        item.id to when {
            isSuspended || !harness.isEnabled || !item.isEnabled -> ItemStatus.Disabled
            !item.isCode() -> ItemStatus.Active(generation)
            !isRuntimeAvailable -> ItemStatus.Unsupported
            else -> ItemStatus.Pending(generation)
        }
    },
)

internal fun HarnessState.Ready.activations(): List<HarnessActivationRequest> = harnesses.flatMap { entry ->
    entry.harness.items.mapNotNull { item ->
        (entry.itemStatus[item.id] as? ItemStatus.Pending)?.let {
            HarnessActivationRequest(entry.harness, item, it.generation)
        }
    }
}

internal fun HarnessState.Ready.codeItems(): List<HarnessActivationRequest> = harnesses.flatMap { entry ->
    entry.harness.items.filter { it.isCode() }.map {
        HarnessActivationRequest(
            entry.harness,
            it,
            entry.itemStatus[it.id].generationOr(activationGeneration),
        )
    }
}

internal fun HarnessState.Ready.suspendLibrary(isSuspended: Boolean): LibraryTransition {
    val next = copy(isSuspended = isSuspended, activationGeneration = activationGeneration + 1).let { changed ->
        changed.copy(harnesses = harnesses.map { changed.project(it.harness, changed.activationGeneration) })
    }
    val effect = if (isSuspended) {
        harnesses.takeIf { it.isNotEmpty() }?.let {
            HarnessEffect.Deactivate(
                codeItems(),
                isStopping = false,
                harnesses = it.map { entry -> entry.harness.id }.toSet(),
                generation = next.activationGeneration,
            )
        }
    } else {
        next.activations().takeIf { it.isNotEmpty() }?.let(HarnessEffect::Activate)
    }
    return LibraryTransition(next, listOfNotNull(effect))
}

internal fun HarnessState.Ready.activated(
    id: HarnessId,
    item: ItemId,
    revision: Long,
    generation: Long,
    hasFailed: Boolean,
): LibraryTransition? {
    val entry = harnesses.firstOrNull { it.harness.id == id && it.harness.revision == revision } ?: return null
    if (entry.itemStatus[item] != ItemStatus.Pending(generation)) return null
    val status = if (hasFailed) ItemStatus.Failed(generation, false) else ItemStatus.Active(generation)
    val next = replaceStatus(id, item, status)
    val output = if (hasFailed) {
        HarnessOutput.ItemFailed(
            id,
            item,
            revision,
        )
    } else {
        HarnessOutput.ItemActivated(id, item, revision)
    }
    return LibraryTransition(next, output = output)
}

internal fun HarnessState.Ready.runtimeFailed(intent: HarnessIntent.Internal.ItemRuntimeFailed): LibraryTransition? {
    val entry =
        harnesses.firstOrNull { it.harness.id == intent.id && it.harness.revision == intent.revision } ?: return null
    val status = entry.itemStatus[intent.item]
    val isMatching = status == ItemStatus.Active(
        intent.generation,
    ) || status == ItemStatus.Pending(intent.generation) || status == ItemStatus.Failed(intent.generation, false)
    if (!isMatching || isSuspended) return null
    val next = replaceStatus(intent.id, intent.item, ItemStatus.Failed(intent.generation, intent.isDisabled))
    val item = entry.harness.items.first { it.id == intent.item }
    val effect = if (intent.isDisabled) {
        HarnessEffect.Deactivate(
            listOf(HarnessActivationRequest(entry.harness, item, intent.generation)),
            false,
            generation = intent.generation,
        )
    } else {
        null
    }
    return LibraryTransition(
        next,
        listOfNotNull(effect),
        HarnessOutput.ItemFailed(intent.id, intent.item, intent.revision),
    )
}

private fun HarnessState.Ready.replaceStatus(id: HarnessId, item: ItemId, status: ItemStatus): HarnessState.Ready =
    copy(
        harnesses = harnesses.map {
            if (it.harness.id == id) {
                it.copy(
                    itemStatus = it.itemStatus + (item to status),
                )
            } else {
                it
            }
        },
    )

internal fun HarnessState.Ready.commitSave(mutation: HarnessMutation.Save): LibraryTransition {
    val next = copy(
        revision = revision + 1,
        pending = pending - mutation.harness.id,
        activationGeneration = activationGeneration + 1,
    )
    val entry = next.project(mutation.harness, next.activationGeneration)
    val old = harnesses.firstOrNull { it.harness.id == mutation.harness.id }
    val committed = next.copy(
        harnesses = (
            harnesses.filterNot {
                it.harness.id == mutation.harness.id
            } + entry
        ).sortedBy { it.harness.name.value },
    )
    val deactivated = old?.harness?.items.orEmpty().asSequence().filter { item ->
        item.isCode() &&
            (
                !mutation.harness.isEnabled ||
                    mutation.harness.items.none { it.id == item.id && it.isEnabled && it.isCode() }
            )
    }.map {
        HarnessActivationRequest(
            checkNotNull(old).harness,
            it,
            old.itemStatus[it.id].generationOr(activationGeneration),
        )
    }.toList()
    val activations = committed.activations().filter { it.harness.id == mutation.harness.id }
    val effects = listOfNotNull(
        deactivated.takeIf { it.isNotEmpty() || !mutation.harness.isEnabled }?.let {
            HarnessEffect.Deactivate(
                it,
                !mutation.harness.isEnabled,
                if (mutation.harness.isEnabled) emptySet() else setOf(mutation.harness.id),
                generation = next.activationGeneration,
            )
        },
        activations.takeIf { it.isNotEmpty() }?.let(HarnessEffect::Activate),
    )
    val output = if (mutation.isCreated) {
        HarnessOutput.Created(mutation.receipt.requestId, mutation.harness.id)
    } else {
        HarnessOutput.Updated(mutation.receipt.requestId, mutation.harness.id)
    }
    return LibraryTransition(committed, effects, output)
}

private fun ItemStatus?.generationOr(fallback: Long): Long = when (this) {
    is ItemStatus.Active -> generation
    is ItemStatus.Pending -> generation
    is ItemStatus.Failed -> generation
    ItemStatus.Disabled, ItemStatus.Unsupported, null -> fallback
}

internal fun HarnessState.Ready.activationBatchFailed(items: List<HarnessActivationRequest>): LibraryTransition? {
    var next = this
    val failed = items.filter { item ->
        val transition = next.activated(item.harness.id, item.item.id, item.harness.revision, item.generation, true)
        if (transition != null) next = transition.state
        transition != null
    }
    return if (failed.isEmpty()) null else LibraryTransition(next, output = HarnessOutput.RuntimeFailed(failed))
}
