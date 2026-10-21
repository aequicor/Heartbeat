package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativePreparation
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.SecurityMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.native.terminatesHostCommand
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.TestSecuritySettings
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ToolPolicyResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Characterizes the reported PowerShell prompts under the profile policy introduced in #504.
 * PiPowerShellListingTest in pi verifies that the adapter supplies Command, empty paths and the actual cwd.
 * The real facade below has no hooks or model reviewer: Command alone makes Automatic ask. Ask is a pending
 * user decision, not a Deny verdict. No command is executed by these tests and no permission rule is changed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PowerShellListingPermissionsTest {
    @Test
    fun `automatic listings wait for an explicit answer and retain the displayed call facts`() = runTest {
        for (command in DirectoryListings) {
            val decision = CompletableDeferred<Boolean>()
            val approvals = mutableListOf<AgentToolApproval>()
            val context = Context.copy(
                permissions = AgentToolPermissions {
                    approvals += it
                    decision.await()
                },
            )
            val tools = tools(SecurityMode.Automatic)
            val call = listing(command)
            assertFalse(terminatesHostCommand(command), command)
            val pending = assertIs<NativePreparation.Ask>(tools.prepareNative(context, call))
            val verdict = async { pending.confirmation.authorize() }
            runCurrent()
            assertFalse(verdict.isCompleted, command)
            val approval = approvals.single()
            assertEquals("powershell", approval.name)
            assertEquals(
                JsonObject(
                    mapOf(
                        "arguments" to checkNotNull(call.arguments),
                        "command" to JsonPrimitive(command),
                        "paths" to JsonArray(emptyList()),
                        "workingDirectory" to JsonPrimitive(WORKSPACE),
                    ),
                ).toString(),
                approval.description,
            )
            decision.complete(true)
            assertEquals(NativeVerdict.Allow, verdict.await())
        }
    }

    @Test
    fun `profile mode determines listing prompts and refusal requires the user to decline`() = runTest {
        for (command in DirectoryListings) {
            for (mode in SecurityMode.entries) {
                val approvals = mutableListOf<AgentToolApproval>()
                val context = Context.copy(
                    permissions = AgentToolPermissions {
                        approvals += it
                        false
                    },
                )
                val verdict = tools(mode).authorizeNative(context, listing(command))
                if (mode == SecurityMode.Trust) {
                    assertEquals(NativeVerdict.Allow, verdict, command)
                    assertTrue(approvals.isEmpty(), command)
                } else {
                    assertEquals(1, approvals.size, "$mode: $command")
                    assertEquals("The user declined this action", assertIs<NativeVerdict.Deny>(verdict).reason)
                }
            }
        }
    }

    @Test
    fun `removing the pipeline or adding a path does not turn a shell command into a native read`() = runTest {
        val tools = tools(SecurityMode.Automatic)
        assertIs<NativePreparation.Ask>(tools.prepareNative(Context, listing("Get-ChildItem")))
        assertIs<NativePreparation.Ask>(
            tools.prepareNative(Context, listing("Get-ChildItem").copy(paths = listOf(WORKSPACE))),
        )
        assertEquals(
            NativePreparation.Allow,
            tools.prepareNative(Context, NativeToolCall("ls", AgentToolAction.Read, paths = listOf(WORKSPACE))),
        )
    }

    private fun tools(mode: SecurityMode) = DefaultAgentTools(
        emptySet(),
        policies = ToolPolicyResolver { _, _, _ -> ResolvedToolPolicy(nativeOn = setOf("powershell", "ls")) },
        settings = TestSecuritySettings(mode),
    )

    private fun listing(command: String) = NativeToolCall(
        "powershell",
        AgentToolAction.Command,
        command = command,
        arguments = JsonObject(mapOf("command" to JsonPrimitive(command))),
        workingDirectory = WORKSPACE,
    )
}

private const val WORKSPACE = "C:/project"
private val Context = AgentToolContext(
    SessionRef(EngineId("pi"), SessionSourceId("local"), "listing-research"),
    WorkspaceRef("project"),
    TurnId("turn"),
)
private val DirectoryListings = listOf(
    "Get-ChildItem -Path features/ai-engine -Directory -Recurse -Depth 2 | Select-Object -ExpandProperty FullName",
    "Get-ChildItem -Path core -Directory | Select-Object -ExpandProperty FullName",
    "Get-ChildItem -Directory | Select-Object -ExpandProperty Name",
)
