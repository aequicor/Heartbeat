package io.aequicor.heartbeat.feature.aistudio.api

import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.machineSpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Offline studio landing page; does not create a profile or an AI session. */
@Serializable
@SerialName("ai_studio")
public data object AiStudioRoute : Route

/** The placeholder has no loading or generation phase. */
public data object AiStudioState : MachineState

/** Reserved contract for future studio user actions. */
public sealed interface AiStudioIntent : MachineIntent {
    /** Intents accepted from screens and other features. */
    public sealed interface Public : AiStudioIntent
}

/** The placeholder performs no IO. */
public sealed interface AiStudioEffect : MachineEffect

/** The placeholder emits no business results. */
public sealed interface AiStudioOutput : MachineOutput

/** Address of the studio machine. */
public object AiStudioMachineKey :
    MachineKey<AiStudioState, AiStudioIntent, AiStudioIntent.Public, AiStudioEffect, AiStudioOutput> {
    override val name: String = "ai_studio"
}

/** Ready is the only state until a real studio workflow is implemented. */
public val AiStudioMachineSpec: MachineSpec<AiStudioState, AiStudioIntent, AiStudioEffect, AiStudioOutput> =
    machineSpec(AiStudioMachineKey, AiStudioState) { state<AiStudioState>() }
