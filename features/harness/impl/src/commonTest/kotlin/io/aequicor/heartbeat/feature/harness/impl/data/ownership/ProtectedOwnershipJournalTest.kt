package io.aequicor.heartbeat.feature.harness.impl.data.ownership

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.github.GitHubNodeId
import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository
import io.aequicor.heartbeat.feature.harness.api.process.ExecutorHostId
import io.aequicor.heartbeat.feature.harness.api.process.GitHubTaskIdentity
import io.aequicor.heartbeat.feature.harness.api.process.GitHubTaskKind
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipFence
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipKey
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipPermit
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipPermitKind
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessOwnership
import io.aequicor.heartbeat.feature.harness.impl.data.services.HarnessDataTestSecrets
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.OwnershipLocalBinding
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.OwnershipLocalProcess
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.OwnershipNativeIntent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProtectedOwnershipJournalTest {
    private val local = OwnershipLocalProcess(HarnessId("harness"), ProcessId("process"), "lifetime")
    private val identity = GitHubTaskIdentity(
        GitHubRepository("owner", "repo"),
        GitHubTaskKind.Issue,
        1,
        GitHubNodeId("R_repo"),
        GitHubNodeId("I_task"),
    )
    private val key = OwnershipKey(identity.canonical.encodeUtf8().sha256().hex())

    @Test
    fun `host binding and exact native proposal survive restart independently of ScriptData`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val journal = ProtectedOwnershipJournal(secrets)
        val host = journal.host()
        val binding = OwnershipLocalBinding(local, identity, OwnershipFence(key, host, 1))
        assertTrue(journal.bind(null, binding))
        val intent = OwnershipNativeIntent(
            local,
            OwnershipPermit("a".repeat(32), 7, binding.fence, OwnershipPermitKind.Workflow),
        )
        assertTrue(journal.reserve(intent))
        val restarted = ProtectedOwnershipJournal(secrets)
        assertEquals(host, restarted.host())
        assertEquals(binding, restarted.binding(local))
        assertEquals(intent, restarted.intent(local, intent.permit.id))
        assertTrue(restarted.hasBindings(local.harness))
        assertFalse(restarted.hasBindings(HarnessId("other")))
        assertFalse(journal.reserve(intent.copy(permit = intent.permit.copy(sequence = 8))))
        assertTrue(journal.reserve(intent))
    }

    @Test
    fun `fence update uses exact previous binding and preserves old native proposals`() = runTest {
        val journal = ProtectedOwnershipJournal(HarnessDataTestSecrets())
        val first = OwnershipLocalBinding(local, identity, OwnershipFence(key, journal.host(), 1))
        assertTrue(journal.bind(null, first))
        val intent = OwnershipNativeIntent(
            local,
            OwnershipPermit("b".repeat(32), 1, first.fence, OwnershipPermitKind.Command),
        )
        assertTrue(journal.reserve(intent))
        val next = first.copy(fence = first.fence.copy(epoch = 2))
        assertFalse(journal.bind(null, next))
        assertTrue(journal.bind(first, next))
        assertEquals(intent, journal.intent(local, intent.permit.id))
        assertFalse(journal.bind(first, first.copy(fence = first.fence.copy(epoch = 3))))
    }

    @Test
    fun `unreadable journal never resets host or treats a protected process as unowned`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val journal = ProtectedOwnershipJournal(secrets)
        journal.host()
        val storageKey = secrets.values.keys.single()
        secrets.values[storageKey] = "SECRET-invalid-json"
        assertFailsWith<IllegalStateException> { journal.host() }.also {
            assertFalse(it.toString().contains("SECRET"))
        }
        assertFailsWith<IllegalStateException> { journal.binding(local) }
        assertFailsWith<IllegalStateException> { journal.hasBindings(local.harness) }
        assertEquals("SECRET-invalid-json", secrets.values[storageKey])
    }

    @Test
    fun `another profile gets another host and failed writes publish no identity`() = runTest {
        val first = ProtectedOwnershipJournal(HarnessDataTestSecrets()).host()
        assertNotEquals(first, ProtectedOwnershipJournal(HarnessDataTestSecrets()).host())
        val secrets = HarnessDataTestSecrets()
        secrets.beforeWrite = { error("SECRET-backend-failure") }
        assertFailsWith<IllegalStateException> { ProtectedOwnershipJournal(secrets).host() }
        assertTrue(secrets.values.isEmpty())
        secrets.beforeWrite = {}
        assertTrue(ProtectedOwnershipJournal(secrets).host().value.isNotEmpty())
    }

    @Test
    fun `future intent epochs and journal quota fail closed without losing receipts`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val journal = ProtectedOwnershipJournal(secrets)
        val binding = OwnershipLocalBinding(local, identity, OwnershipFence(key, journal.host(), 1))
        assertTrue(journal.bind(null, binding))
        val first = OwnershipNativeIntent(
            local,
            OwnershipPermit("0".repeat(32), 1, binding.fence, OwnershipPermitKind.Workflow),
        )
        repeat(512) { index ->
            assertTrue(
                journal.reserve(first.copy(permit = first.permit.copy(id = index.toString(16).padStart(32, '0')))),
            )
        }
        assertFailsWith<IllegalStateException> {
            journal.reserve(first.copy(permit = first.permit.copy(id = "f".repeat(32))))
        }
        assertEquals(first, journal.intent(local, first.permit.id))
        val storageKey = secrets.values.keys.single()
        secrets.values[storageKey] = checkNotNull(secrets.values[storageKey]).replace("\"epoch\":1", "\"epoch\":2")
            .replaceFirst("\"epoch\":2", "\"epoch\":1")
        assertFailsWith<IllegalStateException> { journal.host() }
    }

    @Test
    fun `a losing provisional enrollment can later claim the winners different lifetime`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val journal = ProtectedOwnershipJournal(secrets)
        val first = OwnershipLocalBinding(local, identity, OwnershipFence(key, journal.host(), 1))
        assertTrue(journal.beginEnrollment(first))
        val intent = OwnershipNativeIntent(
            local,
            OwnershipPermit("c".repeat(32), 1, first.fence, OwnershipPermitKind.Command),
        )
        assertFailsWith<IllegalStateException> { journal.reserve(intent) }
        val restarted = ProtectedOwnershipJournal(secrets)
        assertEquals(first, restarted.pendingEnrollment(key))
        assertTrue(restarted.hasBindings(local.harness))
        assertFalse(restarted.discardEnrollment(first, ProcessOwnership(first.fence)))
        val foreign = ProcessOwnership(first.fence.copy(host = ExecutorHostId("f".repeat(32))))
        assertTrue(restarted.discardEnrollment(first, foreign))
        val imported = first.copy(
            local = local.copy(incarnation = "winning_lifetime"),
            fence = first.fence.copy(epoch = 2),
        )
        assertTrue(restarted.bind(null, imported))
        assertEquals(imported, restarted.binding(imported.local))
    }

    @Test
    fun `a promoted enrollment never loses its binding even before its first native intent`() = runTest {
        val journal = ProtectedOwnershipJournal(HarnessDataTestSecrets())
        val binding = OwnershipLocalBinding(local, identity, OwnershipFence(key, journal.host(), 1))
        assertTrue(journal.beginEnrollment(binding))
        assertTrue(journal.promoteEnrollment(binding))
        assertTrue(journal.promoteEnrollment(binding))
        val foreign = ProcessOwnership(binding.fence.copy(host = ExecutorHostId("f".repeat(32))))
        assertFalse(journal.discardEnrollment(binding, foreign))
        val intent = OwnershipNativeIntent(
            local,
            OwnershipPermit("d".repeat(32), 1, binding.fence, OwnershipPermitKind.Workflow),
        )
        assertTrue(journal.reserve(intent))
        assertEquals(intent, journal.intent(local, intent.permit.id))
    }

    @Test
    fun `foreign hosts and noncanonical task keys cannot enroll`() = runTest {
        val journal = ProtectedOwnershipJournal(HarnessDataTestSecrets())
        journal.host()
        val binding = OwnershipLocalBinding(local, identity, OwnershipFence(key, ExecutorHostId("f".repeat(32)), 1))
        assertFailsWith<IllegalStateException> { journal.bind(null, binding) }
        assertFailsWith<IllegalStateException> {
            journal.bind(null, binding.copy(fence = OwnershipFence(OwnershipKey("e".repeat(64)), journal.host(), 1)))
        }
        assertFalse(journal.hasBindings(local.harness))
    }
}
