package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.SecurityMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCatalogEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.safety.AgentSafetyToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EngineRegistry
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.TestEngine
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.TestSecuritySettings
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.registration
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServiceIsolationPermissionsTest {
    @Test
    fun `text only sessions cannot access native or adapter operated tools under any mode`() = runTest {
        val entry = registration()
        val registry = EngineRegistry(
            listOf(
                EngineRegistration(
                    entry.descriptor.copy(
                        nativeTools = listOf(NativeToolSpec("bash", AgentToolAction.Command, true, true)),
                    ),
                    entry.authOwner,
                    entry.factory,
                ),
            ),
            EnginePlatform.DesktopMacOs,
        )
        for (mode in SecurityMode.entries) {
            val isolation = AgentSafetyToolPolicy()
            val tools = DefaultAgentTools(
                setOf(Search()),
                policies = ProfileToolPolicies(lazyOf(setOf(isolation)), registry),
                settings = TestSecuritySettings(mode),
            )
            val context = AgentToolContext(
                SessionRef(TestEngine, SessionSourceId("test"), "isolated"),
                null,
                TurnId("turn"),
                permissions = AgentToolPermissions { error("Isolation must not request permission") },
            )
            isolation.restrict(context.session, listOf("bash"))
            assertEquals(
                setOf("web_search"),
                tools.nativeTools(ToolPolicyScope(session = context.session)).hostedDenied,
            )
            assertIs<NativeVerdict.Deny>(tools.authorizeHosted(context, "web_search", JsonObject(emptyMap())))
            assertIs<NativeVerdict.Deny>(
                tools.authorizeNative(context, NativeToolCall("bash", AgentToolAction.Command)),
            )
            assertTrue(tools.specifications(AgentToolScope(workspace = null, session = context.session)).isEmpty())
        }
    }

    private class Search : AgentToolContribution {
        override val isAdapterOperated = true
        override val isDetachedSupported = true
        override val catalog = listOf(ToolCatalogEntry("web_search", AgentToolAction.Read))
        override suspend fun specifications(workspace: WorkspaceRef?) = listOf(
            AgentToolSpec("web_search", "Search", JsonObject(emptyMap()), AgentToolAction.Read),
        )
        override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult =
            error("Adapter-owned tools do not execute here")
    }
}
