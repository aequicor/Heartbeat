package io.aequicor.heartbeat.feature.harness.impl.data.decision

import io.aequicor.heartbeat.feature.harness.api.decision.ProcessDecisionLedger
import io.aequicor.heartbeat.feature.harness.impl.data.services.HarnessDataTestSecrets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtectedProcessDecisionsTest {
    @Test
    fun `new book starts empty and committed revisions survive restart`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val storage = ProtectedProcessDecisions(secrets)
        assertEquals(ProcessDecisionLedger(), storage.load())
        val next = ProcessDecisionLedger(revision = 1)
        storage.save(next, 0)
        assertEquals(next, ProtectedProcessDecisions(secrets).load())
        storage.save(next, 0)
        assertEquals(next, storage.load())
    }

    @Test
    fun `stale concurrent writer cannot overwrite the committed book`() = runTest {
        val storage = ProtectedProcessDecisions(HarnessDataTestSecrets())
        val first = async { storage.save(ProcessDecisionLedger(revision = 1), 0) }
        first.await()
        assertFailsWith<IllegalStateException> { storage.save(ProcessDecisionLedger(revision = 2), 0) }
        assertEquals(1L, storage.load().revision)
    }

    @Test
    fun `unreadable and future books fail closed without overwriting or leaking their contents`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val storage = ProtectedProcessDecisions(secrets)
        for (raw in listOf("private-broken-payload", """{"version":99,"ledger":{"revision":0,"entries":[]}}""")) {
            secrets.values[ProtectedProcessDecisions.KEY] = raw
            val error = assertFailsWith<IllegalStateException> { storage.load() }
            assertEquals("Process decision storage is unavailable", error.message)
            assertNull(error.cause)
            assertFailsWith<IllegalStateException> { storage.save(ProcessDecisionLedger(revision = 1), 0) }
            assertEquals(raw, secrets.values[ProtectedProcessDecisions.KEY])
        }
    }

    @Test
    fun `cancelled storage operation does not turn into an absent book`() = runTest {
        val secrets = HarnessDataTestSecrets()
        secrets.beforeRead = { throw CancellationException("cancelled") }
        assertFailsWith<CancellationException> { ProtectedProcessDecisions(secrets).load() }
        assertTrue(secrets.values.isEmpty())
    }

    @Test
    fun `decision book uses a different namespace than script data`() = runTest {
        val secrets = HarnessDataTestSecrets()
        ProtectedProcessDecisions(secrets).save(ProcessDecisionLedger(revision = 1), 0)
        assertEquals(setOf(ProtectedProcessDecisions.KEY), secrets.values.keys)
        assertTrue(secrets.values.keys.none { it.value.startsWith("harness_data_") })
    }
}
