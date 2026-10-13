package io.aequicor.heartbeat.feature.contextwindowoptimization.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOwner
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.CompactionOrigin
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextLedger
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationIntent.Public
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationState
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationTools
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationView
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.LedgerUpdate
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.updated
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class ContextLedgerDeliveryTest {
    private val ledger: ContextLedger = null.updated(LedgerUpdate(goal = "Fix every open issue"), Instant.DISTANT_PAST)
    private val state = MutableStateFlow<ContextOptimizationState>(
        ContextOptimizationState.Ready(
            ledgers = mapOf(Session to ledger),
            usage = mapOf(Session to ContextUsage(70, 100)),
        ),
    )
    private val view = object : ContextOptimizationView {
        override val state = this@ContextLedgerDeliveryTest.state
    }

    @Test
    fun `the ledger reaches a native context once per revision`() = runTest {
        val hook = ContextLedgerHook(view, { true }) { false }

        val first = assertNotNull(hook.preparePrompt(HookContext, "continue", "r1"))
        first.receipt?.accepted(HookContext, "r1")

        assertTrue("Fix every open issue" in first.text)
        assertNull(hook.preparePrompt(HookContext, "continue", "r1"))
        assertNotNull(hook.preparePrompt(HookContext, "continue", "r2"), "a compaction renews the revision")
        assertNotNull(hook.preparePrompt(HookContext, "continue", null), "an unprovable context gets it every time")
        assertNull(hook.preparePrompt(HookContext, "/compact", null))
    }

    @Test
    fun `refreshed engines read the ledger from instructions instead of prompts`() = runTest {
        val hook = ContextLedgerHook(view, { true }) { true }
        val tools = ContextAgentTools(view, Registry(), EnabledToggles(), Clock.System)

        assertNull(hook.preparePrompt(HookContext, "continue", null))
        val instructions = tools.instructions(AgentToolScope(null, session = Session, isRefreshedPerTurn = true))
        assertTrue("Fix every open issue" in instructions && "context_checkpoint" in instructions)
    }

    @Test
    fun `checkpoint and compact reach the machine status reads it`() = runTest {
        val registry = Registry()
        val tools = ContextAgentTools(view, registry, EnabledToggles(), Clock.System)
        val checkpoint = Json.parseToJsonElement("""{"done":["#12"],"next":["#13"]}""").jsonObject

        tools.execute(ToolContext, ContextOptimizationTools.CHECKPOINT, checkpoint)
        tools.execute(ToolContext, ContextOptimizationTools.COMPACT, JsonObject(emptyMap()))
        val status = tools.execute(ToolContext, ContextOptimizationTools.STATUS, JsonObject(emptyMap())).text

        val update = (registry.sent.first() as Public.UpdateLedger).update
        assertEquals(listOf("#12"), update.done)
        assertEquals(listOf("#13"), update.next)
        assertEquals(Public.Compact(Session, CompactionOrigin.Agent), registry.sent.last())
        assertTrue("70%" in status && "Fix every open issue" in status)
    }

    private class Registry : MachineRegistry {
        val sent = mutableListOf<MachineIntent>()

        override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
            key: MachineKey<S, I, P, E, O>,
        ): MachineRef<S, P, O>? = null

        override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
            key: MachineKey<S, I, P, E, O>,
        ) = MutableStateFlow<MachineRef<S, P, O>?>(null)

        override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
            key: MachineKey<S, I, P, E, O>,
            intent: P,
        ): SendResult {
            sent += intent
            return SendResult.Accepted
        }
    }

    private class EnabledToggles : FeatureToggles {
        override fun <T : Any> observe(toggle: FeatureToggle<T>) = flowOf(enabled<T>())
        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = enabled()

        @Suppress("UNCHECKED_CAST") // The tools read only flags.
        private fun <T : Any> enabled(): T = true as T
    }

    private companion object {
        val Session = SessionRef(EngineId("codex"), SessionSourceId("s"), "thread")
        val HookContext = SessionHookContext(Session, null, null, null, SessionOwner("owner"))
        val ToolContext = AgentToolContext(
            session = Session,
            workspace = null,
            turn = TurnId("turn"),
            request = null,
            trust = TrustLevel.Ask,
        )
    }
}
