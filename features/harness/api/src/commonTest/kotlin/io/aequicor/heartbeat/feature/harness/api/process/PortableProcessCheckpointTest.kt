package io.aequicor.heartbeat.feature.harness.api.process

import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.github.GitHubNodeId
import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository
import io.aequicor.heartbeat.feature.harness.api.github.GitHubWriteRefusal
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortableProcessCheckpointTest {
    private val repository = GitHubRepository("aequicor", "Heartbeat")
    private val repositoryNode = GitHubNodeId("R_repository")
    private val taskNode = GitHubNodeId("I_task")
    private val identity = GitHubTaskIdentity(repository, GitHubTaskKind.Issue, 475, repositoryNode, taskNode)
    private val sourceResult = ResultRef("private_result", "SECRET-result-payload")
    private val target = ResultRef("private_target", "SECRET-local-path")
    private val sealedResult = PortableEvidence(
        repository,
        repositoryNode,
        PortableGitHubKind.IssueComment,
        101,
        "a".repeat(64),
    )
    private val sealedTarget = PortableEvidence(
        repository,
        repositoryNode,
        PortableGitHubKind.Pull,
        475,
        "b".repeat(64),
    )
    private val definitionDigest = "e".repeat(64)
    private val approved = ProcessDefinition(ItemId("approved_recipient"), 7)

    @Test
    fun `export excludes native references approvals and private payloads`() {
        val record = completedAttempt().copy(grants = ProcessAuthority.entries.toSet())
        val checkpoint = ready(record)
        val encoded = Json.encodeToString(checkpoint)
        assertFalse(encoded.contains("SECRET"))
        assertFalse(encoded.contains("grants"))
        assertFalse(encoded.contains("workflow"))
        assertTrue(encoded.encodeToByteArray().size <= PORTABLE_PROCESS_CHECKPOINT_BYTES)
        val imported = importCheckpoint(checkpoint)
        assertTrue(imported.record.grants.isEmpty())
        assertEquals(approved, imported.record.definition)
        val attempt = imported.record.history.single().attempt
        assertEquals(ExecutorRef(ExecutorKind.External, "handover_1"), attempt.executor)
        assertNull(attempt.workflow)
        assertEquals(AttemptOutcome.Succeeded(sealedResult.asResultRef()), imported.record.history.single().outcome)
        assertEquals(listOf(sealedResult), imported.evidence)
    }

    @Test
    fun `restore preserves lifetime and every monotonic counter without starting work`() {
        val source = completedAttempt().copy(
            nextWait = 9,
            updatedAt = processAt(4),
            writes = listOf(
                ExternalWrite(
                    WriteKey("merge"),
                    ProcessAuthority.Merge,
                    target,
                    WritePhase.NotApplied,
                    29,
                    processAt(4),
                ),
            ),
        )
        val checkpoint = ready(source)
        val imported = importCheckpoint(checkpoint).record
        assertEquals(source.id, imported.id)
        assertEquals(source.incarnation, imported.incarnation)
        assertEquals(source.createdAt, imported.createdAt)
        assertEquals(source.updatedAt, imported.updatedAt)
        assertEquals(source.inputRevision, imported.inputRevision)
        assertEquals(source.subject, imported.subject)
        assertEquals(source.nextAttempt, imported.nextAttempt)
        assertEquals(source.nextWait, imported.nextWait)
        assertEquals(source.nextWriteTry, imported.nextWriteTry)
        assertEquals(source.writes.single().key, imported.writes.single().key)
        assertIs<WritePhase.NotApplied>(imported.writes.single().phase)
        assertIs<ProcessPhase.Idle>(imported.phase)
        assertNull(imported.inFlight)
    }

    @Test
    fun `a terminal manifest stays terminal and serializes without raw records`() {
        val source = completedAttempt().change(ProcessCommand.Close(ProcessEnd.Completed), 5)
        val checkpoint = ready(source)
        val decoded = Json.decodeFromString<PortableProcessCheckpoint>(Json.encodeToString(checkpoint))
        assertEquals(checkpoint, decoded)
        assertEquals(source.phase, importCheckpoint(decoded).record.phase)
    }

    @Test
    fun `live attempts unresolved writes and scheduler waits refuse export`() {
        val running = newProcess().change(ProcessCommand.Start(planStage, helperExecutor))
        assertRefused(running, PortableProcessRefusal.LiveAttempt)
        val unknown = completedAttempt().copy(
            writes = listOf(
                ExternalWrite(WriteKey("write"), ProcessAuthority.Write, target, WritePhase.Unknown, at = processAt(4)),
            ),
        )
        assertRefused(unknown, PortableProcessRefusal.UnresolvedWrite)
        val waiting = newProcess().change(
            ProcessCommand.Wait(WaitReason.Timer, WakeCondition(deadline = processAt(100))),
        )
        assertRefused(waiting, PortableProcessRefusal.UnsupportedWait)
    }

    @Test
    fun `terminal status never substitutes for native stop receipts`() {
        assertRefused(
            completedAttempt(),
            PortableProcessRefusal.NativeStopUnconfirmed,
            receipts().copy(isNativeStopped = false),
        )
        val record = completedAttempt()
        val closed = record.history.single()
        val native = closed.copy(
            attempt = closed.attempt.copy(
                executor = ExecutorRef(ExecutorKind.Workflow, "wf_private"),
                workflow = ProcessWorkflow(RunId("wf_private"), ItemId("private_script"), "c".repeat(64)),
            ),
        )
        assertRefused(record.copy(history = listOf(native)), PortableProcessRefusal.NativeStopUnconfirmed)
    }

    @Test
    fun `every successful result and settled write reference requires a host seal`() {
        assertRefused(
            completedAttempt(),
            PortableProcessRefusal.MissingEvidence,
            receipts().copy(evidence = emptyMap()),
        )
        val source = completedAttempt().copy(
            updatedAt = processAt(4),
            writes = listOf(
                ExternalWrite(
                    WriteKey("merge"),
                    ProcessAuthority.Merge,
                    target,
                    WritePhase.Confirmed(sourceResult),
                    29,
                    processAt(4),
                ),
            ),
        )
        val imported = importCheckpoint(ready(source))
        assertEquals(WritePhase.Confirmed(sealedResult.asResultRef()), imported.record.writes.single().phase)
        assertEquals(sealedTarget.asResultRef(), imported.record.writes.single().target)
    }

    @Test
    fun `registry keys older than the write window remain permanent tombstones`() {
        val tombstone = PortableGitHubTombstone(
            WriteKey("older_than_write_window"),
            0,
            "c".repeat(64),
            "d".repeat(32),
            result = sealedResult,
        )
        val receipts = receipts().copy(registryEntries = 1, github = listOf(tombstone))
        val checkpoint = ready(completedAttempt(), receipts)
        assertTrue(checkpoint.writes.isEmpty())
        assertEquals(listOf(tombstone), importCheckpoint(checkpoint).github)
        assertRefused(
            completedAttempt(),
            PortableProcessRefusal.IncompleteRegistry,
            receipts.copy(github = emptyList()),
        )
    }

    @Test
    fun `unknown registry outcomes cannot be represented as settled tombstones`() {
        assertFailsWith<IllegalArgumentException> {
            PortableGitHubTombstone(WriteKey("unknown"), 0, "c".repeat(64), "d".repeat(32))
        }
        assertFailsWith<IllegalArgumentException> {
            PortableGitHubTombstone(
                WriteKey("contradictory"),
                0,
                "c".repeat(64),
                "d".repeat(32),
                result = sealedResult,
                refusal = GitHubWriteRefusal.NotAuthorized,
            )
        }
    }

    @Test
    fun `canonical task identities ignore repository case and reject another task`() {
        val checkpoint = ready(completedAttempt())
        val uppercase = identity.copy(repository = GitHubRepository("AEQUICOR", "HEARTBEAT"))
        assertEquals(identity.canonical, uppercase.canonical)
        val renamed = identity.copy(repository = GitHubRepository("new_owner", "renamed"), number = 500)
        assertEquals(identity.canonical, renamed.canonical)
        assertIs<PortableProcessImport.Ready>(checkpoint.prepareImport(uppercase, approved, definitionDigest, null))
        val refused = checkpoint.prepareImport(
            identity.copy(taskNode = GitHubNodeId("I_other")),
            approved,
            definitionDigest,
            null,
        )
        assertEquals(PortableProcessImport.Refused(PortableProcessRefusal.IdentityMismatch), refused)
    }

    @Test
    fun `import replay is exact and never rewinds an existing process`() {
        val checkpoint = ready(completedAttempt())
        val first = importCheckpoint(checkpoint)
        assertEquals(first, checkpoint.prepareImport(identity, approved, definitionDigest, first.record))
        val newer = first.record.copy(nextAttempt = first.record.nextAttempt + 1)
        assertEquals(
            PortableProcessImport.Refused(PortableProcessRefusal.RecordConflict),
            checkpoint.prepareImport(identity, approved, definitionDigest, newer),
        )
        assertEquals(
            PortableProcessImport.Refused(PortableProcessRefusal.DefinitionMismatch),
            checkpoint.prepareImport(identity, approved, "f".repeat(64), null),
        )
    }

    @Test
    fun `numeric evidence allowlist rejects URLs and malformed identities`() {
        assertFailsWith<IllegalArgumentException> {
            GitHubTaskIdentity(
                repository,
                GitHubTaskKind.Issue,
                0,
                repositoryNode,
                taskNode,
            )
        }
        assertFailsWith<IllegalArgumentException> { sealedResult.copy(number = -1) }
        assertFailsWith<IllegalArgumentException> { sealedResult.copy(digest = "https://private.example/token") }
        assertFailsWith<IllegalArgumentException> { ready(completedAttempt()).copy(version = 2) }
        assertFailsWith<IllegalArgumentException> { ready(completedAttempt()).copy(nextWriteTry = 3) }
    }

    @Test
    fun `wire decoder rejects unknown fields invalid UTF8 and oversized input before import`() {
        val encoded = Json.encodeToString(ready(completedAttempt()))
        assertEquals(ready(completedAttempt()), decodePortableProcessCheckpoint(encoded.encodeToByteArray()))
        assertFailsWith<IllegalArgumentException> {
            decodePortableProcessCheckpoint(
                (encoded.dropLast(1) + ",\"native_handle\":\"secret\"}").encodeToByteArray(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            decodePortableProcessCheckpoint(ByteArray(PORTABLE_PROCESS_CHECKPOINT_BYTES + 1))
        }
        assertFailsWith<CharacterCodingException> {
            decodePortableProcessCheckpoint(byteArrayOf(0xC3.toByte()))
        }
    }

    @Test
    fun `conflicting sealed references never share an imported evidence digest`() {
        val other = sealedResult.copy(number = 102)
        val tombstone = PortableGitHubTombstone(
            WriteKey("old_key"),
            0,
            "c".repeat(64),
            "d".repeat(32),
            result = other,
        )
        assertRefused(
            completedAttempt(),
            PortableProcessRefusal.MissingEvidence,
            receipts().copy(registryEntries = 1, github = listOf(tombstone)),
        )
    }

    @Test
    fun `wire enums keep explicit stable serial names`() {
        assertEquals("\"issue\"", Json.encodeToString(GitHubTaskKind.Issue))
        assertEquals("\"pull\"", Json.encodeToString(GitHubTaskKind.Pull))
        assertEquals("\"issue_comment\"", Json.encodeToString(PortableGitHubKind.IssueComment))
        assertEquals("\"run\"", Json.encodeToString(PortableGitHubKind.Run))
    }

    @Test
    fun `out of range terminal timestamps refuse export with a typed result`() {
        val source = completedAttempt().copy(phase = ProcessPhase.Ended(ProcessEnd.Completed, processAt(50)))
        assertRefused(source, PortableProcessRefusal.InvalidRecord)
    }

    private fun completedAttempt(): ProcessRecord = newProcess().attempt(
        planStage,
        AttemptOutcome.Succeeded(sourceResult),
        ExecutorRef(ExecutorKind.Helper, "SECRET-native-session"),
    )

    private fun receipts(): PortableProcessReceipts = PortableProcessReceipts(
        definitionDigest,
        true,
        mapOf(sourceResult to sealedResult, target to sealedTarget),
        0,
        emptyList(),
    )

    private fun ready(
        source: ProcessRecord,
        receipts: PortableProcessReceipts = receipts(),
    ): PortableProcessCheckpoint = assertIs<PortableProcessExport.Ready>(
        source.portableCheckpoint(identity, receipts),
    ).checkpoint

    private fun importCheckpoint(checkpoint: PortableProcessCheckpoint): PortableProcessImport.Ready =
        assertIs<PortableProcessImport.Ready>(checkpoint.prepareImport(identity, approved, definitionDigest, null))

    private fun assertRefused(
        source: ProcessRecord,
        reason: PortableProcessRefusal,
        receipts: PortableProcessReceipts = receipts(),
    ) {
        assertEquals(PortableProcessExport.Refused(reason), source.portableCheckpoint(identity, receipts))
    }
}
