package io.aequicor.heartbeat.feature.harness.impl.data.script

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.github.GitHubEntity
import io.aequicor.heartbeat.feature.harness.api.github.GitHubNodeId
import io.aequicor.heartbeat.feature.harness.api.github.GitHubQuery
import io.aequicor.heartbeat.feature.harness.api.github.GitHubReadResult
import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository
import io.aequicor.heartbeat.feature.harness.api.github.GitHubSnapshot
import io.aequicor.heartbeat.feature.harness.api.github.ScriptGitHub
import io.aequicor.heartbeat.feature.harness.api.github.UnavailableScriptGitHub
import io.aequicor.heartbeat.feature.harness.api.script.HarnessScriptScope
import io.aequicor.heartbeat.feature.harness.api.script.ScriptAgent
import io.aequicor.heartbeat.feature.harness.api.script.ScriptCommands
import io.aequicor.heartbeat.feature.harness.api.script.ScriptData
import io.aequicor.heartbeat.feature.harness.api.script.ScriptDataSnapshot
import io.aequicor.heartbeat.feature.harness.api.script.ScriptDataWriteResult
import io.aequicor.heartbeat.feature.harness.api.script.ScriptEvents
import io.aequicor.heartbeat.feature.harness.api.script.ScriptHelper
import io.aequicor.heartbeat.feature.harness.api.script.ScriptHistory
import io.aequicor.heartbeat.feature.harness.api.script.ScriptHooks
import io.aequicor.heartbeat.feature.harness.api.script.ScriptProcesses
import io.aequicor.heartbeat.feature.harness.api.script.ScriptPrompts
import io.aequicor.heartbeat.feature.harness.api.script.ScriptRegistration
import io.aequicor.heartbeat.feature.harness.api.script.ScriptScheduler
import io.aequicor.heartbeat.feature.harness.api.script.ScriptSession
import io.aequicor.heartbeat.feature.harness.api.script.ScriptSessions
import io.aequicor.heartbeat.feature.harness.api.script.ScriptToolCall
import io.aequicor.heartbeat.feature.harness.api.script.ScriptToolResult
import io.aequicor.heartbeat.feature.harness.api.script.ScriptUi
import io.aequicor.heartbeat.feature.harness.api.script.ScriptUiCall
import io.aequicor.heartbeat.feature.harness.api.script.ScriptUiResult
import io.aequicor.heartbeat.feature.harness.api.script.ScriptWorkflows
import io.aequicor.heartbeat.feature.harness.api.workflow.AdaptiveAgent
import io.aequicor.heartbeat.feature.harness.impl.domain.config.HarnessConfigCodec
import io.aequicor.heartbeat.feature.harness.impl.domain.config.HarnessDecoded
import io.aequicor.heartbeat.feature.harness.impl.domain.content.renderHarnessTemplate
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationContext
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationResult
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigFile
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigPath
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigSectionId
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnit
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnitRef
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Runs the repository's actual script, rather than a second implementation of its board logic. */
class LifecycleBoardTest {
    @Test
    fun `board compiles and synchronizes pages closures artifacts and repository changes`() = runTest {
        val source = Files.readString(lifecycleRoot().resolve(".heartbeat/harnesses/product_lifecycle/board.kts"))
        val folder = lifecycleRoot().resolve(".heartbeat/harnesses/product_lifecycle")
        val files = Files.list(folder).use { entries ->
            entries.map {
                val path = ConfigPath.of("harnesses", "product_lifecycle", it.fileName.toString())
                ConfigFile(path, Files.readString(it))
            }.toList()
        }
        val unit = ConfigUnit(ConfigUnitRef(ConfigSectionId.Harnesses, "product_lifecycle"), files)
        val decoded = HarnessConfigCodec.decode(unit)
        assertIs<HarnessDecoded.Valid>(decoded)
        assertEquals(15, decoded.content.items.size)
        val directory = Files.createTempDirectory("lifecycle-board-test")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        }
        val host = JvmHarnessScriptHost(directory, "test", backgroundScope, dispatchers)
        try {
            val request =
                HarnessCompilationRequest(HarnessId("lifecycle"), ItemId("board"), HarnessCodeKind.Script, source)
            val result = host.compile(request)
            val success = assertIs<HarnessCompilationResult.Success>(result, result.toString())
            try {
                val templates = decoded.content.items.filterIsInstance<HarnessItem.Template>()
                val board = LifecycleBoardScope(backgroundScope, templates)
                val evaluated = host.evaluate(success.code, HarnessEvaluationContext.Script(board))
                assertIs<HarnessEvaluationResult.Success>(evaluated, "Registered: ${board.methods.keys}")
                verifyPagesAndClosures(board)
                verifyCancellationAndConcurrentEdit(board)
                verifyRepositorySwitchAndDiscovery(board)
                verifyPromptsAndTriage(board)
                verifyFullBoardQuota(board)
                verifyProcessedQueueRotation(board)
                verifyCompletedFeatureRemoval(board)
            } finally {
                success.code.close()
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

private suspend fun verifyPagesAndClosures(board: LifecycleBoardScope) {
    assertEquals(15, board.methods.size)
    assertEquals(JsonPrimitive(0), board.value("snapshot")["features_count"])
    board.value("add_feature", "title" to JsonPrimitive("Tracked"), "epic" to JsonPrimitive(1))
    board.read = { query ->
        when (query) {
            is GitHubQuery.Issues ->
                if (query.page == 1) {
                    GitHubSnapshot(listOf(issue(2, "<!-- type:defect -->")), nextPage = 2)
                } else {
                    GitHubSnapshot(listOf(issue(1, "<!-- heartbeat:lifecycle v1 -->\nСтадия: testing")))
                }

            is GitHubQuery.SubIssues -> GitHubSnapshot(listOf(issue(3, "Artifact")))

            else -> error("Unexpected read")
        }
    }
    val synced = board.value("sync_from_github")
    val feature = synced.getValue("features").jsonArray.single().jsonObject
    assertEquals(JsonPrimitive("testing"), feature["stage"])
    assertEquals(JsonPrimitive(1), feature["artifact_count"])
    assertEquals(JsonPrimitive(1), synced["feedback_open"])
    board.read = { query ->
        when (query) {
            is GitHubQuery.Issues -> GitHubSnapshot(listOf(issue(2, "", "closed")))
            is GitHubQuery.Issue -> GitHubSnapshot(listOf(issue(1, "Стадия: acceptance")))
            else -> GitHubSnapshot()
        }
    }
    val closed = board.value("sync_from_github")
    assertEquals(JsonPrimitive(0), closed["feedback_open"])
    assertEquals(JsonPrimitive("acceptance"), onlyFeature(closed)["stage"])
}

private suspend fun verifyCancellationAndConcurrentEdit(board: LifecycleBoardScope) {
    board.read = { throw CancellationException("cancelled") }
    val before = board.snapshot
    assertFailsWith<CancellationException> { board.call("sync_from_github") }
    assertEquals(before, board.snapshot)
    board.read = { query ->
        when (query) {
            is GitHubQuery.Issues -> GitHubSnapshot(nextPage = query.page + 1)
            is GitHubQuery.Issue -> GitHubSnapshot(listOf(issue(1, "Стадия: acceptance")))
            else -> GitHubSnapshot()
        }
    }
    assertTrue(board.value("sync_from_github").getValue("sync_note").toString().contains("Неполная"))
    board.read = { query ->
        if (query is GitHubQuery.Issues) {
            board.value("set_stage", "epic" to JsonPrimitive(1), "stage" to JsonPrimitive("released"))
            GitHubSnapshot(listOf(issue(1, "Стадия: requirements")))
        } else {
            GitHubSnapshot()
        }
    }
    assertIs<ScriptUiResult.Error>(board.call("sync_from_github"))
    assertEquals(JsonPrimitive("released"), onlyFeature(board.value("snapshot"))["stage"])
}

private suspend fun verifyRepositorySwitchAndDiscovery(board: LifecycleBoardScope) {
    // Switching repositories must not reinterpret the same issue numbers as new remote entities.
    val switched = board.value(
        "set_repo",
        "owner" to JsonPrimitive("other"),
        "name" to JsonPrimitive("repo"),
    )
    assertEquals(JsonPrimitive(0), switched["features_count"])
    assertEquals(JsonPrimitive(0), switched["feedback_open"])
    val invalidRepo = board.call("set_repo", "owner" to JsonPrimitive(".."), "name" to JsonPrimitive("repo"))
    assertIs<ScriptUiResult.Error>(invalidRepo)
    board.read = { query ->
        when (query) {
            is GitHubQuery.Issues -> GitHubSnapshot(
                listOf(issue(10, "<!-- heartbeat:lifecycle v1 -->"), issue(11, "Artifact")),
            )

            is GitHubQuery.SubIssues ->
                if (query.page == 1) {
                    GitHubSnapshot(listOf(issue(11, "Artifact")), nextPage = 2)
                } else {
                    GitHubSnapshot(listOf(issue(12, "Artifact")))
                }

            else -> error("Unexpected query")
        }
    }
    val discovered = board.value("sync_from_github")
    assertEquals(JsonPrimitive(2), onlyFeature(discovered)["artifact_count"])
    assertEquals(JsonPrimitive(0), discovered["feedback_open"])
}

private suspend fun verifyPromptsAndTriage(board: LifecycleBoardScope) {
    assertIs<ScriptUiResult.Error>(board.call("kickoff_feature"))
    board.session = SessionRef(EngineId("test"), SessionSourceId("test"), "session")
    board.value("set_current", "epic" to JsonPrimitive(10))
    board.value("advance_stage")
    board.value("kickoff_feature")
    board.value("set_stage", "epic" to JsonPrimitive(10), "stage" to JsonPrimitive("acceptance"))
    board.value("link_pr", "epic" to JsonPrimitive(10), "pr" to JsonPrimitive(99))
    board.value("request_acceptance")
    board.value("prepare_release")
    board.read = { query ->
        if (query is GitHubQuery.Issues) {
            GitHubSnapshot(listOf(issue(10, "Стадия: acceptance"), issue(21, ""), issue(20, "")))
        } else {
            GitHubSnapshot()
        }
    }
    board.value("sync_from_github")
    board.value("triage_feedback")
    assertTrue(board.sent.last().contains("issue #20"))
    board.value("mark_feedback", "number" to JsonPrimitive(20))
    board.value("triage_feedback")
    assertTrue(board.sent.last().contains("issue #21"))
    board.value("mark_feedback", "number" to JsonPrimitive(21))
    assertIs<ScriptUiResult.Error>(board.call("triage_feedback"))
    assertEquals(JsonPrimitive(0), board.value("snapshot")["feedback_pending"])
    assertEquals(6, board.sent.size)
    board.sent.forEach { text ->
        assertTrue(text.contains("Репозиторий шага: `other/repo`"))
        assertTrue(text.contains("--repo other/repo"))
        assertTrue("{{" !in text)
    }
    board.sendFailure = CancellationException("cancelled")
    assertFailsWith<CancellationException> { board.call("prepare_release") }
    board.sendFailure = IllegalStateException("failed")
    assertFailsWith<IllegalStateException> { board.call("prepare_release") }
    val before = board.snapshot
    board.conflictNextWrite = true
    val conflicted = board.call("set_stage", "epic" to JsonPrimitive(10), "stage" to JsonPrimitive("released"))
    assertIs<ScriptUiResult.Error>(conflicted)
    assertEquals(before, board.snapshot)
}

private suspend fun verifyFullBoardQuota(board: LifecycleBoardScope) {
    board.value("set_repo", "owner" to JsonPrimitive("quota"), "name" to JsonPrimitive("test"))
    board.read = { query -> fullBoardPage(query) }
    val view = board.value("sync_from_github")
    assertEquals(JsonPrimitive(12), view["features_count"])
    assertEquals(JsonPrimitive(32), view["feedback_pending"])
    view.getValue("features").jsonArray.forEach { feature ->
        assertEquals(JsonPrimitive(200), feature.jsonObject["artifact_count"])
    }
    // Leave ample space for namespace metadata rather than merely fitting the raw board value.
    assertTrue(board.snapshot.value.toString().encodeToByteArray().size < HarnessLimits.DATA_BYTES / 2)
    assertTrue(view.toString().encodeToByteArray().size < HarnessLimits.UI_RESULT_BYTES)
}

private suspend fun verifyProcessedQueueRotation(board: LifecycleBoardScope) {
    for (number in 400..431) board.value("mark_feedback", "number" to JsonPrimitive(number))
    board.read = { query -> fullBoardPage(query, hasNewFeedback = true) }
    repeat(2) {
        val view = board.value("sync_from_github")
        assertEquals(JsonPrimitive(1), view["feedback_pending"])
        assertEquals(JsonPrimitive(32), view["feedback_open"])
        assertEquals(JsonPrimitive(999), view.getValue("feedback").jsonArray.first().jsonObject["number"])
        val stored = board.snapshot.value!!.jsonObject.getValue("feedback").jsonArray
        assertTrue(stored.any { row -> row.jsonObject["number"] == JsonPrimitive(999) })
    }
    // Rotating a processed row must not turn it back into pending work on the next refresh.
    board.value("mark_feedback", "number" to JsonPrimitive(999))
    assertEquals(JsonPrimitive(0), board.value("sync_from_github")["feedback_pending"])
}

private fun fullBoardPage(query: GitHubQuery, hasNewFeedback: Boolean = false): GitHubSnapshot = when (query) {
    is GitHubQuery.Issues -> {
        val epics = (1L..12).map { number -> issue(number, "<!-- heartbeat:lifecycle v1 -->") }
        val feedback = (400L..431).map { number -> issue(number, "<!-- type:feedback -->") }
        val newFeedback = if (hasNewFeedback) listOf(issue(999, "<!-- type:feedback -->")) else emptyList()
        GitHubSnapshot((epics + feedback + newFeedback).map { it.copy(title = "Ж".repeat(256)) })
    }

    is GitHubQuery.SubIssues -> {
        val first = 1_000_000 + query.number * 1000 + query.page * 50
        val artifacts = (first until first + 50).map { issue(it, "").copy(title = "Ж".repeat(256)) }
        GitHubSnapshot(artifacts, nextPage = (query.page + 1).takeIf { it <= 4 })
    }

    else -> error("Unexpected full board query")
}

private suspend fun verifyCompletedFeatureRemoval(board: LifecycleBoardScope) {
    board.value("set_repo", "owner" to JsonPrimitive("completed"), "name" to JsonPrimitive("test"))
    var completed = false
    board.read = { query ->
        if (query is GitHubQuery.Issues) {
            val closed = (1L..12).map { issue(it, "<!-- heartbeat:lifecycle v1 -->", "closed") }
            val state = if (completed) "released" else "requirements"
            val active = issue(13, "<!-- heartbeat:lifecycle v1 -->\nСтадия: $state")
            GitHubSnapshot(closed + active)
        } else {
            GitHubSnapshot()
        }
    }
    assertEquals(JsonPrimitive(1), board.value("sync_from_github")["features_count"])
    completed = true
    board.value("sync_from_github")
    board.value("remove_feature", "epic" to JsonPrimitive(13))
    assertEquals(JsonPrimitive(0), board.value("sync_from_github")["features_count"])
}

private class LifecycleBoardScope(override val scope: CoroutineScope, val templates: List<HarnessItem.Template>) :
    HarnessScriptScope {
    override val harness = HarnessId("lifecycle")
    override val name = HarnessName("product_lifecycle")
    override val item = ItemId("board")
    override val revision = 1L
    val methods = mutableMapOf<String, suspend (ScriptUiCall) -> ScriptUiResult>()
    var snapshot = ScriptDataSnapshot(null, 0)
    var session: SessionRef? = null
    val sent = mutableListOf<String>()
    var sendFailure: Exception? = null
    var conflictNextWrite = false
    var read: suspend (GitHubQuery) -> GitHubSnapshot = { GitHubSnapshot() }
    override val github = object : ScriptGitHub by UnavailableScriptGitHub {
        override suspend fun read(repository: GitHubRepository, query: GitHubQuery) = GitHubReadResult.Read(read(query))
    }
    override val data = object : ScriptData {
        override suspend fun read(key: String) = snapshot
        override suspend fun write(key: String, value: JsonElement?, expectedRevision: Long): ScriptDataWriteResult {
            if (conflictNextWrite) {
                conflictNextWrite = false
                return ScriptDataWriteResult.Conflict(snapshot)
            }
            if (snapshot.revision != expectedRevision) return ScriptDataWriteResult.Conflict(snapshot)
            require(value.toString().encodeToByteArray().size <= HarnessLimits.DATA_BYTES) { "Namespace quota" }
            if (value != snapshot.value) snapshot = ScriptDataSnapshot(value, snapshot.revision + 1)
            return ScriptDataWriteResult.Written(snapshot)
        }
        override suspend fun update(key: String, transform: (JsonElement?) -> JsonElement?): ScriptDataSnapshot {
            val next = transform(snapshot.value)
            write(key, next, snapshot.revision)
            return snapshot
        }
    }
    override val ui = object : ScriptUi {
        override fun method(
            name: ItemName,
            description: String,
            handler: suspend (ScriptUiCall) -> ScriptUiResult,
        ): ScriptRegistration {
            assertTrue(methods.put(name.value, handler) == null)
            return ScriptRegistration {}
        }
    }
    override val agent = object : ScriptAgent {
        override fun tool(
            name: ItemName,
            description: String,
            schema: JsonObject,
            action: AgentToolAction,
            handler: suspend (ScriptToolCall) -> ScriptToolResult,
        ) = ScriptRegistration {}

        override fun instructions(handler: suspend (AgentToolScope) -> String): ScriptRegistration =
            error("Unexpected instructions")
    }
    override val events: ScriptEvents get() = error("Unexpected service")
    override val hooks: ScriptHooks get() = error("Unexpected service")
    override val sessions = object : ScriptSessions {
        override suspend fun list(): List<ScriptSession> = error("Unexpected list")
        override suspend fun history(session: SessionRef, page: HistoryPageRequest): ScriptHistory =
            error("Unexpected history")
        override suspend fun send(session: SessionRef, text: String): WakeId {
            sendFailure?.let { throw it }
            sent += text
            return WakeId("sent")
        }
        override suspend fun spawn(
            parent: SessionRef?,
            title: String,
            prompt: String,
            adaptive: AdaptiveAgent?,
        ): ScriptHelper = error("Unexpected spawn")
    }
    override val scheduler: ScriptScheduler get() = error("Unexpected service")
    override val prompts = object : ScriptPrompts {
        override suspend fun render(name: ItemName, args: Map<String, String>): String =
            renderHarnessTemplate(templates.single { it.name == name }, args)
    }
    override val workflows: ScriptWorkflows get() = error("Unexpected service")
    override val processes: ScriptProcesses get() = error("Unexpected service")
    override val commands: ScriptCommands get() = error("Unexpected service")

    suspend fun call(method: String, vararg args: Pair<String, JsonElement>): ScriptUiResult =
        methods.getValue(method)(ScriptUiCall(JsonObject(args.toMap()), session = session))

    suspend fun value(method: String, vararg args: Pair<String, JsonElement>): JsonObject =
        assertIs<ScriptUiResult.Value>(call(method, *args)).value.jsonObject
}

private fun issue(number: Long, body: String, state: String = "open") = GitHubEntity.Issue(
    id = number.toString(),
    updatedAt = Instant.fromEpochMilliseconds(0),
    number = number,
    node = GitHubNodeId("I_$number"),
    title = "Issue $number",
    body = body,
    state = state,
)

private fun lifecycleRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
    .first { Files.isDirectory(it.resolve(".heartbeat")) }

private fun onlyFeature(view: JsonObject): JsonObject = view.getValue("features").jsonArray.single().jsonObject
