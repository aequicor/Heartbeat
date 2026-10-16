package io.aequicor.heartbeat.feature.harness.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.harness.api.HarnessAttachmentWrite
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageConflict
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageFailure
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class HarnessDefaultSelectionStorageTest {
    @Test
    fun `default selection and clearing survive restart without changing session attachments`() = runTest {
        val stores = HarnessTestStores()
        stores.library().save(storageHarness, storageReceipt)
        val attachments = mapOf(storageSession to setOf(storageHarness.id))
        stores.library().saveAttachments(
            HarnessAttachmentWrite(RequestId("attach"), storageHarness.id, storageSession, true, attachments),
        )
        var previous = storageHarness
        for (isDefault in listOf(true, false)) {
            val next = previous.copy(isDefaultForNewSessions = isDefault, revision = previous.revision + 1)
            stores.library().save(next, storageReceipt.copy(revision = next.revision, generation = next.revision + 1))
            val restored = stores.library().load()
            assertEquals(listOf(next), restored.harnesses)
            assertEquals(attachments, restored.attachments)
            previous = next
        }
    }

    @Test
    fun `legacy records without default selection restore as false`() = runTest {
        val stores = HarnessTestStores()
        val preferred = storageHarness.copy(isDefaultForNewSessions = true)
        stores.library().save(preferred, storageReceipt)
        val key = harnessRecordKey(preferred.id)
        val legacy = Json.parseToJsonElement(checkNotNull(stores.libraryValues.values[key])).jsonObject
        stores.libraryValues.values[key] = JsonObject(legacy - "isDefaultForNewSessions").toString()
        val restored = stores.library().load().harnesses.single()
        assertEquals(storageHarness, restored)
        assertFalse(restored.isDefaultForNewSessions)
    }

    @Test
    fun `failed and stale default writes cannot overwrite the saved preference`() = runTest {
        val stores = HarnessTestStores()
        stores.library().save(storageHarness, storageReceipt)
        val preferred = storageHarness.copy(isDefaultForNewSessions = true, revision = 1)
        val receipt = storageReceipt.copy(revision = 1, generation = 2)
        stores.libraryValues.before = { action, key, value ->
            if (action == "set" && key == "pending" && value.orEmpty().contains("Committed")) error("Write failed")
        }
        assertFailsWith<HarnessStorageFailure> { stores.library().save(preferred, receipt) }
        stores.libraryValues.restart()
        assertEquals(listOf(storageHarness), stores.library().load().harnesses)
        assertNull(stores.libraryValues.values["pending"])
        stores.library().save(preferred, receipt)
        assertFailsWith<HarnessStorageConflict> {
            stores.library().save(preferred.copy(isDefaultForNewSessions = false), receipt)
        }
        assertEquals(listOf(preferred), stores.library().load().harnesses)
    }
}
