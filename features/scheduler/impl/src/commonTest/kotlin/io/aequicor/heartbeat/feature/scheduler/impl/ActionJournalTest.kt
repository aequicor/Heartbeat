package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import io.aequicor.heartbeat.feature.scheduler.impl.data.ActionRecord
import io.aequicor.heartbeat.feature.scheduler.impl.data.KeyValueActionJournal
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ActionJournalTest {
    @Test
    fun `completed results replace running records and survive non-destructive reads`() = runTest {
        val stores = MemoryStores()
        val journal = KeyValueActionJournal(stores)
        val running = ActionRecord(
            ActionId("a1"),
            "command",
            START,
            initiator = RequestInitiator(SESSION, RequestId("source")),
        )
        val completed = running.copy(payload = "private command result")
        journal.add(running)
        journal.add(completed)
        assertEquals(listOf(completed), journal.readAll())
        assertEquals(listOf(completed), KeyValueActionJournal(stores).readAll())
        assertFalse(completed.toString().contains("private command result"))
        journal.remove(running.id)
        assertEquals(emptyList(), journal.readAll())
    }

    @Test
    fun `legacy running records load with no completed payload`() = runTest {
        val stores = MemoryStores()
        stores.store.values["actions"] = """[{"id":{"value":"a1"},"kind":"command","startedAt":"$START"}]"""
        val record = KeyValueActionJournal(stores).readAll().single()
        assertEquals(ActionId("a1"), record.id)
        assertNull(record.payload)
        assertNull(record.initiator)
    }

    @Test
    fun `malformed completion never exposes stored result through a decoder error`() = runTest {
        val stores = MemoryStores()
        stores.store.values["actions"] = "[{private command result}"
        val error = assertFailsWith<IllegalStateException> { KeyValueActionJournal(stores).readAll() }
        assertFalse(error.toString().contains("private command result"))
        assertNull(error.cause)
    }
}
