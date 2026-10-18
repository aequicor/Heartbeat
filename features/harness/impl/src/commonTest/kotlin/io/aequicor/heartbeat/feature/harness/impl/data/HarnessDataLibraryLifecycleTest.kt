package io.aequicor.heartbeat.feature.harness.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.harness.impl.data.services.HarnessDataTestSecrets
import io.aequicor.heartbeat.feature.harness.impl.data.services.protectedHarnessData
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageUncertain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class HarnessDataLibraryLifecycleTest {
    @Test
    fun `source edits and disable retain data but deleting a harness clears it`() = runTest {
        val stores = HarnessTestStores()
        val library = stores.library()
        library.save(storageHarness, storageReceipt)
        stores.scriptData.write(storageHarness.id, "progress", JsonPrimitive(3), 0) {}
        val disabled = storageHarness.copy(isEnabled = false, items = emptyList(), revision = 1)
        val next = storageReceipt.copy(revision = 1, generation = 2)
        library.save(disabled, next)
        stores.library().load()
        assertEquals(JsonPrimitive(3), stores.scriptData.read(storageHarness.id, "progress") {}.value)
        library.remove(disabled, next.copy(requestId = RequestId("delete"), generation = 3))
        assertNull(stores.scriptData.read(storageHarness.id, "progress") {}.value)
    }

    @Test
    fun `load retries data cleanup if deletion committed before storage failure`() = runTest {
        val stores = HarnessTestStores()
        val secrets = HarnessDataTestSecrets()
        val data = protectedHarnessData(secrets)
        val library = KeyValueHarnessLibrary(stores, storageClock, data)
        library.save(storageHarness, storageReceipt)
        data.write(storageHarness.id, "progress", JsonPrimitive(3), 0) {}
        secrets.beforeRemove = { error("storage unavailable") }
        assertFailsWith<HarnessStorageUncertain> {
            library.remove(storageHarness, storageReceipt.copy(requestId = RequestId("delete"), generation = 2))
        }
        secrets.beforeRemove = {}
        assertEquals(emptyList(), KeyValueHarnessLibrary(stores, storageClock, data).load().harnesses)
        assertNull(data.read(storageHarness.id, "progress") {}.value)
    }
}
