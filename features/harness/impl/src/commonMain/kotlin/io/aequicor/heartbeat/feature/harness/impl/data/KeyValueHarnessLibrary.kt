package io.aequicor.heartbeat.feature.harness.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessApprovalWrite
import io.aequicor.heartbeat.feature.harness.api.HarnessAttachmentWrite
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessReceipt
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessLibrarySnapshot
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessLibraryStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageConflict
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageCorrupt
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageException
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageUncertain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.time.Clock

internal val HarnessLibrarySpec = KeyValueSpec("harness_library")

/** Profile-owned, serialized library IO. Values remain raw strings so decode failure never looks like absence. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class KeyValueHarnessLibrary(
    @ForScope(ProfileScope::class) stores: DataStores,
    clock: Clock,
) : HarnessLibraryStorage {
    private val store = stores.keyValue(HarnessLibrarySpec)
    private val journal = HarnessKeyValueJournal(store, clock)
    private val mutex = Mutex()
    private val log = Log.tag("HarnessLibrary")

    override suspend fun load(): HarnessLibrarySnapshot = access {
        journal.recover()
        snapshot()
    }

    override suspend fun save(harness: Harness, receipt: HarnessReceipt): HarnessReceipt = access {
        journal.recover()
        val current = snapshot()
        val previous = current.harnesses.firstOrNull { it.id == harness.id }
        validateReceipt(harness, receipt)
        if (previous == harness) return@access receipt
        validateSave(previous, harness)
        val proposed = current.copy(harnesses = current.harnesses.filterNot { it.id == harness.id } + harness)
        if (!proposed.isValid()) throw HarnessStorageConflict()
        val name = harnessRecordKey(harness.id)
        if (previous == null && raw(name) != null) throw HarnessStorageCorrupt()
        commit(
            receipt.operationId("save"),
            mapOf(
                name to Json.encodeToString(harness),
                INDEX to Json.encodeToString(proposed.harnesses.map { it.id }),
            ),
        )
        receipt
    }

    override suspend fun remove(harness: Harness, receipt: HarnessReceipt): HarnessReceipt = access {
        journal.recover()
        validateReceipt(harness, receipt)
        val current = snapshot()
        val previous = current.harnesses.firstOrNull { it.id == harness.id }
        if (previous == null) {
            if (raw(harnessRecordKey(harness.id)) != null) throw HarnessStorageCorrupt()
            return@access receipt
        }
        if (previous != harness) throw HarnessStorageConflict()
        val attachments = current.attachments.mapValues { it.value - harness.id }.filterValues { it.isNotEmpty() }
        commit(
            receipt.operationId("remove"),
            mapOf(
                harnessRecordKey(harness.id) to null,
                INDEX to Json.encodeToString(current.harnesses.filterNot { it.id == harness.id }.map { it.id }),
                ATTACHMENTS to attachments.encodeAttachments(),
            ),
        )
        receipt
    }

    override suspend fun saveAttachments(write: HarnessAttachmentWrite): HarnessAttachmentWrite = access {
        journal.recover()
        if (write.generation < 1) throw HarnessStorageConflict()
        val current = snapshot()
        if (current.harnesses.none { it.id == write.id }) throw HarnessStorageConflict()
        if (!validStoredAttachments(current.harnesses, write.attachments)) throw HarnessStorageConflict()
        if (write.attachments == current.attachments) return@access write
        val ids = if (write.isAttached) {
            current.attachments[write.session].orEmpty() + write.id
        } else {
            current.attachments[write.session].orEmpty() - write.id
        }
        val expected = (current.attachments + (write.session to ids)).filterValues { it.isNotEmpty() }
        if (write.attachments != expected) throw HarnessStorageConflict()
        val operation = harnessOperationId("attachments", write.requestId.value, write.generation.toString())
        commit(operation, mapOf(ATTACHMENTS to write.attachments.encodeAttachments()))
        write
    }

    override suspend fun saveApproval(write: HarnessApprovalWrite): HarnessApprovalWrite = access {
        journal.recover()
        if (write.generation < 1) throw HarnessStorageConflict()
        val current = snapshot()
        if (current.approvalRevision == write.revision && current.approval == write.level) return@access write
        if (current.approvalRevision == Long.MAX_VALUE || current.approvalRevision + 1 != write.revision) {
            throw HarnessStorageConflict()
        }
        val value = HarnessStoredApproval(write.level, write.revision)
        val operation = harnessOperationId(
            "approval",
            write.requestId.value,
            write.revision.toString(),
            write.generation.toString(),
        )
        commit(operation, mapOf(APPROVAL to Json.encodeToString(value)))
        write
    }

    private suspend fun snapshot(): HarnessLibrarySnapshot {
        val ids = raw(INDEX)?.let { decodeHarnessRecord<List<HarnessId>>(it) }.orEmpty()
        if (ids.distinct().size != ids.size) throw HarnessStorageCorrupt()
        val harnesses = ids.map { id ->
            readHarness(id)
        }
        val approval = raw(APPROVAL)?.let { decodeHarnessRecord<HarnessStoredApproval>(it) } ?: HarnessStoredApproval()
        return HarnessLibrarySnapshot(
            harnesses,
            decodeAttachments(raw(ATTACHMENTS)),
            approval.level,
            approval.revision,
        ).also {
            if (!it.isValid()) throw HarnessStorageCorrupt()
        }
    }

    private suspend fun readHarness(id: HarnessId): Harness {
        val text = raw(harnessRecordKey(id)) ?: throw HarnessStorageCorrupt()
        return decodeHarnessRecord<Harness>(text).also { if (it.id != id) throw HarnessStorageCorrupt() }
    }

    private suspend fun commit(operation: String, values: Map<String, String?>) {
        val before = values.keys.associateWith { name -> raw(name)?.let(::HarnessRawValue) }
        journal.commit(operation, before, values.mapValues { (_, text) -> text?.let(::HarnessRawValue) })
    }

    private suspend fun raw(name: String): String? = store.get(stringKey(name))

    private suspend fun <T> access(block: suspend () -> T): T = mutex.withLock {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: HarnessStorageException) {
            throw error
        } catch (error: Exception) {
            log.w(
                HarnessStorageUncertain(),
            ) { "Harness repository access unavailable (${error::class.simpleName.orEmpty()})" }
            throw HarnessStorageUncertain()
        }
    }

    private companion object {
        const val INDEX = "index"
        const val ATTACHMENTS = "attachments"
        const val APPROVAL = "approval"
    }
}

private fun validateReceipt(harness: Harness, receipt: HarnessReceipt) {
    if (receipt.id != harness.id || receipt.revision != harness.revision || receipt.generation < 1) {
        throw HarnessStorageConflict()
    }
}

private fun validateSave(previous: Harness?, proposed: Harness) {
    val isValid = if (previous == null) {
        proposed.revision == 0L
    } else {
        previous.revision < Long.MAX_VALUE && proposed.revision == previous.revision + 1 &&
            proposed.name == previous.name && proposed.createdAt == previous.createdAt &&
            proposed.updatedAt >= previous.updatedAt && proposed.author == previous.author &&
            proposed.items.all { item -> previous.items.none { it.id == item.id && it.name != item.name } }
    }
    if (!isValid) throw HarnessStorageConflict()
}

private fun HarnessReceipt.operationId(action: String): String =
    harnessOperationId(action, requestId.value, id.value, revision.toString(), generation.toString())
