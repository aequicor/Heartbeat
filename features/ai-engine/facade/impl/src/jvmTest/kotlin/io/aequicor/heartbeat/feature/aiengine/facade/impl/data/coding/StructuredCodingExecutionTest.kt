package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.coding

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SecurityMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.ActionSafetyGate
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.DefaultAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.safety.ActionSafetyPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.security.DesktopNativeActionAnalysis
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.TestSecuritySettings
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real dispatcher, filesystem, parser and shell; only the separately labelled model responses are fixtures. */
class StructuredCodingExecutionTest {
    private val directory = Files.createTempDirectory("structured-coding-").toRealPath()
    private val other = Files.createTempDirectory("structured-coding-other-").toRealPath()
    private val dispatcher = Executors.newCachedThreadPool().asCoroutineDispatcher()
    private val dispatchers = object : DispatcherProvider {
        override val main = dispatcher
        override val default = dispatcher
        override val io = dispatcher
    }
    private val workspace = WorkspaceRef("project")
    private var resolved = directory
    private val workspaces = object : LocalWorkspaces {
        override val isAvailable = true
        override fun observe() = flowOf(listOf(LocalWorkspace(workspace, "Disposable project")))
        override suspend fun register(directory: String) = error("Unused")
        override suspend fun resolve(ref: WorkspaceRef) = resolved.toString().takeIf { ref == workspace }
    }
    private val approvals = mutableListOf<AgentToolApproval>()
    private val modelPrompts = mutableListOf<IsolatedPrompt>()
    private val modelAnswers = ArrayDeque<String>()
    private var permission: suspend () -> Boolean = { false }
    private val settings = TestSecuritySettings(SecurityMode.Automatic)
    private val session = SessionRef(EngineId("test"), SessionSourceId("local"), "session")
    private val request = RequestId("request")
    private val turn = TurnId("turn")
    private val target = EngineTarget(session.engine, EngineBindingId("binding"), ModelId("fixture"))
    private val tools = DefaultAgentTools(
        setOf(DesktopCodingTools(workspaces, dispatchers)),
        workspaces = workspaces,
        settings = settings,
        actionSafety = ActionSafetyGate(
            DesktopNativeActionAnalysis(workspaces, dispatchers),
            ActionSafetyPolicy(
                lazyOf(
                    IsolatedPrompts {
                        modelPrompts += it
                        IsolatedAnswer.Answered(modelAnswers.removeFirst())
                    },
                ),
            ),
            enabled = { true },
        ),
    )

    @AfterTest
    fun cleanUp() {
        dispatcher.close()
        directory.toFile().deleteRecursively()
        other.toFile().deleteRecursively()
    }

    @Test
    fun `original PowerShell listings run through the real automatic gate without confirmation`() = runTest {
        assumeTrue(isWindows())
        Files.createDirectories(directory.resolve("features/ai-engine/facade/api"))
        Files.createDirectories(directory.resolve("core/common"))
        // The controlled runner must not resolve an executable from the project directory.
        Files.writeString(directory.resolve("powershell.exe"), "not a shell")
        bind("List directories in features/ai-engine, core and the project root.", count = 3)
        val commands = listOf(
            "Get-ChildItem -Path features/ai-engine -Directory -Recurse -Depth 2 | " +
                "Select-Object -ExpandProperty FullName",
            "Get-ChildItem -Path core -Directory | Select-Object -ExpandProperty FullName",
            "Get-ChildItem -Directory | Select-Object -ExpandProperty Name",
        )
        for ((index, command) in commands.withIndex()) {
            val result = tools.execute(context("listing-$index"), "run_command", command(command))
            assertFalse(result.isError, result.text)
            assertTrue(result.text.contains("Exit code: 0"))
            val expected = when (index) {
                0 -> "facade"
                1 -> "common"
                else -> "features"
            }
            assertTrue(result.text.contains(expected))
        }
        assertTrue(approvals.isEmpty())
        assertEquals(3, modelPrompts.size)
    }

    @Test
    fun `PowerShell aliases case and quoted literal paths retain verified semantics`() = runTest {
        assumeTrue(isWindows())
        Files.createDirectories(directory.resolve("space dir/child"))
        Files.createDirectories(directory.resolve("space dir/\u043f\u0440\u0438\u043c\u0435\u0440"))
        bind("List subdirectories of space dir.")
        val result = tools.execute(
            context("alias"),
            "run_command",
            command("GCI -LiTeRaLpAtH 'space dir' -DiReCtOrY | SELECT -ExpandProperty NAME"),
        )
        assertFalse(result.isError, result.text)
        assertTrue(result.text.contains("child"))
        assertTrue(result.text.contains("\u043f\u0440\u0438\u043c\u0435\u0440"))
        assertTrue(approvals.isEmpty())
    }

    @Test
    fun `PowerShell process arguments preserve quoted paths and semicolons`() = runTest {
        assumeTrue(isWindows())
        val path = "safe; Set-Content -LiteralPath marker.txt -Value changed; #"
        Files.createDirectories(directory.resolve(path).resolve("child"))
        Files.createDirectory(directory.resolve("safe"))
        bind("List subdirectories of the exact named path. Keep all files unchanged.", count = 2)
        for ((index, quote) in listOf("'", "\"").withIndex()) {
            val source = "Get-ChildItem -LiteralPath $quote$path$quote -Directory"
            val result = tools.execute(context("quoted-$index"), "run_command", command(source))
            assertFalse(Files.exists(directory.resolve("marker.txt")), result.text)
            assertFalse(result.isError, result.text)
            assertTrue(result.text.contains("child"), result.text)
        }
        assertTrue(approvals.isEmpty())
        assertEquals(2, modelPrompts.size)
    }

    @Test
    fun `unsupported PowerShell suffixes redirections and substitutions never execute during analysis`() = runTest {
        assumeTrue(isWindows())
        val commands = listOf(
            "Get-ChildItem -Directory; Set-Content marker.txt changed",
            "Get-ChildItem -Directory > marker.txt",
            "Get-ChildItem -Path $(Set-Content marker.txt changed) -Directory",
            "Get-ChildItem -Directory | ForEach-Object { Set-Content marker.txt changed }",
            "& { Set-Content marker.txt changed; Get-ChildItem -Directory }",
            "Get-ChildItem '-Directory'",
            "Get-ChildItem -Path '~' -Directory",
        )
        for ((index, source) in commands.withIndex()) {
            val result = tools.execute(context("unparsed-$index"), "run_command", command(source))
            assertTrue(result.isError, source)
            assertFalse(Files.exists(directory.resolve("marker.txt")), source)
            assertTrue(approvals.last().description.orEmpty().contains(source))
        }
        assertEquals(commands.size, approvals.size)
        assertTrue(modelPrompts.isEmpty(), "A model must not approve an unverified shell program")
    }

    @Test
    fun `POSIX absolute system listing runs with a controlled environment`() = runTest {
        assumeTrue(!isWindows())
        Files.createDirectory(directory.resolve("child"))
        bind("List the project directory.")
        val result = tools.execute(context("posix"), "run_command", command("/bin/ls -1"))
        assertFalse(result.isError, result.text)
        assertTrue(result.text.contains("child"))
        assertTrue(approvals.isEmpty())
    }

    @Test
    fun `unknown shell command executes only after the exact manual decision and cannot be replayed`() = runTest {
        val source = if (isWindows()) {
            "Set-Content -LiteralPath marker.txt -Value approved -NoNewline"
        } else {
            "printf approved > marker.txt"
        }
        permission = {
            assertFalse(Files.exists(directory.resolve("marker.txt")))
            true
        }
        val caller = context("manual")
        val first = tools.execute(caller, "run_command", command(source))
        assertFalse(first.isError, first.text)
        assertEquals("approved", Files.readString(directory.resolve("marker.txt")))
        assertTrue(tools.execute(caller, "run_command", command(source)).isError)
        assertEquals(1, approvals.size)
        assertTrue(modelPrompts.isEmpty())
    }

    @Test
    fun `workspace changed during command confirmation blocks the real process`() = runTest {
        settings.save(SecurityMode.Ask)
        permission = {
            resolved = other
            true
        }
        val source = if (isWindows()) "Set-Content marker.txt changed" else "printf changed > marker.txt"
        assertTrue(tools.execute(context("changed"), "run_command", command(source)).isError)
        assertFalse(Files.exists(directory.resolve("marker.txt")))
        assertFalse(Files.exists(other.resolve("marker.txt")))
    }

    @Test
    fun `two stage fixture verdicts govern real file effects`() = runTest {
        tools.bindTurn(session, request, turn, target = target)
        tools.bindUserInstruction(session, request, turn, "Create approved.txt containing the word approved.")
        modelAnswers.addAll(listOf("REVIEW", "ALLOW", "REVIEW", "DENY"))
        val allowed = buildJsonObject {
            put("path", "approved.txt")
            put("content", "approved")
        }
        assertFalse(tools.execute(context("allowed"), "write_file", allowed).isError)
        assertEquals("approved", Files.readString(directory.resolve("approved.txt")))
        val forbidden = buildJsonObject {
            put("path", "forbidden.txt")
            put("content", "forbidden")
        }
        permission = { error("Model denial must not become a manual override") }
        assertTrue(tools.execute(context("denied"), "write_file", forbidden).isError)
        assertFalse(Files.exists(directory.resolve("forbidden.txt")))
        assertEquals(4, modelPrompts.size)
        assertTrue(approvals.isEmpty())
    }

    private fun context(id: String) = AgentToolContext(
        session,
        workspace,
        turn,
        request = request,
        callId = ToolCallId(id),
        target = target,
        permissions = AgentToolPermissions {
            approvals += it
            permission()
        },
    )

    private fun command(source: String) = buildJsonObject { put("command", source) }
    private fun isWindows() = System.getProperty("os.name").startsWith("Windows")

    private suspend fun bind(instruction: String, count: Int = 1) {
        tools.bindTurn(session, request, turn, target = target)
        tools.bindUserInstruction(session, request, turn, instruction)
        repeat(count) { modelAnswers.addLast("ALLOW") }
    }
}
