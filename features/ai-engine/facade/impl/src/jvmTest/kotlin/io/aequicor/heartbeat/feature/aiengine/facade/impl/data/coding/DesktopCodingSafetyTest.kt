package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.coding

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReview
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReviewer
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAutomaticAuthorization
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolDecisionMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.CommandSafetyEvidence
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.DefaultAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.DesktopGitPushEvidence
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ToolPolicyResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DesktopCodingSafetyTest {
    private val directory = Files.createTempDirectory("coding-safety").toRealPath()
    private val other = Files.createTempDirectory("coding-safety-other").toRealPath()
    private val dispatcher = Executors.newCachedThreadPool().asCoroutineDispatcher()
    private val dispatchers = object : DispatcherProvider {
        override val main = dispatcher
        override val default = dispatcher
        override val io = dispatcher
    }
    private val workspace = WorkspaceRef("project")
    private var resolved = directory
    private var available = true
    private var prompts = 0
    private var reviews = 0
    private var evidence = ""
    private var policy = ""
    private var verdict = AgentSafetyReview("safe", "Bounded local change")
    private var onReview: suspend () -> Unit = {}
    private var commandSnapshot: JsonObject? = null
    private val projects = object : LocalWorkspaces {
        override val isAvailable get() = available
        override fun observe() = flowOf(listOf(LocalWorkspace(workspace, "Project")))
        override suspend fun register(directory: String) = error("Unused")
        override suspend fun resolve(ref: WorkspaceRef): String? = resolved.toString().takeIf { ref == workspace }
    }
    private val contribution = DesktopCodingTools(
        projects,
        dispatchers,
        AgentSafetyReviewer { _, trustedPolicy, change ->
            reviews++
            evidence = change
            policy = trustedPolicy
            onReview()
            verdict
        },
        commandEvidence = setOf(
            CommandSafetyEvidence { root, _, _, _ ->
                assertEquals(directory.toString(), root)
                commandSnapshot
            },
        ),
    )
    private val context = AgentToolContext(
        SessionRef(EngineId("test"), SessionSourceId("test"), "caller"),
        workspace,
        TurnId("turn"),
        request = RequestId("request"),
        callId = ToolCallId("call"),
        target = EngineTarget(EngineId("test"), EngineBindingId("binding"), ModelId("model")),
        decisionMode = AgentToolDecisionMode.AutomaticSafety,
        permissions = AgentToolPermissions {
            prompts++
            true
        },
    )

    @AfterTest
    fun cleanUp() {
        dispatcher.close()
        directory.toFile().deleteRecursively()
        other.toFile().deleteRecursively()
    }

    @Test
    fun `automatic safe write reaches execution once without manual permission`() = runTest {
        val result = DefaultAgentTools(setOf(contribution)).execute(context, "write_file", write())
        assertFalse(result.isError, result.text)
        assertEquals("new", Files.readString(directory.resolve("record.txt")))
        assertEquals(1, reviews)
        assertEquals(0, prompts)
        assertTrue(contribution.execute(context, "write_file", write()).isError)
        assertEquals(1, reviews)
    }

    @Test
    fun `reviews of different calls run concurrently`() = runTest {
        val bothStarted = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        onReview = {
            if (reviews == 2) bothStarted.complete(Unit)
            gate.await()
        }
        val tools = DefaultAgentTools(setOf(contribution))
        val other = buildJsonObject {
            put("path", "other.txt")
            put("content", "other")
        }
        val first = async { tools.execute(context, "write_file", write()) }
        val second = async { tools.execute(context.copy(callId = ToolCallId("other")), "write_file", other) }
        withContext(Dispatchers.Default) { withTimeout(10.seconds) { bothStarted.await() } }
        gate.complete(Unit)
        assertFalse(first.await().isError)
        assertFalse(second.await().isError)
        assertEquals("other", Files.readString(directory.resolve("other.txt")))
    }

    @Test
    fun `automatic safe command executes with full command evidence and no prompt`() = runTest {
        val arguments = buildJsonObject {
            put("command", "exit 0")
            put("timeout_seconds", 1)
        }
        val result = DefaultAgentTools(setOf(contribution)).execute(context, "run_command", arguments)
        assertFalse(result.isError, result.text)
        assertEquals(
            "exit 0",
            Json.parseToJsonElement(evidence).jsonObject["arguments"]
                ?.jsonObject?.get("command")?.jsonPrimitive?.content,
        )
        assertEquals(1, reviews)
        assertEquals(0, prompts)
    }

    @Test
    fun `command policy requires evidence for the push exception and names its limits`() = runTest {
        verdict = AgentSafetyReview("risk", "Refused by the test")
        val arguments = buildJsonObject { put("command", "git push -u origin fix/branch") }
        assertTrue(DefaultAgentTools(setOf(contribution)).execute(context, "run_command", arguments).isError)
        assertEquals(1, reviews)
        assertEquals(0, prompts)
        assertTrue("ordinary push of already committed work to the project's existing configured origin" in policy)
        assertTrue("read-only `gh` commands" in policy)
        assertTrue("own package or build manager" in policy)
        assertTrue("npm publish" in policy)
        for (limit in listOf(
            "force push",
            "deletion",
            "mirrors",
            "tag pushes",
            "other remotes",
            "explicit URLs",
            "gitPushEvidence",
            "Missing/unavailable evidence",
        )) {
            assertTrue(limit in policy, limit)
        }
    }

    @Test
    fun `hosted push authorization binds captured evidence without executing git`() = runTest {
        commandSnapshot = buildJsonObject { put("head", "before") }
        val arguments = buildJsonObject { put("command", "git push -u origin branch") }
        val spec = contribution.specifications(workspace).single { it.name == "run_command" }
        val allowed = assertIs<AgentToolAutomaticAuthorization.Allowed>(
            contribution.automaticAuthorization(context, spec, arguments),
        )
        assertEquals(commandSnapshot, Json.parseToJsonElement(evidence).jsonObject["gitPushEvidence"])
        commandSnapshot = buildJsonObject { put("head", "after") }
        val result = contribution.execute(context.copy(authorization = allowed.approval), "run_command", arguments)
        assertTrue(result.isError)
        assertTrue("changed" in result.text)
        assertEquals(1, reviews)
    }

    @Test
    fun `declared dependency context is recaptured before any command can execute`() = runTest {
        val manifest = directory.resolve("package.json")
        Files.writeString(manifest, "{\"dependencies\":{\"library\":\"1.0.0\"}}")
        Files.writeString(directory.resolve("package-lock.json"), "{\"lockfileVersion\":3}")
        var captured = ""
        var assessments = 0
        val owner = DesktopCodingTools(
            projects,
            dispatchers,
            AgentSafetyReviewer { _, _, snapshot ->
                captured = snapshot
                assessments++
                Files.writeString(manifest, "{\"scripts\":{\"postinstall\":\"opaque-script\"}}")
                AgentSafetyReview("safe", "Mock approval before the manifest changes")
            },
            commandEvidence = setOf(DesktopGitPushEvidence(dispatchers)),
        )
        val args = buildJsonObject {
            put("command", "npm ci --ignore-scripts")
            put("review_files", JsonArray(listOf("package.json", "package-lock.json").map(::JsonPrimitive)))
        }
        val result = DefaultAgentTools(setOf(owner)).execute(context, "run_command", args)
        assertTrue(result.isError)
        assertTrue("changed" in result.text)
        assertEquals(1, assessments)
        assertTrue("projectNetworkEvidence" in captured)
        assertTrue("library" in captured)
        assertFalse("opaque-script" in captured)
        assertEquals(0, prompts)
    }

    @Test
    fun `risk uncertainty missing model and unavailable reviewer fail closed for full trust`() = runTest {
        val tools = DefaultAgentTools(setOf(contribution))
        for (value in listOf("risk", "uncertain")) {
            verdict = AgentSafetyReview(value, "Unsafe operation")
            val caller = context.copy(turn = TurnId(value), trust = TrustLevel.Full)
            assertTrue(tools.execute(caller, "write_file", write()).isError)
        }
        assertTrue(tools.execute(context.copy(target = null), "write_file", write()).isError)
        onReview = { error("Reviewer unavailable") }
        assertTrue(tools.execute(context.copy(turn = TurnId("failure")), "write_file", write()).isError)
        assertFalse(Files.exists(directory.resolve("record.txt")))
        assertEquals(0, prompts)
    }

    @Test
    fun `edit review receives complete old content and exact replacement outside trusted policy`() = runTest {
        Files.writeString(directory.resolve("record.txt"), "private-old\nrest")
        val arguments = buildJsonObject {
            put("path", "record.txt")
            put("old_string", "private-old")
            put("new_string", "private-new")
        }
        assertFalse(DefaultAgentTools(setOf(contribution)).execute(context, "edit_file", arguments).isError)
        assertEquals("private-new\nrest", Files.readString(directory.resolve("record.txt")))
        val change = Json.parseToJsonElement(evidence).jsonObject
        assertEquals("private-old\nrest", change["previousFile"]?.jsonObject?.get("content")?.jsonPrimitive?.content)
        assertEquals("private-new", change["arguments"]?.jsonObject?.get("new_string")?.jsonPrimitive?.content)
        assertFalse("private-old" in policy || "private-new" in policy)
    }

    @Test
    fun `changed preimage during review cancels final execution without a second review`() = runTest {
        Files.writeString(directory.resolve("record.txt"), "old")
        onReview = { Files.writeString(directory.resolve("record.txt"), "concurrent") }
        val result = DefaultAgentTools(setOf(contribution)).execute(context, "write_file", write())
        assertTrue(result.isError)
        assertEquals("concurrent", Files.readString(directory.resolve("record.txt")))
        assertEquals(1, reviews)
        assertEquals(0, prompts)
    }

    @Test
    fun `workspace remap and availability changes after review cannot redirect mutation`() = runTest {
        val tools = DefaultAgentTools(setOf(contribution))
        onReview = { resolved = other }
        assertTrue(tools.execute(context, "write_file", write()).isError)
        assertFalse(Files.exists(directory.resolve("record.txt")))
        assertFalse(Files.exists(other.resolve("record.txt")))
        onReview = { available = false }
        assertTrue(tools.execute(context.copy(turn = TurnId("disabled")), "write_file", write()).isError)
        assertFalse(Files.exists(other.resolve("record.txt")))
        assertEquals(2, reviews)
    }

    @Test
    fun `disabled policy after review is rechecked before mutation`() = runTest {
        var denied = emptySet<String>()
        val resolver = ToolPolicyResolver { _, _, _ -> ResolvedToolPolicy(hostedDenied = denied) }
        val tools = DefaultAgentTools(setOf(contribution), policies = resolver)
        onReview = { denied = setOf("write_file") }
        assertTrue(tools.execute(context, "write_file", write()).isError)
        assertFalse(Files.exists(directory.resolve("record.txt")))
        assertEquals(1, reviews)
        assertEquals(0, prompts)
    }

    @Test
    fun `pure approval binds caller turn command and arguments without starting reviewer`() = runTest {
        val spec = contribution.specifications(workspace).single { it.name == "write_file" }
        val original = contribution.approval(context, spec, write())
        assertEquals(0, reviews)
        val allowed = assertIs<AgentToolAutomaticAuthorization.Allowed>(
            contribution.automaticAuthorization(context, spec, write()),
        )
        assertEquals(original, allowed.approval)
        assertEquals(original, contribution.approval(context, spec, write()))
        val changed = listOf(
            context.copy(turn = TurnId("other")),
            context.copy(callId = ToolCallId("other")),
            context.copy(session = context.session.copy(nativeId = "other")),
            context.copy(target = context.target?.copy(model = ModelId("other"))),
        )
        for (caller in changed) {
            assertNotEquals(original, contribution.approval(caller, spec, write()))
            assertTrue(contribution.execute(caller.copy(authorization = original), "write_file", write()).isError)
        }
        val approved = context.copy(authorization = original)
        assertTrue(contribution.execute(approved, "write_file", write("different")).isError)
        assertEquals(1, reviews)
        assertFalse(Files.exists(directory.resolve("record.txt")))
    }

    @Test
    fun `approved direct execution consumes one shot verdict and never restarts reviewer`() = runTest {
        val spec = contribution.specifications(workspace).single { it.name == "run_command" }
        val arguments = buildJsonObject { put("command", "exit 0") }
        val allowed = assertIs<AgentToolAutomaticAuthorization.Allowed>(
            contribution.automaticAuthorization(context, spec, arguments),
        )
        val approved = context.copy(authorization = allowed.approval)
        assertFalse(contribution.execute(approved, "run_command", arguments).isError)
        assertTrue(contribution.execute(approved, "run_command", arguments).isError)
        assertEquals(1, reviews)
    }

    @Test
    fun `outside paths metadata and oversized evidence never reach reviewer`() = runTest {
        val tools = DefaultAgentTools(setOf(contribution))
        for (path in listOf("../escaped.txt", ".git/config", other.resolve("outside.txt").toString())) {
            val args = buildJsonObject {
                put("path", path)
                put("content", "new")
            }
            assertTrue(tools.execute(context, "write_file", args).isError)
        }
        assertTrue(tools.execute(context, "write_file", write("x".repeat(300_000))).isError)
        assertEquals(0, reviews)
        assertEquals(0, prompts)
    }

    @Test
    fun `interactive calls retain manual trust behavior and reads require no review`() = runTest {
        val tools = DefaultAgentTools(setOf(contribution))
        val interactive = context.copy(decisionMode = AgentToolDecisionMode.Interactive)
        assertFalse(tools.execute(interactive, "write_file", write()).isError)
        assertEquals(1, prompts)
        assertEquals(0, reviews)
        val args = buildJsonObject { put("path", "record.txt") }
        assertFalse(tools.execute(context, "read_file", args).isError)
        assertEquals(1, prompts)
        assertEquals(0, reviews)
    }

    @Test
    fun `cancelled review propagates and cannot leave reusable authorization`() = runTest {
        onReview = { throw CancellationException("Ended") }
        assertFailsWith<CancellationException> {
            DefaultAgentTools(setOf(contribution)).execute(context, "write_file", write())
        }
        assertTrue(contribution.execute(context, "write_file", write()).isError)
        assertFalse(Files.exists(directory.resolve("record.txt")))
        assertEquals(0, prompts)
    }

    @Test
    fun `missing trusted request call or model fails closed before review`() = runTest {
        val tools = DefaultAgentTools(setOf(contribution))
        val incompleteCallers = listOf(
            context.copy(request = null),
            context.copy(callId = null),
            context.copy(target = null),
        )
        for (incomplete in incompleteCallers) {
            assertTrue(tools.execute(incomplete, "write_file", write()).isError)
        }
        assertFalse(Files.exists(directory.resolve("record.txt")))
        assertEquals(0, reviews)
        assertEquals(0, prompts)
    }

    @Test
    fun `retargeting an in-project symbolic link during review cannot redirect a write`() = runTest {
        // Windows requires an optional OS privilege to create symbolic links; macOS exercises this path.
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) {
            val first = directory.resolve("first.txt")
            val second = directory.resolve("second.txt")
            Files.writeString(first, "first")
            Files.writeString(second, "second")
            val link = directory.resolve("record.txt")
            Files.createSymbolicLink(link, first)
            onReview = {
                Files.delete(link)
                Files.createSymbolicLink(link, second)
            }
            assertTrue(DefaultAgentTools(setOf(contribution)).execute(context, "write_file", write()).isError)
            assertEquals("first", Files.readString(first))
            assertEquals("second", Files.readString(second))
            assertEquals(1, reviews)
            assertEquals(0, prompts)
        }
    }

    private fun write(content: String = "new"): JsonObject = buildJsonObject {
        put("path", "record.txt")
        put("content", content)
    }
}
