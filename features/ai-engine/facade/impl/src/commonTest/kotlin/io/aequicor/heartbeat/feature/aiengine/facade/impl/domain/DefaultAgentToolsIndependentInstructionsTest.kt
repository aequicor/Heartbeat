package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.DefaultAgentTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class DefaultAgentToolsIndependentInstructionsTest {
    private val scope = AgentToolScope(
        workspace = null,
        target = EngineTarget(EngineId("test"), EngineBindingId("binding"), ModelId("model")),
        declared = emptySet(),
        session = SessionRef(EngineId("test"), SessionSourceId("local"), "native"),
        isRefreshedPerTurn = true,
    )

    @Test
    fun `failed optional declarations still allow independent source guidance`() = runTest {
        val optional = InstructionOwner(hasIndependentInstructions = false) { error("Optional instructions") }
        val guide = InstructionOwner { "The browser means the external browser hosting this chat" }
        val tools = DefaultAgentTools(linkedSetOf(optional, guide))
        assertFailsWith<IllegalStateException> { tools.specifications(scope.copy(declared = null)) }

        assertEquals("The browser means the external browser hosting this chat", tools.instructions(scope))
        assertEquals(1, optional.specificationCalls)
        assertEquals(0, guide.specificationCalls)
        assertEquals(emptyList(), optional.instructionScopes)
        assertEquals(listOf(scope), guide.instructionScopes)
    }

    @Test
    fun `independent fallback preserves workspace detached and adapter operated boundaries`() = runTest {
        val detached = InstructionOwner { "Detached guidance" }
        val project = InstructionOwner(isDetachedSupported = false) { "Project guidance" }
        val adapter = InstructionOwner(isAdapterOperated = true) { error("Adapter instructions") }
        val optional = InstructionOwner(hasIndependentInstructions = false) { error("Optional instructions") }
        val tools = DefaultAgentTools(linkedSetOf(detached, project, adapter, optional))

        assertEquals("Detached guidance", tools.instructions(scope))
        val workspace = scope.copy(workspace = WorkspaceRef("project"))
        assertEquals("Detached guidance\n\nProject guidance", tools.instructions(workspace))
        assertEquals(listOf(scope, workspace), detached.instructionScopes)
        assertEquals(listOf(workspace), project.instructionScopes)
        assertEquals(emptyList(), adapter.instructionScopes)
        assertEquals(emptyList(), optional.instructionScopes)
        assertEquals(0, listOf(detached, project, adapter, optional).sumOf { it.specificationCalls })
    }

    @Test
    fun `empty guidance and no independent owner leave fallback instructions empty`() = runTest {
        val optional = InstructionOwner(hasIndependentInstructions = false) { error("Optional instructions") }
        val guide = InstructionOwner { "" }

        assertEquals("", DefaultAgentTools(linkedSetOf(optional, guide)).instructions(scope))
        assertEquals("", DefaultAgentTools(setOf(optional)).instructions(scope))
        assertEquals(listOf(scope), guide.instructionScopes)
        assertEquals(emptyList(), optional.instructionScopes)
        assertEquals(0, optional.specificationCalls)
        assertEquals(0, guide.specificationCalls)
    }

    @Test
    fun `independent guidance failure propagates instead of silently losing source context`() = runTest {
        val failure = IllegalStateException("Independent guidance failed")
        val guide = InstructionOwner { throw failure }

        assertSame(
            failure,
            assertFailsWith<IllegalStateException> { DefaultAgentTools(setOf(guide)).instructions(scope) },
        )
        assertEquals(0, guide.specificationCalls)
    }

    @Test
    fun `independent guidance cancellation propagates`() = runTest {
        val cancellation = CancellationException("Instructions cancelled")
        val guide = InstructionOwner { throw cancellation }

        assertSame(
            cancellation,
            assertFailsWith<CancellationException> { DefaultAgentTools(setOf(guide)).instructions(scope) },
        )
        assertEquals(0, guide.specificationCalls)
    }
}

private class InstructionOwner(
    override val hasIndependentInstructions: Boolean = true,
    override val isDetachedSupported: Boolean = true,
    override val isAdapterOperated: Boolean = false,
    private val guidance: suspend (AgentToolScope) -> String,
) : AgentToolContribution {
    var specificationCalls = 0
    val instructionScopes = mutableListOf<AgentToolScope>()

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> {
        specificationCalls++
        error("Optional declarations unavailable")
    }

    override suspend fun instructions(scope: AgentToolScope): String {
        instructionScopes += scope
        return guidance(scope)
    }

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject) = error("No tools")
}
