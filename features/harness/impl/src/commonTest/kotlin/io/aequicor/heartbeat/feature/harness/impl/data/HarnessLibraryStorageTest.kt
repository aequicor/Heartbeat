package io.aequicor.heartbeat.feature.harness.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.harness.api.HarnessApproval
import io.aequicor.heartbeat.feature.harness.api.HarnessApprovalWrite
import io.aequicor.heartbeat.feature.harness.api.HarnessAttachmentWrite
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessLibrarySnapshot
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageConflict
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageCorrupt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class HarnessLibraryStorageTest {
    @Test
    fun `committed library attachments approval and deletion survive new repository instances`() = runTest {
        val stores = HarnessTestStores()
        assertEquals(HarnessLibrarySnapshot(), stores.library().load())
        assertEquals(storageReceipt, stores.library().save(storageHarness, storageReceipt))
        val attach = HarnessAttachmentWrite(
            RequestId("attach"),
            storageHarness.id,
            storageSession,
            true,
            mapOf(storageSession to setOf(storageHarness.id)),
            2,
        )
        assertEquals(attach, stores.library().saveAttachments(attach))
        val approval = HarnessApprovalWrite(RequestId("approve"), HarnessApproval.ByTrust, 1, 3)
        assertEquals(approval, stores.library().saveApproval(approval))
        assertEquals(
            HarnessLibrarySnapshot(listOf(storageHarness), attach.attachments, approval.level, 1),
            stores.library().load(),
        )
        assertEquals(storageReceipt, stores.library().save(storageHarness, storageReceipt))
        stores.library().remove(storageHarness, storageReceipt.copy(requestId = RequestId("delete"), generation = 4))
        assertEquals(HarnessLibrarySnapshot(approval = approval.level, approvalRevision = 1), stores.library().load())
        assertNull(stores.libraryValues.values[harnessRecordKey(storageHarness.id)])
        assertNull(stores.libraryValues.values["pending"])
        assertFalse(HarnessLibrarySpec.areValuesLogged)
    }

    @Test
    fun `immutable identity revision author and item names are enforced before writing`() = runTest {
        val stores = HarnessTestStores()
        val library = stores.library()
        library.save(storageHarness, storageReceipt)
        val next = storageHarness.copy(revision = 1)
        val receipt = storageReceipt.copy(revision = 1, generation = 2)
        val invalid = listOf(
            next.copy(name = HarnessName("renamed")),
            next.copy(author = null),
            next.copy(revision = 3),
            next.copy(createdAt = storageNow - kotlin.time.Duration.parse("1s")),
            next.copy(
                items = listOf((storageHarness.items.single() as HarnessItem.Skill).copy(name = ItemName("renamed"))),
            ),
        )
        invalid.forEach { value ->
            assertFailsWith<HarnessStorageConflict> {
                library.save(
                    value,
                    receipt.copy(revision = value.revision),
                )
            }
        }
        assertEquals(listOf(storageHarness), library.load().harnesses)
        assertNull(stores.libraryValues.values["pending"])
    }

    @Test
    fun `malformed raw snapshots and missing indexed records fail closed without leaking text`() = runTest {
        val corruptions = listOf(
            mapOf("index" to "private source code"),
            mapOf("index" to Json.encodeToString(listOf(storageHarness.id))),
            mapOf("approval" to "private source code"),
            mapOf("attachments" to "private source code"),
            mapOf("pending" to "private source code"),
            mapOf("index" to Json.encodeToString(listOf(storageHarness.id, storageHarness.id))),
        )
        corruptions.forEach { raw ->
            val stores = HarnessTestStores()
            stores.libraryValues.values.putAll(raw)
            val failure = assertFailsWith<HarnessStorageCorrupt> { stores.library().load() }
            assertFalse(failure.toString().contains("private source"))
            assertNull(failure.cause)
            assertEquals(raw, stores.libraryValues.values)
        }
    }

    @Test
    fun `utf8 library quotas and attachment limit are checked at the storage boundary`() = runTest {
        val stores = HarnessTestStores()
        val library = stores.library()
        val multibyte = storageHarness.copy(description = "я".repeat(HarnessLimits.BYTES_PER_HARNESS / 2))
        assertFailsWith<HarnessStorageConflict> { library.save(multibyte, storageReceipt) }
        library.save(storageHarness, storageReceipt)
        val overflow = (1..HarnessLimits.ATTACHED_PER_HARNESS + 1).associate {
            storageSession.copy(nativeId = "s$it") to setOf(storageHarness.id)
        }
        assertFailsWith<HarnessStorageConflict> {
            library.saveAttachments(
                HarnessAttachmentWrite(RequestId("attach"), storageHarness.id, storageSession, true, overflow),
            )
        }
        assertEquals(listOf(storageHarness), library.load().harnesses)
    }

    @Test
    fun `profile bytes cannot exceed the total limit through individually valid records`() = runTest {
        val stores = HarnessTestStores()
        val library = stores.library()
        (1..8).forEach { index ->
            val record = storageHarness.copy(
                id = HarnessId("id$index"),
                name = HarnessName("name$index"),
                description = "x".repeat(250 * 1024),
            )
            library.save(record, storageReceipt.copy(id = record.id))
        }
        val ninth = storageHarness.copy(description = "x".repeat(100 * 1024))
        assertFailsWith<HarnessStorageConflict> { library.save(ninth, storageReceipt) }
        assertEquals(8, library.load().harnesses.size)
    }

    @Test
    fun `index names and cross record attachment references are validated`() = runTest {
        val stores = HarnessTestStores()
        val library = stores.library()
        library.save(storageHarness, storageReceipt)
        val other = storageHarness.copy(id = HarnessId("other"))
        assertFailsWith<HarnessStorageConflict> { library.save(other, storageReceipt.copy(id = other.id)) }
        stores.libraryValues.values["attachments"] = mapOf(
            storageSession to setOf(HarnessId("missing")),
        ).encodeAttachments()
        assertFailsWith<HarnessStorageCorrupt> { library.load() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `same repository reads wait for complete multi key commit`() = runTest {
        val stores = HarnessTestStores()
        val library = stores.library()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        stores.libraryValues.after = { action, key, _ ->
            if (action == "set" && key == harnessRecordKey(
                    storageHarness.id,
                )
            ) {
                entered.complete(Unit)
                release.await()
            }
        }
        val write = async { library.save(storageHarness, storageReceipt) }
        entered.await()
        val load = async { library.load() }
        runCurrent()
        assertFalse(load.isCompleted)
        release.complete(Unit)
        write.await()
        assertEquals(listOf(storageHarness), load.await().harnesses)
    }
}
