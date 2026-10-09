package io.aequicor.heartbeat.feature.harness.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolSwitch
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.ToolPolicySpec
import io.aequicor.heartbeat.feature.harness.impl.data.delivery.HarnessToolPolicyProvider
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessActiveAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.harness
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class HarnessToolPolicyProviderTest {
    @Test
    fun `off dominates independent of harness order and engine policies remain separate`() = runTest {
        val first = harness.copy(
            tools = ToolPolicySpec(setOf("run_command"), mapOf("pi" to mapOf("read" to ToolSwitch.Off))),
        )
        val second = harness.copy(
            tools = ToolPolicySpec(
                setOf("web_search"),
                mapOf("pi" to mapOf("read" to ToolSwitch.On, "grep" to ToolSwitch.On)),
            ),
        )
        for (active in listOf(listOf(first, second), listOf(second, first))) {
            val provider = HarnessToolPolicyProvider(lazy { HarnessActiveAccess { _, _ -> active } })
            val policy = provider.policy(ToolPolicyScope(engine = EngineId("pi")))
            assertEquals(setOf("run_command", "web_search"), policy?.hostedOff)
            assertEquals(mapOf("read" to ToolSwitch.Off, "grep" to ToolSwitch.On), policy?.native)
            assertEquals(emptyMap(), provider.policy(ToolPolicyScope(engine = EngineId("claude")))?.native)
        }
    }

    @Test
    fun `trusted workspace and session reach resolver and unavailable evidence never becomes an empty policy`() =
        runTest {
            val workspace = WorkspaceRef("workspace")
            val scope = ToolPolicyScope(workspace = workspace, session = dispatchSession)
            val provider = HarnessToolPolicyProvider(
                lazy {
                    HarnessActiveAccess { actualWorkspace, actualSession ->
                        assertEquals(workspace, actualWorkspace)
                        assertEquals(dispatchSession, actualSession)
                        emptyList()
                    }
                },
            )
            assertNull(provider.policy(scope))
            val failed = HarnessToolPolicyProvider(lazy { HarnessActiveAccess { _, _ -> error("Unavailable") } })
            assertFailsWith<IllegalStateException> { failed.policy(scope) }
            val cancelled = HarnessToolPolicyProvider(
                lazy { HarnessActiveAccess { _, _ -> throw CancellationException("Stopped") } },
            )
            assertFailsWith<CancellationException> { cancelled.policy(scope) }
        }
}
