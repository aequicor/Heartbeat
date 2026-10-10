package io.aequicor.heartbeat.feature.harness.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlin.time.Instant

internal fun HarnessState.Ready.reject(request: RequestId, reason: HarnessRejection) =
    LibraryTransition(this, output = HarnessOutput.Rejected(request, reason))

internal fun HarnessState.Ready.command(intent: HarnessIntent.Public): LibraryTransition = when (intent) {
    is HarnessIntent.Public.Create -> create(intent)
    is HarnessIntent.Public.Update -> update(intent)
    is HarnessIntent.Public.Delete -> remove(intent)
    is HarnessIntent.Public.SetEnabled -> enable(intent)
    is HarnessIntent.Public.SetItemEnabled -> enableItem(intent)
    is HarnessIntent.Public.Attach -> attach(intent.requestId, intent.id, intent.session, true)
    is HarnessIntent.Public.Detach -> attach(intent.requestId, intent.id, intent.session, false)
    is HarnessIntent.Public.SetApproval -> approve(intent)
    is HarnessIntent.Public.Reload -> reload(intent)
}

private fun HarnessState.Ready.create(intent: HarnessIntent.Public.Create): LibraryTransition {
    val draft = intent.draft
    val reserved = reservedHarnesses()
    if (reserved.any { it.id == intent.id || it.name == draft.name }) {
        return reject(intent.requestId, HarnessRejection.Duplicate)
    }
    if (!draft.items.haveUniqueIdentity()) return reject(intent.requestId, HarnessRejection.Invalid)
    if (draft.items.size > HarnessLimits.ITEMS) return reject(intent.requestId, HarnessRejection.Limit)
    val harness = Harness(
        intent.id, draft.name, draft.title, draft.description, draft.scope, draft.isEnabled, draft.items,
        draft.tools, intent.author, 0, intent.at, intent.at,
    )
    return save(intent.requestId, harness, true)
}

private fun HarnessState.Ready.update(intent: HarnessIntent.Public.Update): LibraryTransition {
    mutationRejection(intent.id, intent.expectedRevision)?.let { return reject(intent.requestId, it) }
    val old = harnesses.first { it.harness.id == intent.id }.harness
    val invalid = old.changeRejection(intent.change)
    return if (invalid != null) {
        reject(intent.requestId, invalid)
    } else {
        val changed = when (val change = intent.change) {
            is HarnessChange.Meta -> old.copy(title = change.title, description = change.description)
            is HarnessChange.Scope -> old.copy(scope = change.scope)
            is HarnessChange.PutItem -> old.copy(items = old.items.filterNot { it.id == change.item.id } + change.item)
            is HarnessChange.RemoveItem -> old.copy(items = old.items.filterNot { it.id == change.item })
            is HarnessChange.Tools -> old.copy(tools = change.tools)
        }
        revised(intent.requestId, changed, intent.at)
    }
}

private fun Harness.changeRejection(change: HarnessChange): HarnessRejection? = when (change) {
    is HarnessChange.PutItem -> when {
        items.any { it.id == change.item.id && it.name != change.item.name } -> HarnessRejection.Invalid
        items.any { it.id != change.item.id && it.name == change.item.name } -> HarnessRejection.Duplicate
        items.none { it.id == change.item.id } && items.size >= HarnessLimits.ITEMS -> HarnessRejection.Limit
        else -> null
    }

    is HarnessChange.RemoveItem -> HarnessRejection.NotFound.takeIf { items.none { item -> item.id == change.item } }

    is HarnessChange.Meta, is HarnessChange.Scope, is HarnessChange.Tools -> null
}

private fun HarnessState.Ready.enable(intent: HarnessIntent.Public.SetEnabled): LibraryTransition {
    mutationRejection(intent.id, intent.expectedRevision)?.let { return reject(intent.requestId, it) }
    val harness = harnesses.first { it.harness.id == intent.id }.harness.copy(isEnabled = intent.isEnabled)
    return revised(intent.requestId, harness, intent.at)
}

private fun HarnessState.Ready.enableItem(intent: HarnessIntent.Public.SetItemEnabled): LibraryTransition {
    mutationRejection(intent.id, intent.expectedRevision)?.let { return reject(intent.requestId, it) }
    val harness = harnesses.first { it.harness.id == intent.id }.harness
    return if (harness.items.none { it.id == intent.item }) {
        reject(intent.requestId, HarnessRejection.NotFound)
    } else {
        val items = harness.items.map { if (it.id == intent.item) it.enabled(intent.isEnabled) else it }
        revised(intent.requestId, harness.copy(items = items), intent.at)
    }
}

private fun HarnessItem.enabled(enabled: Boolean): HarnessItem = when (this) {
    is HarnessItem.Skill -> copy(isEnabled = enabled)
    is HarnessItem.Instruction -> copy(isEnabled = enabled)
    is HarnessItem.Template -> copy(isEnabled = enabled)
    is HarnessItem.Script -> copy(isEnabled = enabled)
    is HarnessItem.Workflow -> copy(isEnabled = enabled)
}

private fun HarnessState.Ready.revised(request: RequestId, harness: Harness, at: Instant): LibraryTransition =
    if (at < harness.updatedAt || harness.revision == Long.MAX_VALUE) {
        reject(request, HarnessRejection.Invalid)
    } else {
        save(request, harness.copy(revision = harness.revision + 1, updatedAt = at), false)
    }

private fun HarnessState.Ready.save(request: RequestId, harness: Harness, isCreated: Boolean): LibraryTransition {
    val reserved = reservedHarnesses().filterNot { it.id == harness.id } + harness
    if (!isHarnessLibraryWithinLimits(reserved) || !hasReservedBytesFor(harness)) {
        return reject(request, HarnessRejection.Limit)
    }
    val receipt = HarnessReceipt(request, harness.id, harness.revision, writeGeneration + 1)
    val mutation = HarnessMutation.Save(receipt, harness, isCreated)
    return LibraryTransition(
        copy(pending = pending + (harness.id to mutation), writeGeneration = receipt.generation),
        listOf(HarnessEffect.Save(harness, receipt)),
    )
}

private fun HarnessState.Ready.remove(intent: HarnessIntent.Public.Delete): LibraryTransition {
    mutationRejection(intent.id, intent.expectedRevision)?.let { return reject(intent.requestId, it) }
    return if (attachmentWrite != null || pending.values.any { it is HarnessMutation.Remove }) {
        reject(intent.requestId, HarnessRejection.Busy)
    } else {
        val harness = harnesses.first { it.harness.id == intent.id }.harness
        val receipt = HarnessReceipt(intent.requestId, intent.id, harness.revision, writeGeneration + 1)
        LibraryTransition(
            copy(
                pending = pending + (intent.id to HarnessMutation.Remove(receipt, harness)),
                writeGeneration = receipt.generation,
            ),
            listOf(HarnessEffect.Remove(harness, receipt)),
        )
    }
}

private fun HarnessState.Ready.mutationRejection(id: HarnessId, expected: Long): HarnessRejection? {
    val current = harnesses.firstOrNull { it.harness.id == id }?.harness
    return when {
        current == null -> HarnessRejection.NotFound
        current.revision != expected -> HarnessRejection.Conflict
        id in pending -> HarnessRejection.Busy
        else -> null
    }
}

private fun HarnessState.Ready.reservedHarnesses(): List<Harness> {
    val saves = pending.values.filterIsInstance<HarnessMutation.Save>().associate { it.harness.id to it.harness }
    return harnesses.map { saves[it.harness.id] ?: it.harness } + saves.values.filter { saved ->
        harnesses.none { it.harness.id == saved.id }
    }
}

internal fun List<HarnessItem>.haveUniqueIdentity(): Boolean =
    map { it.id }.distinct().size == size && map { it.name }.distinct().size == size

private fun HarnessState.Ready.attach(
    request: RequestId,
    id: HarnessId,
    session: SessionRef,
    isAttached: Boolean,
): LibraryTransition {
    if (harnesses.none { it.harness.id == id }) return reject(request, HarnessRejection.NotFound)
    if (attachmentWrite != null || pending.values.any { it is HarnessMutation.Remove }) {
        return reject(request, HarnessRejection.Busy)
    }
    val ids = if (isAttached) attachments[session].orEmpty() + id else attachments[session].orEmpty() - id
    val next = (attachments + (session to ids)).filterValues { it.isNotEmpty() }
    if (!validAttachments(harnesses.map { it.harness }, next)) return reject(request, HarnessRejection.Limit)
    val write = HarnessAttachmentWrite(request, id, session, isAttached, next, writeGeneration + 1)
    return LibraryTransition(
        copy(attachmentWrite = write, writeGeneration = write.generation),
        listOf(HarnessEffect.SaveAttachments(write)),
    )
}

private fun HarnessState.Ready.approve(intent: HarnessIntent.Public.SetApproval): LibraryTransition = when {
    intent.expectedRevision != approvalRevision -> reject(intent.requestId, HarnessRejection.Conflict)

    approvalWrite != null -> reject(intent.requestId, HarnessRejection.Busy)

    else -> {
        val write = HarnessApprovalWrite(intent.requestId, intent.level, approvalRevision + 1, writeGeneration + 1)
        LibraryTransition(
            copy(approvalWrite = write, writeGeneration = write.generation),
            listOf(HarnessEffect.SaveApproval(write)),
        )
    }
}

private fun HarnessState.Ready.reload(intent: HarnessIntent.Public.Reload): LibraryTransition {
    if (reload != null) return reject(intent.requestId, HarnessRejection.Busy)
    val load = HarnessLoad(loadGeneration + 1, revision, !hasWrites())
    return LibraryTransition(copy(reload = load, loadGeneration = load.generation), listOf(HarnessEffect.Load(load)))
}

internal fun HarnessState.Ready.hasWrites(): Boolean =
    pending.isNotEmpty() || attachmentWrite != null || approvalWrite != null

internal fun validAttachments(harnesses: List<Harness>, attachments: Map<SessionRef, Set<HarnessId>>): Boolean {
    val ids = harnesses.map { it.id }.toSet()
    return attachments.values.all { attached -> attached.all { it in ids } } && ids.all { id ->
        attachments.values.count { id in it } <= HarnessLimits.ATTACHED_PER_HARNESS
    }
}

private fun HarnessState.Ready.hasReservedBytesFor(candidate: Harness): Boolean {
    val committed = harnesses.associate { it.harness.id to it.harness.storageBytes().toLong() }
    val proposed = pending.values.filterIsInstance<HarnessMutation.Save>()
        .associate { it.harness.id to it.harness.storageBytes().toLong() } +
        (candidate.id to candidate.storageBytes().toLong())
    return (committed.keys + proposed.keys).sumOf { id ->
        maxOf(committed[id] ?: 0L, proposed[id] ?: 0L)
    } <= HarnessLimits.BYTES_PER_PROFILE
}
