package io.aequicor.heartbeat.feature.planningmode.impl.data

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
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.planningmode.api.Plan
import io.aequicor.heartbeat.feature.planningmode.api.PlanChat
import io.aequicor.heartbeat.feature.planningmode.api.PlanMode
import io.aequicor.heartbeat.feature.planningmode.api.PlanningIntent
import io.aequicor.heartbeat.feature.planningmode.api.PlanningMachineSpec
import io.aequicor.heartbeat.feature.planningmode.api.PlanningState
import io.aequicor.heartbeat.feature.planningmode.api.PlanningView
import io.aequicor.heartbeat.feature.planningmode.impl.domain.PlanFileText
import io.aequicor.heartbeat.feature.planningmode.impl.domain.PlanFiles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Clock
import kotlin.time.Instant

internal val Chat = PlanChat("chat-1")
internal val Session = SessionRef(EngineId("pi"), SessionSourceId("s"), "one")
internal val Now: Instant = Instant.fromEpochSeconds(5_000)

/** A ready state with the plan of [Chat] bound to [Session]. */
internal fun readyWith(plan: Plan): PlanningState.Ready =
    PlanningState.Ready(mapOf(plan.chat to plan), mapOf(Session to plan.chat))

internal fun plan(mode: PlanMode = PlanMode.Planning, revision: Long = 0, text: String = "") =
    Plan(Chat, mode, revision = revision, text = text, updatedAt = Instant.fromEpochSeconds(1))

/** The planning machine without a runtime: public intents go through the real spec. */
internal class SpecPlanning(initial: PlanningState) :
    PlanningView,
    MachineRegistry {
    override val state = MutableStateFlow(initial)
    val sent = mutableListOf<PlanningIntent.Public>()
    var isRunning = true

    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
        key: MachineKey<S, I, P, E, O>,
    ): MachineRef<S, P, O>? = null

    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
        key: MachineKey<S, I, P, E, O>,
    ): StateFlow<MachineRef<S, P, O>?> = MutableStateFlow(null)

    override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
        key: MachineKey<S, I, P, E, O>,
        intent: P,
    ): SendResult {
        if (!isRunning) return SendResult.NotRunning
        val public = intent as PlanningIntent.Public
        sent += public
        val resolution = PlanningMachineSpec.resolve(state.value, public) ?: return SendResult.Ignored
        state.value = resolution.to
        return SendResult.Accepted
    }
}

internal class MemoryPlanFiles : PlanFiles {
    val texts = mutableMapOf<PlanChat, PlanFileText>()

    override suspend fun read(chat: PlanChat): PlanFileText? = texts[chat]

    override suspend fun write(chat: PlanChat, text: String, revision: Long) {
        texts[chat] = PlanFileText(text, isExternal = false, revision = revision)
    }

    override suspend fun delete(chat: PlanChat) {
        texts.remove(chat)
    }
}

internal class Toggles(var isEnabled: Boolean = true) : FeatureToggles {
    @Suppress("UNCHECKED_CAST") // Test fake: only boolean flags are read.
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flowOf(isEnabled as T)

    @Suppress("UNCHECKED_CAST") // Test fake: only boolean flags are read.
    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = isEnabled as T
}

internal object FixedClock : Clock {
    override fun now(): Instant = Now
}
