package io.aequicor.heartbeat.feature.autocontinue.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueIntent.Internal
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueIntent.Public
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AutoContinueMachineTest {
    private val spec = AutoContinueMachineSpec
    private val route = EngineTarget(EngineId("codex"), EngineBindingId("default"), ModelId("gpt"))
    private val continuer = route.copy(model = ModelId("small"))
    private val context = ContinuationContext(route, listOf(DialogEntry(DialogAuthor.Human, "Сделай фичу")))
    private val option = ContinuationOption("Дальше", "Продолжай со шага 3 плана")
    private val proceed = ContinuationVerdict(ContinuationStatus.Continue, "plan open", listOf(option))
    private val done = ContinuationVerdict(ContinuationStatus.Done, "all steps done")

    private fun ready(chat: ChatAuto, settings: AutoContinueSettings = AutoContinueSettings()) =
        AutoContinueState.Ready(settings, mapOf(CHAT to chat))

    private fun ask(seq: Long, mode: AutoContinueMode, target: EngineTarget = route) =
        AutoContinueEffect.Ask(CHAT, seq, target, context, mode, AutoContinueSettings.MAX_OPTIONS)

    private fun saveMode(mode: AutoContinueMode?) = AutoContinueEffect.SaveMode(CHAT, mode)

    @Test
    fun `start loads and restart turns autopilot into suggestions`() {
        spec.assertTransition(
            AutoContinueState.Idle,
            Public.Start,
            AutoContinueState.Loading,
            effects = listOf(AutoContinueEffect.Load),
        )
        val settings = AutoContinueSettings(defaultMode = AutoContinueMode.Suggest)
        spec.assertTransition(
            AutoContinueState.Loading,
            Internal.Loaded(settings, mapOf(CHAT to AutoContinueMode.Autopilot, "b" to AutoContinueMode.Off)),
            AutoContinueState.Ready(
                settings,
                mapOf(
                    CHAT to ChatAuto(AutoContinueMode.Suggest, Phase.Halted(HaltReason.Restarted)),
                    "b" to ChatAuto(AutoContinueMode.Off),
                ),
            ),
        )
        spec.assertTransition(AutoContinueState.Loading, Internal.LoadFailed, AutoContinueState.Ready())
        spec.assertIgnored(AutoContinueState.Loading, Public.SetMode(CHAT, AutoContinueMode.Suggest))
    }

    @Test
    fun `settings are saved only when changed`() {
        val settings = AutoContinueSettings(countdownSeconds = 0)
        spec.assertTransition(
            AutoContinueState.Ready(),
            Public.UpdateSettings(settings),
            AutoContinueState.Ready(settings),
            effects = listOf(AutoContinueEffect.SaveSettings(settings)),
        )
        spec.assertIgnored(AutoContinueState.Ready(), Public.UpdateSettings(AutoContinueSettings()))
        spec.assertTransition(
            AutoContinueState.Ready(),
            Internal.SaveFailed,
            AutoContinueState.Ready(),
            outputs = listOf(AutoContinueOutput.SaveFailed),
        )
        assertFailsWith<IllegalArgumentException> { AutoContinueSettings(defaultMode = AutoContinueMode.Autopilot) }
    }

    @Test
    fun `a new chat follows the default mode and off only keeps the settled context`() {
        val suggesting = AutoContinueState.Ready(AutoContinueSettings(defaultMode = AutoContinueMode.Suggest))
        spec.assertTransition(
            suggesting,
            Public.TurnSettled(CHAT, context),
            suggesting.copy(
                chats = mapOf(
                    CHAT to ChatAuto(AutoContinueMode.Suggest, Phase.Thinking(1), context = context, seq = 1),
                ),
            ),
            effects = listOf(ask(1, AutoContinueMode.Suggest)),
        )
        val off = AutoContinueState.Ready()
        val kept = ready(ChatAuto(AutoContinueMode.Off, context = context))
        spec.assertTransition(off, Public.TurnSettled(CHAT, context), kept)
        spec.assertTransition(kept, Public.TurnStarted(CHAT, isHuman = true), ready(ChatAuto(AutoContinueMode.Off)))
        spec.assertTransition(kept, Public.TurnAwaitsHuman(CHAT), ready(ChatAuto(AutoContinueMode.Off)))
        spec.assertIgnored(off, Public.TurnStarted(CHAT, isHuman = true))
        spec.assertIgnored(off, Public.TurnFailed(CHAT, isStoppedByUser = false))
        spec.assertIgnored(off, Public.DraftTyped(CHAT))
    }

    @Test
    fun `switching on a settled chat asks at once and the continuer route wins`() {
        val settled = ChatAuto(AutoContinueMode.Off, context = null)
        spec.assertTransition(
            ready(settled),
            Public.SetMode(CHAT, AutoContinueMode.Suggest),
            ready(ChatAuto(AutoContinueMode.Suggest)),
            effects = listOf(saveMode(AutoContinueMode.Suggest)),
        )
        val offered = ChatAuto(
            AutoContinueMode.Suggest,
            Phase.Offered(proceed),
            autoTurns = 4,
            context = context,
            seq = 2,
        )
        val settings = AutoContinueSettings(continuerTarget = continuer)
        spec.assertTransition(
            ready(offered, settings),
            Public.SetMode(CHAT, AutoContinueMode.Autopilot),
            ready(ChatAuto(AutoContinueMode.Autopilot, Phase.Thinking(3), context = context, seq = 3), settings),
            effects = listOf(ask(3, AutoContinueMode.Autopilot, continuer), saveMode(AutoContinueMode.Autopilot)),
        )
        spec.assertIgnored(ready(offered), Public.SetMode(CHAT, AutoContinueMode.Suggest))
    }

    @Test
    fun `switching on after a settled turn asks about it`() {
        spec.assertTransition(
            ready(ChatAuto(AutoContinueMode.Off, context = context)),
            Public.SetMode(CHAT, AutoContinueMode.Suggest),
            ready(ChatAuto(AutoContinueMode.Suggest, Phase.Thinking(1), context = context, seq = 1)),
            effects = listOf(ask(1, AutoContinueMode.Suggest), saveMode(AutoContinueMode.Suggest)),
        )
    }

    @Test
    fun `stale deliveries and answers are ignored`() {
        val delivering = ChatAuto(AutoContinueMode.Autopilot, Phase.Delivering(2, "go"), seq = 2)
        spec.assertIgnored(ready(delivering), Internal.Delivered(CHAT, 1))
        spec.assertIgnored(ready(delivering), Internal.DeliveryFailed(CHAT, 1))
        spec.assertIgnored(ready(delivering), Internal.Answered(CHAT, 2, proceed))
        spec.assertIgnored(ready(delivering), Public.Refresh(CHAT))
        val thinking = ChatAuto(AutoContinueMode.Suggest, Phase.Thinking(3), context = context, seq = 3)
        spec.assertIgnored(ready(thinking), Internal.AskFailed(CHAT, 2, ContinuerFailure.Busy))
    }

    @Test
    fun `switching off cancels a running request`() {
        val thinking = ChatAuto(AutoContinueMode.Suggest, Phase.Thinking(1), context = context, seq = 1)
        spec.assertTransition(
            ready(thinking),
            Public.SetMode(CHAT, AutoContinueMode.Off),
            ready(ChatAuto(AutoContinueMode.Off, context = context, seq = 1)),
            effects = listOf(AutoContinueEffect.Cancel(CHAT), saveMode(AutoContinueMode.Off)),
        )
    }

    @Test
    fun `suggestions are offered and hidden by typing`() {
        val thinking = ChatAuto(AutoContinueMode.Suggest, Phase.Thinking(1), context = context, seq = 1)
        val offered = thinking.copy(phase = Phase.Offered(proceed))
        spec.assertTransition(ready(thinking), Internal.Answered(CHAT, 1, proceed), ready(offered))
        spec.assertIgnored(ready(thinking), Internal.Answered(CHAT, 0, proceed))
        spec.assertTransition(ready(offered), Public.DraftTyped(CHAT), ready(offered.copy(phase = Phase.Idle)))
        spec.assertTransition(
            ready(thinking),
            Public.DraftTyped(CHAT),
            ready(thinking.copy(phase = Phase.Idle)),
            effects = listOf(AutoContinueEffect.Cancel(CHAT)),
        )
        spec.assertTransition(
            ready(offered),
            Public.Refresh(CHAT),
            ready(offered.copy(phase = Phase.Thinking(2), seq = 2)),
            effects = listOf(ask(2, AutoContinueMode.Suggest)),
        )
        spec.assertIgnored(ready(thinking), Public.Refresh(CHAT))
        spec.assertIgnored(ready(offered.copy(context = null)), Public.Refresh(CHAT))
    }

    @Test
    fun `a failed suggestion keeps suggest mode`() {
        val thinking = ChatAuto(AutoContinueMode.Suggest, Phase.Thinking(1), context = context, seq = 1)
        spec.assertTransition(
            ready(thinking),
            Internal.AskFailed(CHAT, 1, ContinuerFailure.Timeout),
            ready(thinking.copy(phase = Phase.Halted(HaltReason.ContinuerFailed, failure = ContinuerFailure.Timeout))),
        )
    }

    @Test
    fun `a new turn drops the context and a human message resets the counter`() {
        val offered = ChatAuto(
            AutoContinueMode.Suggest,
            Phase.Offered(proceed),
            autoTurns = 2,
            context = context,
            seq = 1,
        )
        spec.assertTransition(
            ready(offered),
            Public.TurnStarted(CHAT, isHuman = true),
            ready(offered.copy(phase = Phase.Idle, autoTurns = 0, context = null)),
        )
        val delivering = ChatAuto(AutoContinueMode.Autopilot, Phase.Delivering(1, "go"), autoTurns = 3, seq = 1)
        spec.assertTransition(
            ready(delivering),
            Public.TurnStarted(CHAT, isHuman = false),
            ready(delivering.copy(phase = Phase.Idle)),
        )
    }

    @Test
    fun `autopilot counts down and delivers the first option`() {
        val thinking = ChatAuto(AutoContinueMode.Autopilot, Phase.Thinking(1), context = context, seq = 1)
        val countdown = thinking.copy(phase = Phase.Countdown(1, option.text, 5))
        spec.assertTransition(
            ready(thinking),
            Internal.Answered(CHAT, 1, proceed),
            ready(countdown),
            effects = listOf(AutoContinueEffect.Countdown(CHAT, 1, 5)),
        )
        val delivering = thinking.copy(
            phase = Phase.Delivering(1, option.text),
            autoTurns = 1,
            lastAutoText = option.text,
            context = null,
        )
        spec.assertTransition(
            ready(countdown),
            Internal.CountdownElapsed(CHAT, 1),
            ready(delivering),
            effects = listOf(AutoContinueEffect.Deliver(CHAT, 1, option.text)),
        )
        spec.assertTransition(
            ready(countdown),
            Public.SendNow(CHAT),
            ready(delivering),
            effects = listOf(AutoContinueEffect.Deliver(CHAT, 1, option.text)),
        )
        spec.assertIgnored(ready(countdown), Internal.CountdownElapsed(CHAT, 0))
        spec.assertTransition(
            ready(delivering),
            Internal.Delivered(CHAT, 1),
            ready(delivering.copy(phase = Phase.Idle)),
        )
    }

    @Test
    fun `autopilot without countdown delivers at once`() {
        val settings = AutoContinueSettings(countdownSeconds = 0)
        val thinking = ChatAuto(AutoContinueMode.Autopilot, Phase.Thinking(1), context = context, seq = 1)
        spec.assertTransition(
            ready(thinking, settings),
            Internal.Answered(CHAT, 1, proceed),
            ready(
                thinking.copy(
                    phase = Phase.Delivering(1, option.text),
                    autoTurns = 1,
                    lastAutoText = option.text,
                    context = null,
                ),
                settings,
            ),
            effects = listOf(AutoContinueEffect.Deliver(CHAT, 1, option.text)),
        )
    }

    @Test
    fun `autopilot halts on done or needs user or limit or repeats`() {
        val thinking = ChatAuto(AutoContinueMode.Autopilot, Phase.Thinking(1), context = context, seq = 1)
        fun halted(chat: ChatAuto, reason: HaltReason, verdict: ContinuationVerdict) =
            ready(chat.copy(mode = AutoContinueMode.Suggest, phase = Phase.Halted(reason, verdict)))
        spec.assertTransition(
            ready(thinking),
            Internal.Answered(CHAT, 1, done),
            halted(thinking, HaltReason.Done, done),
            effects = listOf(saveMode(AutoContinueMode.Suggest)),
        )
        val ask = ContinuationVerdict(ContinuationStatus.NeedsUser, "choose a database")
        spec.assertTransition(
            ready(thinking),
            Internal.Answered(CHAT, 1, ask),
            halted(thinking, HaltReason.NeedsUser, ask),
            effects = listOf(saveMode(AutoContinueMode.Suggest)),
        )
        val exhausted = thinking.copy(autoTurns = AutoContinueSettings.DEFAULT_AUTO_TURN_LIMIT)
        spec.assertTransition(
            ready(exhausted),
            Internal.Answered(CHAT, 1, proceed),
            halted(exhausted, HaltReason.LimitReached, proceed),
            effects = listOf(saveMode(AutoContinueMode.Suggest)),
        )
        val repeating = thinking.copy(autoTurns = 1, lastAutoText = "Продолжай со шага 3 плана!")
        spec.assertTransition(
            ready(repeating),
            Internal.Answered(CHAT, 1, proceed),
            halted(repeating, HaltReason.Looping, proceed),
            effects = listOf(saveMode(AutoContinueMode.Suggest)),
        )
    }

    @Test
    fun `autopilot halts on failures and takeover`() {
        val thinking = ChatAuto(AutoContinueMode.Autopilot, Phase.Thinking(1), context = context, seq = 1)
        spec.assertTransition(
            ready(thinking),
            Internal.AskFailed(CHAT, 1, ContinuerFailure.Unavailable),
            ready(
                thinking.copy(
                    mode = AutoContinueMode.Suggest,
                    phase = Phase.Halted(HaltReason.ContinuerFailed, failure = ContinuerFailure.Unavailable),
                ),
            ),
            effects = listOf(saveMode(AutoContinueMode.Suggest)),
        )
        val countdown = thinking.copy(phase = Phase.Countdown(1, "go", 5))
        spec.assertTransition(
            ready(countdown),
            Public.DraftTyped(CHAT),
            ready(countdown.copy(mode = AutoContinueMode.Suggest, phase = Phase.Halted(HaltReason.HumanTookOver))),
            effects = listOf(AutoContinueEffect.Cancel(CHAT), saveMode(AutoContinueMode.Suggest)),
        )
        val idle = ChatAuto(AutoContinueMode.Autopilot, autoTurns = 4, lastAutoText = "go", seq = 2)
        spec.assertTransition(
            ready(idle),
            Public.TurnStarted(CHAT, isHuman = true),
            ready(ChatAuto(AutoContinueMode.Suggest, Phase.Halted(HaltReason.HumanTookOver), seq = 2)),
            effects = listOf(saveMode(AutoContinueMode.Suggest)),
        )
        spec.assertTransition(
            ready(idle),
            Public.TurnFailed(CHAT, isStoppedByUser = true),
            ready(idle.copy(mode = AutoContinueMode.Suggest, phase = Phase.Halted(HaltReason.UserStopped))),
            effects = listOf(saveMode(AutoContinueMode.Suggest)),
        )
        spec.assertTransition(
            ready(idle),
            Public.TurnFailed(CHAT, isStoppedByUser = false),
            ready(idle.copy(mode = AutoContinueMode.Suggest, phase = Phase.Halted(HaltReason.TurnFailed))),
            effects = listOf(saveMode(AutoContinueMode.Suggest)),
        )
        val delivering = idle.copy(phase = Phase.Delivering(3, "go"))
        spec.assertTransition(
            ready(delivering),
            Internal.DeliveryFailed(CHAT, 3),
            ready(delivering.copy(mode = AutoContinueMode.Suggest, phase = Phase.Halted(HaltReason.DeliveryFailed))),
            effects = listOf(saveMode(AutoContinueMode.Suggest)),
        )
    }

    @Test
    fun `autopilot pauses while the agent waits for the human`() {
        val idle = ChatAuto(AutoContinueMode.Autopilot, autoTurns = 2, seq = 1)
        spec.assertTransition(ready(idle), Public.TurnAwaitsHuman(CHAT), ready(idle.copy(phase = Phase.Paused)))
        val paused = idle.copy(phase = Phase.Paused)
        spec.assertTransition(
            ready(paused),
            Public.TurnSettled(CHAT, context),
            ready(paused.copy(phase = Phase.Thinking(2), context = context, seq = 2)),
            effects = listOf(ask(2, AutoContinueMode.Autopilot)),
        )
        spec.assertIgnored(ready(paused), Public.DraftTyped(CHAT))
    }

    @Test
    fun `edit and stop hand the chat back to the human`() {
        val countdown = ChatAuto(AutoContinueMode.Autopilot, Phase.Countdown(1, "go", 5), context = context, seq = 1)
        spec.assertTransition(
            ready(countdown),
            Public.Edit(CHAT),
            ready(countdown.copy(mode = AutoContinueMode.Suggest, phase = Phase.Idle)),
            effects = listOf(AutoContinueEffect.Cancel(CHAT), saveMode(AutoContinueMode.Suggest)),
            outputs = listOf(AutoContinueOutput.Draft(CHAT, "go")),
        )
        spec.assertTransition(
            ready(countdown),
            Public.Stop(CHAT),
            ready(countdown.copy(mode = AutoContinueMode.Suggest, phase = Phase.Halted(HaltReason.UserStopped))),
            effects = listOf(AutoContinueEffect.Cancel(CHAT), saveMode(AutoContinueMode.Suggest)),
        )
        spec.assertIgnored(ready(ChatAuto(AutoContinueMode.Suggest)), Public.Stop(CHAT))
        spec.assertIgnored(ready(ChatAuto(AutoContinueMode.Autopilot)), Public.Edit(CHAT))
    }

    @Test
    fun `forget removes a known chat`() {
        spec.assertTransition(
            ready(ChatAuto(AutoContinueMode.Suggest)),
            Public.Forget(CHAT),
            AutoContinueState.Ready(),
            effects = listOf(AutoContinueEffect.Cancel(CHAT), saveMode(null)),
        )
        spec.assertIgnored(AutoContinueState.Ready(), Public.Forget(CHAT))
    }

    private companion object {
        const val CHAT = "chat-1"
    }
}
