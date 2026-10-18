package io.aequicor.heartbeat.feature.harness.impl.data.ownership.github

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.github.GitHubActionContext
import io.aequicor.heartbeat.feature.harness.api.github.GitHubChange
import io.aequicor.heartbeat.feature.harness.api.github.GitHubNodeId
import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository
import io.aequicor.heartbeat.feature.harness.api.github.GitHubWriteRefusal
import io.aequicor.heartbeat.feature.harness.api.github.GitHubWriteResult
import io.aequicor.heartbeat.feature.harness.api.process.AttemptOutcome
import io.aequicor.heartbeat.feature.harness.api.process.ExecutorKind
import io.aequicor.heartbeat.feature.harness.api.process.ExecutorRef
import io.aequicor.heartbeat.feature.harness.api.process.ExternalWrite
import io.aequicor.heartbeat.feature.harness.api.process.GitHubTaskIdentity
import io.aequicor.heartbeat.feature.harness.api.process.GitHubTaskKind
import io.aequicor.heartbeat.feature.harness.api.process.PortableGitHubTombstone
import io.aequicor.heartbeat.feature.harness.api.process.PortableProcessCheckpoint
import io.aequicor.heartbeat.feature.harness.api.process.PortableProcessExport
import io.aequicor.heartbeat.feature.harness.api.process.ProcessAttempt
import io.aequicor.heartbeat.feature.harness.api.process.ProcessAuthority
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessRecord
import io.aequicor.heartbeat.feature.harness.api.process.ProcessWorkflowKey
import io.aequicor.heartbeat.feature.harness.api.process.ResultRef
import io.aequicor.heartbeat.feature.harness.api.process.StageName
import io.aequicor.heartbeat.feature.harness.api.process.WriteKey
import io.aequicor.heartbeat.feature.harness.api.process.WritePhase
import io.aequicor.heartbeat.feature.harness.api.process.portableCheckpoint
import io.aequicor.heartbeat.feature.harness.impl.data.services.CommandOrigins
import io.aequicor.heartbeat.feature.harness.impl.data.services.HarnessDataTestSecrets
import io.aequicor.heartbeat.feature.harness.impl.data.services.ProcessesTestEnv
import io.aequicor.heartbeat.feature.harness.impl.domain.github.FakeGitHubTransport
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubApi
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubHost
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubHostAuthority
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubOperation
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubRegistry
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubReservation
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubResponse
import io.aequicor.heartbeat.feature.harness.impl.domain.github.HarnessScriptGitHub
import io.aequicor.heartbeat.feature.harness.impl.domain.github.ImportedGitHubCheck
import io.aequicor.heartbeat.feature.harness.impl.domain.github.PortableGitHubFailure
import io.aequicor.heartbeat.feature.harness.impl.domain.github.marked
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.runtime.OwnershipLocalProcess
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptProcesses
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class PortableGitHubAdapterTest {
    @Test
    fun `source seals independently found evidence and preserves keys outside record write window`() = runTest {
        val env = PortableGitHubEnv(this)
        env.register()
        val receipts = env.receipts()
        assertEquals(1, receipts.registryEntries)
        assertEquals(env.operation.marker, receipts.github.single().marker)
        assertTrue(checkNotNull(env.operation.result) in receipts.evidence)
        val record = env.record.copy(
            writes = listOf(
                ExternalWrite(
                    env.operation.key,
                    ProcessAuthority.Write,
                    ResultRef("github_operation", env.operation.marker),
                    WritePhase.Confirmed(checkNotNull(env.operation.result)),
                    at = env.record.createdAt,
                ),
            ),
        )
        val manifest = assertIs<PortableProcessExport.Ready>(
            record.portableCheckpoint(env.identity, receipts),
        ).checkpoint
        assertFalse(Json.encodeToString(manifest).contains("PRIVATE body"))
        assertTrue(env.transport.calls.all { it.method == "GET" })
        val afterRetention = assertIs<PortableProcessExport.Ready>(
            env.record.portableCheckpoint(env.identity, receipts),
        ).checkpoint
        assertTrue(afterRetention.writes.isEmpty())
        assertEquals(1, afterRetention.github.size)
    }

    @Test
    fun `imported keys replay through script GitHub without new native sends`() = runTest {
        val source = PortableGitHubEnv(this)
        source.register()
        val target = PortableGitHubEnv(this)
        assertTrue(target.imported.install(target.local, source.manifest()))
        target.processes.data.update(HarnessScriptProcesses.STORAGE_KEY) {
            HarnessScriptProcesses.encodeStored(listOf(target.record))
        }
        val api = GitHubApi(target.transport, target.processes.clock)
        val service = HarnessScriptGitHub(
            target.processes.access,
            CommandOrigins(),
            target.processes.processes,
            target.registry,
            GitHubHost(
                api,
                GitHubHostAuthority { error("Imported replay must not request another approval") },
            ) { true },
            target.processes.processGate,
            target.adapter,
            target.local.harness,
        )
        val result = service.write(
            target.local.process,
            0,
            target.operation.key,
            target.repository,
            target.operation.change,
        )
        assertEquals(GitHubWriteResult.Confirmed(checkNotNull(source.operation.result)), result)
        assertIs<GitHubWriteResult.Confirmed>(service.reconcile(target.local.process, target.operation.key))
        assertTrue(target.registry.snapshot(target.local.process, target.local.incarnation).operations.isEmpty())
        assertTrue(target.transport.calls.all { it.method == "GET" })
    }

    @Test
    fun `target replays original marker through fresh reads despite another native workspace`() = runTest {
        val env = PortableGitHubEnv(this)
        val context = GitHubActionContext(
            WorkspaceRef("source_workspace"),
            ProcessWorkflowKey(env.local.process, env.local.incarnation, 1, StageName("publish")),
        )
        val source = env.operation.copy(context = context)
        env.operation = source
        env.register()
        val digest = source.portablePayloadDigest(env.repositoryNode)
        val candidate = source.copy(
            marker = "b".repeat(32),
            result = null,
            decisionId = "new_native_decision",
            context = context.copy(workspace = WorkspaceRef("target_workspace")),
        )
        assertEquals(digest, candidate.portablePayloadDigest(env.repositoryNode))
        val manifest = env.manifest()
        val target = PortableGitHubEnv(this)
        target.operation = source
        // Source payload and native registry stay on the sender; only tombstones and sealed locators move.
        assertTrue(target.imported.install(target.local, manifest))
        assertTrue(target.registry.snapshot(target.local.process, target.local.incarnation).operations.isEmpty())
        val result = target.adapter.checkImported(target.local, candidate)
        assertEquals(ImportedGitHubCheck.Confirmed(checkNotNull(source.result), source.marker), result)
        assertTrue(target.transport.calls.all { it.method == "GET" })
        assertTrue(target.adapter.reconcileImported(target.local))
    }

    @Test
    fun `missing changed or ambiguous external evidence never authorizes another send`() = runTest {
        val env = PortableGitHubEnv(this)
        env.register()
        assertTrue(env.imported.install(env.local, env.manifest()))
        val candidate = env.operation.copy(result = null, marker = "b".repeat(32))
        env.commentBody = "edited by another actor"
        assertEquals(ImportedGitHubCheck.Unknown, env.adapter.checkImported(env.local, candidate))
        assertFalse(env.adapter.reconcileImported(env.local))
        env.commentBody = "PRIVATE body".marked(env.operation.marker)
        env.duplicate = true
        assertEquals(ImportedGitHubCheck.Unknown, env.adapter.checkImported(env.local, candidate))
        assertTrue(env.transport.calls.all { it.method == "GET" })
    }

    @Test
    fun `same key with changed semantic request is a permanent conflict`() = runTest {
        val env = PortableGitHubEnv(this)
        env.register()
        assertTrue(env.imported.install(env.local, env.manifest()))
        val different = env.operation.copy(change = GitHubChange.Comment(1, "another body"), result = null)
        assertEquals(ImportedGitHubCheck.Conflict, env.adapter.checkImported(env.local, different))
        val revised = env.operation.copy(inputRevision = 1, result = null)
        assertEquals(ImportedGitHubCheck.Conflict, env.adapter.checkImported(env.local, revised))
    }

    @Test
    fun `source refuses unresolved operations and registry changes during remote verification`() = runTest {
        val env = PortableGitHubEnv(this)
        env.operation = env.operation.copy(result = null)
        env.register()
        assertIs<PortableGitHubExport.Unavailable>(env.adapter.exportReceipts(env.local, env.record, env.digest, true))
        val changed = PortableGitHubEnv(this)
        changed.register()
        changed.onEvidenceRead = { changed.processes.data.update("unrelated") { JsonPrimitive("changed") } }
        assertEquals(
            PortableGitHubExport.Unavailable(PortableGitHubFailure.RegistryChanged),
            changed.adapter.exportReceipts(changed.local, changed.record, changed.digest, true),
        )
    }

    @Test
    fun `source cannot turn generic script evidence into a portable host proof`() = runTest {
        val env = PortableGitHubEnv(this)
        val attempt = ProcessAttempt(
            1,
            StageName("verify"),
            ExecutorRef(ExecutorKind.Helper, "private_native_session"),
            0,
            startedAt = env.record.createdAt,
        )
        val source = env.record.copy(
            nextAttempt = 2,
            history = listOf(
                io.aequicor.heartbeat.feature.harness.api.process.AttemptRecord(
                    attempt,
                    AttemptOutcome.Succeeded(ResultRef("report", "arbitrary-private-value")),
                    env.record.createdAt,
                ),
            ),
        )
        assertEquals(
            PortableGitHubExport.Unavailable(PortableGitHubFailure.UnsupportedReference),
            env.adapter.exportReceipts(env.local, source, env.digest, true),
        )
    }

    @Test
    fun `protected imported inventory supports a second transfer without original request payloads`() = runTest {
        val env = PortableGitHubEnv(this)
        env.register()
        val manifest = env.manifest()
        val fresh = PortableGitHubEnv(this)
        assertTrue(fresh.imported.install(fresh.local, manifest))
        val receipts = fresh.receipts()
        assertEquals(manifest.github, receipts.github)
        assertEquals(1, receipts.registryEntries)
        assertTrue(fresh.transport.calls.none { it.path.contains("/comments?") })
        assertTrue(fresh.adapter.reconcileImported(fresh.local))
    }

    @Test
    fun `missing import inventory and changed repository node cannot pass recipient reconciliation`() = runTest {
        val env = PortableGitHubEnv(this)
        assertFalse(env.adapter.reconcileImported(env.local))
        env.register()
        assertTrue(env.imported.install(env.local, env.manifest()))
        env.repositoryNode = GitHubNodeId("R_replaced")
        assertFalse(env.adapter.reconcileImported(env.local))
        assertEquals(
            ImportedGitHubCheck.Conflict,
            env.adapter.checkImported(env.local, env.operation.copy(result = null)),
        )
    }

    @Test
    fun `imported refusal keeps its original key without requiring any write or remote evidence`() = runTest {
        val env = PortableGitHubEnv(this)
        env.operation = env.operation.copy(result = null, refusal = GitHubWriteRefusal.NotAuthorized)
        env.register()
        assertTrue(env.imported.install(env.local, env.manifest()))
        val result = env.adapter.checkImported(env.local, env.operation.copy(result = null, refusal = null))
        assertEquals(ImportedGitHubCheck.Refused(GitHubWriteRefusal.NotAuthorized, env.operation.marker), result)
        assertTrue(env.transport.calls.all { it.method == "GET" })
    }

    @Test
    fun `source cancellation verifies native results without requiring portability`() = runTest {
        val env = PortableGitHubEnv(this)
        assertTrue(env.adapter.reconcileNative(env.local))
        env.register()
        assertTrue(env.adapter.reconcileNative(env.local))
        assertFalse(env.adapter.reconcileImported(env.local))
        env.commentBody = "edited after the original operation"
        assertFalse(env.adapter.reconcileNative(env.local))
        val unknown = PortableGitHubEnv(this)
        unknown.operation = unknown.operation.copy(result = null)
        unknown.register()
        assertFalse(unknown.adapter.reconcileNative(unknown.local))
    }

    @Test
    fun `full imported lifetime inventory blocks fresh keys without evicting old tombstones`() = runTest {
        val env = PortableGitHubEnv(this)
        val empty = env.manifest()
        val tombstones = (1..128).map {
            PortableGitHubTombstone(
                WriteKey("old_$it"),
                0,
                "a".repeat(64),
                it.toString().padStart(32, '0'),
                refusal = GitHubWriteRefusal.NotAuthorized,
            )
        }
        assertTrue(env.imported.install(env.local, empty.copy(github = tombstones)))
        assertEquals(ImportedGitHubCheck.Quota, env.adapter.checkImported(env.local, env.operation.copy(result = null)))
        assertEquals(128, env.imported.snapshot(env.local).tombstones.size)
        assertTrue(env.transport.calls.isEmpty())
    }

    @Test
    fun `positive native reconciliation settles an unknown response and returns its original operation`() = runTest {
        val env = PortableGitHubEnv(this)
        env.operation = env.operation.copy(result = null)
        env.register()
        val receipts = env.processes.processGate.withLock { env.adapter.reconcileAndCollectNative(env.local) }
        val receipt = checkNotNull(receipts).single()
        assertEquals(ResultRef("github_comment", "7"), receipt.result)
        assertEquals(env.operation.marker, receipt.operation.marker)
        assertEquals(
            receipt.result,
            env.registry.find(env.local.process, env.local.incarnation, env.operation.key)?.result,
        )
        val replayed = env.processes.processGate.withLock { env.adapter.reconcileAndCollectNative(env.local) }
        assertEquals(receipts, replayed)
        assertTrue(env.transport.calls.all { it.method == "GET" })
    }

    @Test
    fun `missing evidence and failed journal persistence never release an unknown native write`() = runTest {
        val env = PortableGitHubEnv(this)
        env.operation = env.operation.copy(result = null)
        env.register()
        env.commentBody = "marker no longer present"
        assertNull(env.processes.processGate.withLock { env.adapter.reconcileAndCollectNative(env.local) })
        assertNull(env.registry.find(env.local.process, env.local.incarnation, env.operation.key)?.result)
        env.commentBody = "PRIVATE body".marked(env.operation.marker)
        env.secrets.beforeWrite = { error("SECRET-write-failed") }
        assertNull(env.processes.processGate.withLock { env.adapter.reconcileAndCollectNative(env.local) })
        env.secrets.beforeWrite = {}
        assertNull(env.registry.find(env.local.process, env.local.incarnation, env.operation.key)?.result)
        assertTrue(env.transport.calls.all { it.method == "GET" })
    }

    @Test
    fun `key only imported reconciliation verifies the sealed locator and cannot require another send`() = runTest {
        val source = PortableGitHubEnv(this)
        source.register()
        val checkpoint = source.manifest()
        val target = PortableGitHubEnv(this)
        assertTrue(target.imported.install(target.local, checkpoint))
        val proof = checkNotNull(checkpoint.github.single().result)
        assertEquals(
            ImportedGitHubCheck.Confirmed(proof.asResultRef(), source.operation.marker),
            target.adapter.reconcileImportedKey(target.local, source.operation.key),
        )
        target.commentBody = "externally modified evidence"
        assertEquals(
            ImportedGitHubCheck.Unknown,
            target.adapter.reconcileImportedKey(target.local, source.operation.key),
        )
        assertEquals(ImportedGitHubCheck.Absent, target.adapter.reconcileImportedKey(target.local, WriteKey("missing")))
        assertTrue(target.transport.calls.all { it.method == "GET" })
    }

    @Test
    fun `concurrent native reservations atomically share capacity with imported lifetime keys`() = runTest {
        val env = PortableGitHubEnv(this)
        val checkpoint = env.manifest().copy(
            github = (1..127).map {
                PortableGitHubTombstone(
                    WriteKey("old_$it"),
                    0,
                    "a".repeat(64),
                    it.toString().padStart(32, '0'),
                    refusal = GitHubWriteRefusal.NotAuthorized,
                )
            },
        )
        assertTrue(env.imported.install(env.local, checkpoint))
        val first = async {
            env.adapter.reserveNative(env.local, env.operation.copy(key = WriteKey("first"), result = null))
        }
        val second = async {
            env.adapter.reserveNative(env.local, env.operation.copy(key = WriteKey("second"), result = null))
        }
        val results = listOf(first.await(), second.await())
        assertEquals(1, results.count { it is GitHubReservation.Reserved })
        assertEquals(1, results.count { it == GitHubReservation.Full })
        assertEquals(1, env.registry.snapshot(env.local.process, env.local.incarnation).operations.size)
        assertEquals(127, env.imported.snapshot(env.local).tombstones.size)
        assertTrue(env.transport.calls.isEmpty())
    }
}

private class PortableGitHubEnv(scope: TestScope) {
    val secrets = HarnessDataTestSecrets()
    val processes = ProcessesTestEnv(scope, secrets)
    val local = OwnershipLocalProcess(HarnessId("owner"), ProcessId("process"), "lifetime")
    val record = ProcessRecord(local.process, Instant.fromEpochSeconds(1000), incarnation = local.incarnation)
    val repository = GitHubRepository("owner", "repo")
    var repositoryNode = GitHubNodeId("R_repo")
    val identity = GitHubTaskIdentity(repository, GitHubTaskKind.Issue, 1, repositoryNode, GitHubNodeId("I_issue"))
    val digest = "e".repeat(64)
    var operation = GitHubOperation(
        local.process,
        local.incarnation,
        0,
        WriteKey("comment"),
        repository,
        GitHubChange.Comment(1, "PRIVATE body"),
        marker = "a".repeat(32),
        result = ResultRef("github_comment", "7"),
    )
    var commentBody = "PRIVATE body".marked(operation.marker)
    var duplicate = false
    var onEvidenceRead: suspend () -> Unit = {}
    val transport = FakeGitHubTransport { request ->
        val root = "repos/owner/repo"
        when {
            request.path == root -> GitHubResponse(200, buildJsonObject { put("node_id", repositoryNode.value) })

            request.path.startsWith("$root/issues/1/comments?") ->
                GitHubResponse(200, JsonArray(if (duplicate) listOf(comment(), comment()) else listOf(comment())))

            request.path == "$root/issues/comments/7" -> {
                onEvidenceRead()
                GitHubResponse(200, comment())
            }

            else -> GitHubResponse(404)
        }
    }
    val registry = GitHubRegistry(processes.data.processData())
    val imported = ProtectedImportedGitHub(secrets)
    val adapter = PortableGitHubAdapter(GitHubApi(transport, processes.clock), registry, imported)

    suspend fun register() {
        registry.reserve(operation)
    }

    suspend fun receipts() = assertIs<PortableGitHubExport.Ready>(
        adapter.exportReceipts(local, record, digest, true),
    ).receipts

    suspend fun manifest(): PortableProcessCheckpoint = assertIs<PortableProcessExport.Ready>(
        record.portableCheckpoint(identity, receipts()),
    ).checkpoint

    private fun comment(): JsonObject = buildJsonObject {
        put("id", 7)
        put("node_id", "C_comment")
        put("updated_at", "2026-10-18T00:00:00Z")
        put("body", commentBody)
    }
}
