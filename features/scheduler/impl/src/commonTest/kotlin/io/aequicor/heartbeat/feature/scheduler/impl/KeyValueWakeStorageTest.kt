package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.feature.scheduler.impl.data.KeyValueWakeStorage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyValueWakeStorageTest {
    private val stores = MemoryStores()
    private val storage = KeyValueWakeStorage(stores)

    @Test
    fun `wakes survive a round trip and an empty schedule clears the key`() = runTest {
        assertTrue(storage.load().isEmpty())
        val wakes = listOf(
            scheduled("w1", deadline = START).copy(fenceNonce = "a1b2c3"),
            scheduled("w2", events = setOf(OTHER_KEY)),
        )
        storage.save(wakes)
        // The secret fence nonce survives a restart; a wake stored before it existed reads back without one.
        assertEquals(wakes, storage.load())
        assertEquals(listOf("a1b2c3", null), storage.load().map { it.fenceNonce })
        storage.save(emptyList())
        assertTrue(stores.store.values.isEmpty())
    }

    @Test
    fun `an unreadable schedule fails without quoting stored notes`() = runTest {
        stores.store.values["wakes"] = """[{"request":{"note":"deploy to prod-db-7"}}]"""
        val failure = assertFailsWith<IllegalStateException> { storage.load() }
        assertFalse("prod-db-7" in failure.toString())
        assertNull(failure.cause)
    }

    private companion object {
        val OTHER_KEY = io.aequicor.heartbeat.feature.scheduler.api.EventKeys.custom("other")
    }
}
