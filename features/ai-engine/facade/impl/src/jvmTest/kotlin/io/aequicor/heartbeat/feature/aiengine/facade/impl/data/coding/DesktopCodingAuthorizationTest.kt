package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.coding

import io.aequicor.heartbeat.core.common.DispatcherProvider
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.SecurityMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.DefaultAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.TestSecuritySettings
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopCodingAuthorizationTest {
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
    private val projects = object : LocalWorkspaces {
        override val isAvailable get() = available
        override fun observe() = flowOf(listOf(LocalWorkspace(workspace, "Project")))
        override suspend fun register(directory: String) = error("Unused")
        override suspend fun resolve(ref: WorkspaceRef): String? = resolved.toString().takeIf { ref == workspace }
    }
    private val contribution = DesktopCodingTools(projects, dispatchers)
    private val context = AgentToolContext(
        SessionRef(EngineId("test"), SessionSourceId("test"), "caller"),
        workspace,
        TurnId("turn"),
        request = RequestId("request"),
        callId = ToolCallId("call"),
        target = EngineTarget(EngineId("test"), EngineBindingId("binding"), ModelId("model")),
        decisionMode = AgentToolDecisionMode.AutomaticSafety,
        permissions = AgentToolPermissions {
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
    fun `full trust writes without a reviewer model or permission prompt`() = runTest {
        val caller = context.copy(target = null, permissions = AgentToolPermissions { error("Unexpected permission") })
        val tools = DefaultAgentTools(setOf(contribution), settings = TestSecuritySettings(SecurityMode.Trust))
        assertFalse(tools.execute(caller, "write_file", write()).isError)
        assertEquals("new", Files.readString(directory.resolve("record.txt")))
        assertTrue(tools.execute(caller, "write_file", write("replay")).isError)
        assertEquals("new", Files.readString(directory.resolve("record.txt")))
    }

    @Test
    fun `changed file workspace or symlink invalidates a pending operation`() = runTest {
        for (scenario in listOf("file", "workspace", "symlink")) {
            resolved = directory
            val path = directory.resolve("record.txt")
            Files.deleteIfExists(path)
            Files.writeString(path, "before")
            val tools = DefaultAgentTools(setOf(contribution), settings = TestSecuritySettings(SecurityMode.Ask))
            val caller = context.copy(
                permissions = AgentToolPermissions {
                    when (scenario) {
                        "file" -> Files.writeString(path, "human edit")

                        "workspace" -> resolved = other

                        else -> {
                            Files.writeString(directory.resolve("elsewhere.txt"), "other")
                            Files.delete(path)
                            Files.createSymbolicLink(path, directory.resolve("elsewhere.txt"))
                        }
                    }
                    true
                },
            )
            assertTrue(tools.execute(caller, "write_file", write()).isError, scenario)
            assertFalse(Files.exists(other.resolve("record.txt")))
            assertFalse(Files.readString(path) == "new")
        }
    }

    @Test
    fun `large existing content is bound without the obsolete model review limit`() = runTest {
        Files.writeString(directory.resolve("record.txt"), "a".repeat(300_000))
        assertFalse(DefaultAgentTools(setOf(contribution)).execute(context, "write_file", write()).isError)
        assertEquals("new", Files.readString(directory.resolve("record.txt")))
    }

    @Test
    fun `command approval shows complete input and rejection prevents execution`() = runTest {
        val command = if (System.getProperty("os.name").startsWith("Windows")) {
            "Set-Content marker.txt changed"
        } else {
            "touch marker.txt"
        }
        var prompts = 0
        val caller = context.copy(
            permissions = AgentToolPermissions {
                prompts++
                assertTrue(it.description.orEmpty().contains(command))
                false
            },
        )
        val result = DefaultAgentTools(
            setOf(contribution),
        ).execute(caller, "run_command", buildJsonObject { put("command", command) })
        assertTrue(result.isError)
        assertEquals(1, prompts)
        assertFalse(Files.exists(directory.resolve("marker.txt")))
    }

    private fun write(content: String = "new"): JsonObject = buildJsonObject {
        put("path", "record.txt")
        put("content", content)
    }
}
