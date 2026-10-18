package io.aequicor.heartbeat.feature.harness.impl.data.ownership.github

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.github.GitHubNodeId
import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository
import io.aequicor.heartbeat.feature.harness.api.github.GitHubWriteRefusal
import io.aequicor.heartbeat.feature.harness.api.process.GitHubTaskIdentity
import io.aequicor.heartbeat.feature.harness.api.process.GitHubTaskKind
import io.aequicor.heartbeat.feature.harness.api.process.PORTABLE_GITHUB_OPERATIONS
import io.aequicor.heartbeat.feature.harness.api.process.PortableGitHubTombstone
import io.aequicor.heartbeat.feature.harness.api.process.PortableProcessCheckpoint
import io.aequicor.heartbeat.feature.harness.api.process.PortableProcessExport
import io.aequicor.heartbeat.feature.harness.api.process.PortableProcessReceipts
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessRecord
import io.aequicor.heartbeat.feature.harness.api.process.WriteKey
import io.aequicor.heartbeat.feature.harness.api.process.portableCheckpoint
import io.aequicor.heartbeat.feature.harness.impl.data.services.HarnessDataTestSecrets
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.OwnershipLocalProcess
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ProtectedImportedGitHubTest {
    private val local = OwnershipLocalProcess(HarnessId("harness"), ProcessId("process"), "lifetime")
    private val identity = GitHubTaskIdentity(
        GitHubRepository("owner", "repo"),
        GitHubTaskKind.Issue,
        1,
        GitHubNodeId("R_repo"),
        GitHubNodeId("I_issue"),
    )

    @Test
    fun `import inventory survives restart outside script namespace and exact replay is idempotent`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val store = ProtectedImportedGitHub(secrets)
        val checkpoint = manifest(listOf(tombstone(1)))
        assertTrue(store.install(local, checkpoint))
        val stored = secrets.values.toMap()
        assertTrue(store.install(local, checkpoint))
        assertEquals(stored, secrets.values)
        val restarted = ProtectedImportedGitHub(secrets)
        assertEquals(checkpoint.github.single(), restarted.lookup(local, WriteKey("key_1")))
        assertEquals(identity.canonical, restarted.snapshot(local).identity)
        assertTrue(restarted.snapshot(local.copy(incarnation = "another_lifetime")).tombstones.isEmpty())
    }

    @Test
    fun `conflicting key identity and foreign process imports preserve the original inventory`() = runTest {
        val store = ProtectedImportedGitHub(HarnessDataTestSecrets())
        val first = manifest(listOf(tombstone(1)))
        assertTrue(store.install(local, first))
        val conflicting = first.copy(github = listOf(first.github.single().copy(marker = "f".repeat(32))))
        assertFalse(store.install(local, conflicting))
        assertFalse(store.install(local.copy(process = ProcessId("foreign")), first))
        assertEquals(first.github, store.snapshot(local).tombstones)
    }

    @Test
    fun `full lifetime inventory never evicts an old key to admit a later import`() = runTest {
        val store = ProtectedImportedGitHub(HarnessDataTestSecrets())
        val original = manifest((1..PORTABLE_GITHUB_OPERATIONS).map { tombstone(it) })
        assertTrue(store.install(local, original))
        assertFailsWith<IllegalStateException> { store.install(local, manifest(listOf(tombstone(129)))) }
        assertEquals(original.github, store.snapshot(local).tombstones)
    }

    @Test
    fun `unreadable storage never becomes an empty imported registry`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val store = ProtectedImportedGitHub(secrets)
        assertTrue(store.install(local, manifest(emptyList())))
        val key = secrets.values.keys.single()
        secrets.values[key] = "SECRET-corrupt-record"
        val error = assertFailsWith<IllegalStateException> { store.snapshot(local) }
        assertFalse(error.toString().contains("SECRET"))
        assertEquals("SECRET-corrupt-record", secrets.values[key])
    }

    @Test
    fun `failed persistence publishes no imported marker`() = runTest {
        val secrets = HarnessDataTestSecrets()
        secrets.beforeWrite = { error("SECRET-storage-failure") }
        val store = ProtectedImportedGitHub(secrets)
        assertFailsWith<IllegalStateException> { store.install(local, manifest(listOf(tombstone(1)))) }
        assertTrue(secrets.values.isEmpty())
    }

    private fun tombstone(index: Int): PortableGitHubTombstone = PortableGitHubTombstone(
        WriteKey("key_$index"),
        0,
        "a".repeat(64),
        index.toString().padStart(32, '0'),
        refusal = GitHubWriteRefusal.NotAuthorized,
    )

    private fun manifest(tombstones: List<PortableGitHubTombstone>): PortableProcessCheckpoint {
        val record = ProcessRecord(local.process, Instant.fromEpochSeconds(1000), incarnation = local.incarnation)
        return (
            record.portableCheckpoint(
                identity,
                PortableProcessReceipts("e".repeat(64), true, emptyMap(), tombstones.size, tombstones),
            ) as PortableProcessExport.Ready
        ).checkpoint
    }
}
