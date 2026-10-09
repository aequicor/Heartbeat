package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.DefaultAgentTools
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultAgentToolsScopeTest {
    @Test
    fun `independent instructions survive an empty declaration set while preserving detached boundaries`() = runTest {
        val seen = mutableListOf<AgentToolScope>()
        val owner = object : AgentToolContribution {
            override val hasIndependentInstructions = true
            override suspend fun specifications(workspace: WorkspaceRef?) = emptyList<AgentToolSpec>()
            override suspend fun instructions(scope: AgentToolScope): String {
                seen += scope
                return "Independent knowledge"
            }
            override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject) =
                error("No tools")
        }
        val tools = DefaultAgentTools(setOf(owner))
        val scope = AgentToolScope(PROJECT, TARGET, declared = emptySet())
        assertEquals("Independent knowledge", tools.instructions(scope))
        assertEquals(listOf(scope), seen)
        assertEquals("", tools.instructions(AgentToolScope(null)))
        assertEquals(1, seen.size)
        assertTrue(tools.specifications(scope).isEmpty())
    }

    @Test
    fun `session without a project sees only detached contributions`() = runTest {
        val project = ScopedOwner("project_tool", isDetached = false)
        val detached = ScopedOwner("detached_tool", isDetached = true)
        val tools = DefaultAgentTools(linkedSetOf(project, detached))

        assertEquals(listOf("detached_tool"), tools.specifications(null).map { it.name })
        assertEquals("detached_tool:null", tools.instructions(null))
        assertEquals("detached_tool:null", tools.instructions(AgentToolScope(null)))
        assertTrue(tools.execute(context(workspace = null), "project_tool", EMPTY).isError)
        assertFalse(tools.execute(context(workspace = null), "detached_tool", EMPTY).isError)
        assertEquals(0, project.calls)

        assertEquals(listOf("project_tool", "detached_tool"), tools.specifications(PROJECT).map { it.name })
        assertFalse(tools.execute(context(), "project_tool", EMPTY).isError)
    }

    @Test
    fun `scoped instructions receive the target and honour declared tools`() = runTest {
        val first = ScopedOwner("first", isDetached = true)
        val second = ScopedOwner("second", isDetached = true)
        val tools = DefaultAgentTools(linkedSetOf(first, second))

        assertEquals("first:model\n\nsecond:model", tools.instructions(AgentToolScope(PROJECT, TARGET)))
        assertEquals("second:model", tools.instructions(AgentToolScope(PROJECT, TARGET, declared = setOf("second"))))
        assertEquals("", tools.instructions(AgentToolScope(PROJECT, TARGET, declared = emptySet())))
    }

    @Test
    fun `owner decision is added to the trust table and never removes one`() = runTest {
        for (trust in TrustLevel.entries) {
            val read = ScopedOwner("tool", isDetached = false, action = AgentToolAction.Read, isDecisionDemanded = true)
            var asked = 0
            val permissions = AgentToolPermissions {
                asked++
                true
            }
            assertFalse(DefaultAgentTools(setOf(read)).execute(context(trust, permissions), "tool", EMPTY).isError)
            assertEquals(1, asked, "$trust")
        }
        val command = ScopedOwner("tool", isDetached = false, action = AgentToolAction.Command)
        val declined = context(TrustLevel.Ask, AgentToolPermissions { false })
        assertTrue(DefaultAgentTools(setOf(command)).execute(declined, "tool", EMPTY).isError)
        assertEquals(0, command.calls)
    }

    @Test
    fun `bound turn target replaces the adapter supplied one`() = runTest {
        val owner = ScopedOwner("tool", isDetached = false)
        val tools = DefaultAgentTools(setOf(owner))
        val request = RequestId("request")
        tools.bindTurn(SESSION, request, TurnId("facade"), TARGET)

        val forged = EngineTarget(EngineId("other"), EngineBindingId("binding"), ModelId("forged"))
        assertFalse(tools.execute(context().copy(request = request, target = forged), "tool", EMPTY).isError)
        assertEquals(TARGET, owner.lastContext?.target)
        assertEquals(TurnId("facade"), owner.lastContext?.turn)

        assertFalse(tools.execute(context().copy(target = forged), "tool", EMPTY).isError)
        assertEquals(forged, owner.lastContext?.target)
    }
}

private class ScopedOwner(
    private val name: String,
    private val isDetached: Boolean,
    private val action: AgentToolAction = AgentToolAction.Read,
    private val isDecisionDemanded: Boolean = false,
) : AgentToolContribution {
    var calls = 0
    var lastContext: AgentToolContext? = null
    override val isDetachedSupported: Boolean get() = isDetached
    override suspend fun specifications(workspace: WorkspaceRef?) = listOf(AgentToolSpec(name, name, EMPTY, action))
    override suspend fun instructions(workspace: WorkspaceRef?): String = "$name:${workspace?.value}"
    override suspend fun instructions(scope: AgentToolScope): String =
        "$name:${scope.target?.model?.value ?: scope.workspace?.value}"
    override suspend fun requiresDecision(context: AgentToolContext, spec: AgentToolSpec, arguments: JsonObject) =
        isDecisionDemanded
    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        calls++
        lastContext = context
        return AgentToolResult("done")
    }
}

private val PROJECT = WorkspaceRef("project")
private val SESSION = SessionRef(EngineId("test"), SessionSourceId("local"), "native")
private val TARGET = EngineTarget(EngineId("test"), EngineBindingId("binding"), ModelId("model"))
private val EMPTY = JsonObject(emptyMap())

private fun context(
    trust: TrustLevel = TrustLevel.Full,
    permissions: AgentToolPermissions = AgentToolPermissions { false },
    workspace: WorkspaceRef? = PROJECT,
) = AgentToolContext(SESSION, workspace, TurnId("turn"), trust = trust, permissions = permissions)
