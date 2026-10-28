package io.aequicor.heartbeat.feature.autocontinue.api

import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.StateBuilder
import io.aequicor.heartbeat.core.statemachine.machineSpec
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueIntent.Internal
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueIntent.Public

/**
 * Auto-continue of studio chats. The profile machine loads settings and chat modes, then reacts per chat; every chat
 * intent in `Ready` stays in `Ready` and changes only [ChatAuto] of its chat. Requests carry a sequence number `seq`:
 * answers, countdowns and deliveries of an older request are ignored.
 *
 * | From | Intent | Guard | To | Effect / output |
 * |---|---|---|---|---|
 * | Idle | Start | | Loading | Load |
 * | Loading | Loaded | | Ready (autopilot chats → Suggest + Halted(Restarted)) | |
 * | Loading | LoadFailed | | Ready() | |
 * | Ready | UpdateSettings | differs | Ready(settings) | SaveSettings |
 * | Ready | SaveFailed | | Ready | SaveFailed |
 *
 * Per chat in `Ready` (mode Off only keeps the context of the last settled turn):
 *
 * | Phase | Intent | Guard | Chat | Effect / output |
 * |---|---|---|---|---|
 * | any | SetMode | mode differs | mode, Idle; Autopilot resets counter; settled → Thinking | Ask / Cancel; SaveMode |
 * | any | TurnStarted | | Idle, no context; human resets counter; Autopilot → Halted(HumanTookOver) | Cancel; Save |
 * | any | TurnSettled | | Thinking(seq + 1), context kept; Off: only the context kept | Ask |
 * | any | TurnAwaitsHuman | | Autopilot → Paused, Suggest → Idle | Cancel if busy |
 * | any | TurnFailed | | Autopilot → Halted(UserStopped / TurnFailed), Suggest → Idle | Cancel if busy; SaveMode |
 * | Thinking / Offered / Halted | DraftTyped | Suggest | Idle | Cancel if Thinking |
 * | Thinking / Countdown | DraftTyped | Autopilot | Halted(HumanTookOver) | Cancel; SaveMode |
 * | not busy | Refresh | context known | Thinking(seq + 1) | Ask |
 * | Thinking(seq) | Answered | Suggest | Offered(verdict) | |
 * | Thinking(seq) | Answered | Autopilot: Done / NeedsUser / limit / near repeat | Halted(reason, verdict) | SaveMode |
 * | Thinking(seq) | Answered | Autopilot: Continue, countdown > 0 | Countdown(seq, first option) | Countdown |
 * | Thinking(seq) | Answered | Autopilot: Continue, countdown = 0 | Delivering, counter + 1 | Deliver |
 * | Thinking(seq) | AskFailed | | Autopilot → Halted(ContinuerFailed); Suggest → Halted, mode kept | SaveMode |
 * | Countdown(seq) | CountdownElapsed / SendNow | | Delivering, counter + 1 | Deliver |
 * | Countdown | Edit | | Suggest, Idle | Cancel; SaveMode; Draft(text) |
 * | any | Stop | Autopilot | Halted(UserStopped) | Cancel if busy; SaveMode |
 * | Delivering(seq) | Delivered | | Idle | |
 * | Delivering(seq) | DeliveryFailed | | Halted(DeliveryFailed) | SaveMode |
 * | any | Forget | chat known | removed | Cancel; SaveMode(null) |
 *
 * Effect failures: Load → LoadFailed, saves → SaveFailed, Ask → AskFailed(Failed), Deliver → DeliveryFailed.
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
                is AutoContinueEffect.Countdown, is AutoContinueEffect.Cancel -> null
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
    chats = intent.modes.mapValues { (_, mode) ->
        if (mode == AutoContinueMode.Autopilot) {
            ChatAuto(AutoContinueMode.Suggest, Phase.Halted(HaltReason.Restarted))
        } else {
            ChatAuto(mode)
        }
    },
)
