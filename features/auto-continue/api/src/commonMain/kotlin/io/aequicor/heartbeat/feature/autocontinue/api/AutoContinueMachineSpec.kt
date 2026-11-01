package io.aequicor.heartbeat.feature.autocontinue.api

import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.StateBuilder
import io.aequicor.heartbeat.core.statemachine.machineSpec
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueIntent.Internal
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueIntent.Public

/**
 * Profile-owned continuation, independently sequenced per chat. Mode and model survive stops and restart.
 * Every generated answer is tied to the current sequence and optional exact structured request.
 *
 * | State / phase | Intent | Result | Effect |
 * |---|---|---|---|
 * | Idle | Start | Loading | Load |
 * | Loading | Loaded / LoadFailed | Ready; restored autopilots Suspended(Restarted) | |
 * | Ready | UpdateSettings | New settings | SaveSettings |
 * | any chat | SetMode | Selected mode/model; known context → Thinking | SaveMode, Ask / Cancel / Resettle |
 * | enabled chat | TurnStarted | Idle, context cleared; human resets reply count | Cancel if busy |
 * | enabled chat | TurnSettled | Thinking; Off only keeps context | Ask |
 * | any chat | TurnAwaitsEvent | WaitingForEvent, context cleared, sequence advanced | Cancel |
 * | autopilot | TurnAwaitsHuman | Paused for a human decision | Cancel if busy |
 * | autopilot | TurnFailed / Stop | Suspended; mode/model kept, sequence advanced | Cancel |
 * | autopilot, not suspended | RequestOpened | Thinking for a different exact request | Ask |
 * | matching request | RequestClosed | Idle, context cleared, sequence advanced | Cancel |
 * | Thinking(seq) | Answered | Suggest → Offered; Autopilot → Delivering immediately or Halted | Deliver |
 * | Thinking(seq) | Answered with changed settings | Idle, old context dropped | Resettle with latest settings |
 * | Thinking(seq) | AskFailed | Halted; mode/model kept | |
 * | Delivering(seq) | Delivered / DeliveryFailed | Idle / Halted | |
 * | busy autopilot | DraftTyped | Suspended(HumanTookOver), mode/model kept | Cancel |
 * | any chat | Forget | Removed | Cancel, SaveMode(null) |
 *
 * Stale sequence results and replaced/closed requests are ignored. Structured requests never fall back to sending
 * ordinary chat text. Legacy countdown intents can drain an already pending countdown, but no new countdown is
 * created. Effect errors map to LoadFailed, SaveFailed, AskFailed or DeliveryFailed.
 */
public val AutoContinueMachineSpec: AutoContinueSpec =
    machineSpec(AutoContinueMachineKey, AutoContinueState.Idle) {
        state<AutoContinueState.Idle> {
            on<Public.Start> {
                goto<AutoContinueState.Loading> { AutoContinueState.Loading }
                effect { AutoContinueEffect.Load }
            }
        }
        state<AutoContinueState.Loading> {
            on<Internal.Loaded> {
                goto<AutoContinueState.Ready> { loaded(intent) }
            }
            on<Internal.LoadFailed> {
                goto<AutoContinueState.Ready> { AutoContinueState.Ready() }
            }
        }
        state<AutoContinueState.Ready> {
            on<Public.UpdateSettings>(guard = { intent.settings != state.settings }) {
                stay { state.copy(settings = intent.settings) }
                effect { AutoContinueEffect.SaveSettings(intent.settings) }
            }
            on<Internal.SaveFailed> {
                stay { state }
                output { AutoContinueOutput.SaveFailed }
            }
            chatStep<Public.SetMode>()
            chatStep<Public.TurnStarted>()
            chatStep<Public.TurnSettled>()
            chatStep<Public.TurnAwaitsHuman>()
            chatStep<Public.TurnAwaitsEvent>()
            chatStep<Public.RequestOpened>()
            chatStep<Public.RequestClosed>()
            chatStep<Public.TurnFailed>()
            chatStep<Public.DraftTyped>()
            chatStep<Public.Refresh>()
            chatStep<Public.SendNow>()
            chatStep<Public.Edit>()
            chatStep<Public.Stop>()
            chatStep<Public.Forget>()
            chatStep<Internal.Answered>()
            chatStep<Internal.AskFailed>()
            chatStep<Internal.CountdownElapsed>()
            chatStep<Internal.Delivered>()
            chatStep<Internal.DeliveryFailed>()
        }
        onEffectFailure { effect, _ ->
            when (effect) {
                AutoContinueEffect.Load -> Internal.LoadFailed
                is AutoContinueEffect.SaveSettings, is AutoContinueEffect.SaveMode -> Internal.SaveFailed
                is AutoContinueEffect.Ask -> Internal.AskFailed(effect.chat, effect.seq, ContinuerFailure.Failed)
                is AutoContinueEffect.Deliver -> Internal.DeliveryFailed(effect.chat, effect.seq)
                is AutoContinueEffect.Countdown, is AutoContinueEffect.Cancel, is AutoContinueEffect.Resettle -> null
            }
        }
    }

/** Type of [AutoContinueMachineSpec]. */
public typealias AutoContinueSpec = MachineSpec<
    AutoContinueState,
    AutoContinueIntent,
    AutoContinueEffect,
    AutoContinueOutput,
>

private typealias ReadyBuilder = StateBuilder<
    AutoContinueState,
    AutoContinueState.Ready,
    AutoContinueIntent,
    AutoContinueEffect,
    AutoContinueOutput,
>

private inline fun <reified J : AutoContinueIntent> ReadyBuilder.chatStep() {
    on<J>(guard = { state.step(intent) != null }) {
        stay { state.applied(state.step(intent), intent.chat) }
        effect { state.step(intent)?.job }
        effect { state.step(intent)?.save }
        output { state.step(intent)?.output }
    }
}

private fun loaded(intent: Internal.Loaded): AutoContinueState.Ready = AutoContinueState.Ready(
    settings = intent.settings,
    chats = intent.modes.mapValues { (id, mode) ->
        if (mode == AutoContinueMode.Autopilot) {
            ChatAuto(mode, Phase.Suspended(HaltReason.Restarted), continuerTarget = intent.targets[id])
        } else {
            ChatAuto(mode, continuerTarget = intent.targets[id])
        }
    },
)
