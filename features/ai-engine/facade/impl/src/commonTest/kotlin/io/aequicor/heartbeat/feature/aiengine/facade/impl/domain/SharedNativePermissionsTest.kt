package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolDecisionMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativePreparation
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.SecurityMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.DefaultAgentTools
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SharedNativePermissionsTest {
    @Test
    fun `native and hosted tools use the same profile mode ignoring adapter coverage`() = runTest {
        for (mode in SecurityMode.entries) {
            for (trust in TrustLevel.entries) {
                var prompts = 0
                val tools = tools(TestSecuritySettings(mode))
                val context = Context.copy(
                    trust = trust,
                    permissions = AgentToolPermissions {
                        prompts++
                        true
                    },
                )
                assertEquals(NativeVerdict.Allow, tools.authorizeNative(context, Call))
                assertEquals(if (mode == SecurityMode.Trust) 0 else 1, prompts)
            }
        }
    }

    @Test
    fun `pending native confirmation follows mode changes and is consumed once`() = runTest {
        val settings = TestSecuritySettings(SecurityMode.Automatic)
        val tools = tools(settings)
        val prepared = assertIs<NativePreparation.Ask>(tools.prepareNative(Context, Call))
        settings.save(SecurityMode.Trust)
        assertEquals(NativeVerdict.Allow, prepared.confirmation.authorize())
        assertIs<NativeVerdict.Deny>(prepared.confirmation.authorize())
    }

    @Test
    fun `native user decline returns its explicit reason`() = runTest {
        val tools = tools(TestSecuritySettings(SecurityMode.Automatic))
        val denied = assertIs<NativeVerdict.Deny>(tools.authorizeNative(Context, Call))
        assertTrue(denied.reason.contains("user declined"))
    }

    @Test
    fun `native confirmation rejects input changed while the question is open`() = runTest {
        val data = mutableMapOf("command" to JsonPrimitive("echo hello"))
        val context = Context.copy(
            permissions = AgentToolPermissions {
                data["command"] = JsonPrimitive("changed")
                true
            },
        )
        val tools = tools(TestSecuritySettings(SecurityMode.Automatic))
        assertIs<NativeVerdict.Deny>(tools.authorizeNative(context, Call.copy(arguments = JsonObject(data))))
    }

    private fun tools(settings: TestSecuritySettings) = DefaultAgentTools(
        emptySet(),
        policies = ToolPolicyResolver { _, _, _ -> ResolvedToolPolicy(nativeOn = setOf("bash")) },
        settings = settings,
    )
}

private val Context = AgentToolContext(
    SessionRef(EngineId("test"), SessionSourceId("local"), "caller"),
    WorkspaceRef("project"),
    TurnId("turn"),
    decisionMode = AgentToolDecisionMode.AutomaticSafety,
)
private val Call = NativeToolCall("bash", AgentToolAction.Command, command = "true", covered = { false })
