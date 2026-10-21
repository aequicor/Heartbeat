package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionSafetyEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SecurityMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Production Metro wiring and actual OS effects; unavailable review must retain the exact manual decision. */
@OptIn(ExperimentalCoroutinesApi::class)
class ActionSafetyGraphTest {
    private val persisted = PersistedProfile()
    private val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(Dispatchers.Default.limitedParallelism(1))

    @AfterTest
    fun tearDown() {
        try {
            runBlocking { app.closeAndAwaitStorages() }
        } finally {
            Dispatchers.resetMain()
            File(persisted.storageRoot).deleteRecursively()
        }
    }

    @Test
    fun `production graph keeps review failure manual and binds actual execution to one call`() = runBlocking {
        withTimeout(60.seconds) {
            (app as TestToggleAccessors).toggleControl.setOverride(ActionSafetyEnabled, true)
            val graph = app.profileSessions.open(ProfileId("action-safety-graph")).graph
            val profile = graph as HostedToolCatalogAccessors
            (graph as AiEngineTestAccessors).agentSecuritySettings.save(SecurityMode.Automatic)
            val tools = profile.agentTools
            assertTrue(tools.requiresActionAnalysis())
            val directory = File(persisted.storageRoot, "action-project").apply { mkdirs() }
            File(directory, "child").mkdir()
            val workspace = profile.localWorkspaces.registerManaged(directory.absolutePath).ref
            val session = SessionRef(EngineId("unavailable-review-engine"), SessionSourceId("local"), "session")
            val request = RequestId("human-request")
            val turn = TurnId("turn")
            val target = EngineTarget(session.engine, EngineBindingId("missing"), ModelId("missing"))
            tools.bindTurn(session, request, turn, target = target)
            tools.bindUserInstruction(session, request, turn, "List this project's directories and create marker.txt.")
            var approvals = 0
            var isApproved = true
            val permissions = AgentToolPermissions { approval ->
                assertTrue(approval.description.orEmpty().contains("Arguments:"))
                assertFalse(File(directory, "marker.txt").exists())
                approvals++
                isApproved
            }
            fun context(id: String) = AgentToolContext(
                session, workspace, turn, request = request, permissions = permissions,
                callId = ToolCallId(id), target = target,
            )
            val isWindows = System.getProperty("os.name").startsWith("Windows")
            val listing = if (isWindows) "Get-ChildItem -Directory | Select-Object -ExpandProperty Name" else "/bin/ls -1"
            val listed = tools.execute(context("listing"), "run_command", buildJsonObject { put("command", listing) })
            assertFalse(listed.isError, listed.text)
            assertTrue(listed.text.contains("child"), listed.text)
            assertEquals(1, approvals, "Unavailable isolated model review must fall back to a human decision")
            val source = if (isWindows) {
                "Set-Content -LiteralPath marker.txt -Value approved -NoNewline"
            } else {
                "printf approved > marker.txt"
            }
            val arguments = buildJsonObject { put("command", source) }
            isApproved = false
            assertTrue(tools.execute(context("denied"), "run_command", arguments).isError)
            assertFalse(File(directory, "marker.txt").exists())
            isApproved = true
            val allowedContext = context("allowed")
            val allowed = tools.execute(allowedContext, "run_command", arguments)
            assertFalse(allowed.isError, allowed.text)
            assertEquals("approved", File(directory, "marker.txt").readText())
            assertTrue(tools.execute(allowedContext, "run_command", arguments).isError)
            assertEquals(3, approvals, "A replay cannot ask again or execute twice")
            tools.finishTurn(session, turn)
        }
    }
}
