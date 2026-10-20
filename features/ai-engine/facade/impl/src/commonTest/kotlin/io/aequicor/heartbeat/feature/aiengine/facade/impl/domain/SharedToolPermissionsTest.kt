package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSecuritySettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolDecisionMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.HookedToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SecurityMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOwner
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolHookVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.SessionHooks
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.DefaultAgentTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedToolPermissionsTest {
    @Test
    fun `profile mode controls every inherited trust and decision mode`() = runTest {
        for (mode in SecurityMode.entries) {
            for (trust in TrustLevel.entries) {
                for (inherited in AgentToolDecisionMode.entries) {
                    for (action in AgentToolAction.entries) {
                        assertMode(mode, trust, inherited, action)
                    }
                }
            }
        }
    }

    private suspend fun assertMode(
        mode: SecurityMode,
        trust: TrustLevel,
        inherited: AgentToolDecisionMode,
        action: AgentToolAction,
    ) {
        val settings = TestSecuritySettings(mode)
        val owner = PermissionOwner(action)
        var prompts = 0
        val context = permissionContext().copy(
            trust = trust,
            decisionMode = inherited,
            permissions = AgentToolPermissions {
                prompts++
                true
            },
        )
        val tools = DefaultAgentTools(setOf(owner), settings = settings)
        assertFalse(tools.execute(context, owner.name, EMPTY).isError)
        assertEquals(if (mode != SecurityMode.Trust && action != AgentToolAction.Read) 1 else 0, prompts)
        assertEquals(1, owner.calls)
        assertEquals(owner.snapshot(), owner.authorization)
    }

    @Test
    fun `only ordinary project edits are automatic while unknown edits and commands ask`() = runTest {
        for (workspace in listOf(null, WorkspaceRef("project"))) {
            for (name in listOf("write_file", "unknown_edit", "run_command")) {
                assertEditConfirmation(workspace, name)
            }
        }
    }

    private suspend fun assertEditConfirmation(workspace: WorkspaceRef?, name: String) {
        var prompts = 0
        val owner = PermissionOwner(
            if (name == "run_command") AgentToolAction.Command else AgentToolAction.Edit,
            name,
        )
        val context = permissionContext().copy(
            workspace = workspace,
            permissions = AgentToolPermissions {
                prompts++
                true
            },
        )
        assertFalse(DefaultAgentTools(setOf(owner)).execute(context, name, EMPTY).isError)
        val expected = if (workspace != null && name == "write_file") 0 else 1
        assertEquals(expected, prompts)
    }

    @Test
    fun `deny and ask hooks request confirmation and full trust bypasses them`() = runTest {
        for (mode in SecurityMode.entries) {
            for (verdict in listOf(
                ToolHookVerdict.Deny("hook"),
                ToolHookVerdict.Ask("hook"),
            )) {
                val owner = PermissionOwner(AgentToolAction.Read)
                val hooks = PermissionHooks().apply { current = verdict }
                var prompts = 0
                val context = permissionContext().copy(
                    permissions = AgentToolPermissions {
                        prompts++
                        true
                    },
                )
                assertFalse(
                    DefaultAgentTools(
                        setOf(owner),
                        hooks,
                        settings = TestSecuritySettings(mode),
                    ).execute(context, owner.name, EMPTY).isError,
                )
                assertEquals(if (mode == SecurityMode.Trust) 0 else 1, prompts)
            }
        }
    }

    @Test
    fun `changed operation or withdrawn capability cannot execute after confirmation`() = runTest {
        for (disappears in listOf(false, true)) {
            val owner = PermissionOwner()
            val context = permissionContext().copy(
                permissions = AgentToolPermissions {
                    if (disappears) owner.isAvailable = false else owner.revision++
                    true
                },
            )
            assertTrue(DefaultAgentTools(setOf(owner)).execute(context, owner.name, EMPTY).isError)
            assertEquals(0, owner.calls)
        }
    }

    @Test
    fun `switching to full trust cancels the pending prompt and executes once`() = runTest {
        val settings = TestSecuritySettings(SecurityMode.Automatic)
        val owner = PermissionOwner()
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val context = permissionContext().copy(
            permissions = AgentToolPermissions {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            },
        )
        val tools = DefaultAgentTools(setOf(owner), settings = settings)
        val pending = async { tools.execute(context, owner.name, EMPTY) }
        entered.await()
        settings.save(SecurityMode.Trust)
        assertFalse(pending.await().isError)
        cancelled.await()
        assertEquals(1, owner.calls)
    }

    @Test
    fun `a stricter mode during final revalidation is applied before execution`() = runTest {
        val settings = TestSecuritySettings(SecurityMode.Trust)
        val owner = PermissionOwner().apply { onSecondApproval = { settings.save(SecurityMode.Ask) } }
        var prompts = 0
        val context = permissionContext().copy(
            permissions = AgentToolPermissions {
                prompts++
                false
            },
        )
        assertTrue(DefaultAgentTools(setOf(owner), settings = settings).execute(context, owner.name, EMPTY).isError)
        assertEquals(1, prompts)
        assertEquals(0, owner.calls)
    }

    @Test
    fun `ending the turn cancels a pending permission and prevents side effects`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val lifetime = Job()
        val owner = PermissionOwner()
        val context = permissionContext().copy(
            lifetime = lifetime,
            permissions = AgentToolPermissions {
                entered.complete(Unit)
                awaitCancellation()
            },
        )
        val task = async { DefaultAgentTools(setOf(owner)).execute(context, owner.name, EMPTY) }
        entered.await()
        lifetime.cancel()
        assertFailsWith<CancellationException> { task.await() }
        assertEquals(0, owner.calls)
    }

    @Test
    fun `the same tool call cannot execute twice even under full trust`() = runTest {
        val owner = PermissionOwner()
        val tools = DefaultAgentTools(setOf(owner), settings = TestSecuritySettings(SecurityMode.Trust))
        val context = permissionContext().copy(callId = ToolCallId("one"))
        assertFalse(tools.execute(context, owner.name, EMPTY).isError)
        assertTrue(tools.execute(context, owner.name, EMPTY).isError)
        assertEquals(1, owner.calls)
    }
}

internal class TestSecuritySettings(initial: SecurityMode) : AgentSecuritySettings {
    override val modes = MutableStateFlow(initial)
    override suspend fun load(): SecurityMode = modes.value
    override suspend fun save(mode: SecurityMode) {
        modes.value = mode
    }
}

private class PermissionOwner(action: AgentToolAction = AgentToolAction.Command, val name: String = "mini_app") :
    AgentToolContribution {
    override val isDetachedSupported = true
    private val spec = AgentToolSpec(name, "Mini app", EMPTY, action)
    var isAvailable = true
    var revision = 1
    var calls = 0
    var approvals = 0
    var authorization: AgentToolApproval? = null
    var onSecondApproval: suspend () -> Unit = {}
    fun snapshot() = AgentToolApproval(spec.name, spec.description, binding = "revision-$revision")
    override suspend fun specifications(workspace: WorkspaceRef?) = if (isAvailable) listOf(spec) else emptyList()
    override suspend fun approval(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): AgentToolApproval {
        if (++approvals == 2) onSecondApproval()
        return snapshot()
    }
    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        calls++
        authorization = context.authorization
        return AgentToolResult("saved")
    }
}

private class PermissionHooks : SessionHooks {
    var current: ToolHookVerdict = ToolHookVerdict.Continue
    override fun context(session: SessionRef, request: RequestId?, turn: TurnId?) =
        SessionHookContext(session, null, request, turn, SessionOwner("test"))
    override suspend fun beforeTool(call: HookedToolCall): ToolHookVerdict = current
}

private fun permissionContext() = AgentToolContext(
    SessionRef(EngineId("test"), SessionSourceId("local"), "caller"),
    WorkspaceRef("workspace"),
    TurnId("turn"),
)

private val EMPTY = JsonObject(emptyMap())
