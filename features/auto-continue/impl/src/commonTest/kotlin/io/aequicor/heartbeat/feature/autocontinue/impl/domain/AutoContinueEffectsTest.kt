package io.aequicor.heartbeat.feature.autocontinue.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPromptFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueEffect
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueHost
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueIntent
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueIntent.Internal
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueMode
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueSettings
import io.aequicor.heartbeat.feature.autocontinue.api.ContinuationContext
import io.aequicor.heartbeat.feature.autocontinue.api.ContinuationOption
import io.aequicor.heartbeat.feature.autocontinue.api.ContinuationStatus
import io.aequicor.heartbeat.feature.autocontinue.api.ContinuationVerdict
import io.aequicor.heartbeat.feature.autocontinue.api.ContinuerFailure
import io.aequicor.heartbeat.feature.autocontinue.api.DialogAuthor
import io.aequicor.heartbeat.feature.autocontinue.api.DialogEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class AutoContinueEffectsTest {
    private val target = EngineTarget(EngineId("pi"), EngineBindingId("b"), ModelId("m"))
    private val context = ContinuationContext(target, listOf(DialogEntry(DialogAuthor.Human, "Сделай фичу")))
    private val ask = AutoContinueEffect.Ask(CHAT, 1, target, context, AutoContinueMode.Suggest, optionCount = 3)
    private val valid = """{"status":"continue","reason":"r","options":[{"label":"Дальше","text":"Продолжай"}]}"""
    private val verdict =
        ContinuationVerdict(ContinuationStatus.Continue, "r", listOf(ContinuationOption("Дальше", "Продолжай")))

    @Test
    fun `settings and modes round trip through the store`() = runTest {
        val store = MemoryStore()
        val machine = RecordingScope()
        val effects = effects(store, prompts = ScriptedPrompts())
        val settings = AutoContinueSettings(defaultMode = AutoContinueMode.Suggest, countdownSeconds = 0)
        effects.handle(AutoContinueEffect.SaveSettings(settings), machine)
        effects.handle(AutoContinueEffect.SaveMode(CHAT, AutoContinueMode.Autopilot), machine)
        effects.handle(AutoContinueEffect.SaveMode("b", AutoContinueMode.Off), machine)
        effects.handle(AutoContinueEffect.SaveMode("b", null), machine)
        effects.handle(AutoContinueEffect.Load, machine)
        assertEquals(
            listOf<AutoContinueIntent>(Internal.Loaded(settings, mapOf(CHAT to AutoContinueMode.Autopilot))),
            machine.sent,
        )
    }

    @Test
    fun `an invalid answer is retried once`() = runTest {
        val prompts = ScriptedPrompts(IsolatedAnswer.Answered("sure!"), IsolatedAnswer.Answered(valid))
        val machine = RecordingScope()
        effects(prompts = prompts).handle(ask, machine)
        assertEquals(listOf<AutoContinueIntent>(Internal.Answered(CHAT, 1, verdict, NOW)), machine.sent)
        assertEquals(2, prompts.asked.size)
        assertTrue("not a valid JSON object" in prompts.asked[1].text)
        assertEquals(target, prompts.asked[0].target)
    }

    @Test
    fun `two invalid answers and an unavailable engine fail the request`() = runTest {
        val machine = RecordingScope()
        effects(
            prompts = ScriptedPrompts(IsolatedAnswer.Answered("a"), IsolatedAnswer.Answered("b")),
        ).handle(ask, machine)
        effects(prompts = ScriptedPrompts(IsolatedAnswer.Unavailable(IsolatedPromptFailure.Busy))).handle(ask, machine)
        effects(prompts = ScriptedPrompts(), isEnabled = false).handle(ask, machine)
        assertEquals(
            listOf<AutoContinueIntent>(
                Internal.AskFailed(CHAT, 1, ContinuerFailure.InvalidAnswer),
                Internal.AskFailed(CHAT, 1, ContinuerFailure.Busy),
                Internal.AskFailed(CHAT, 1, ContinuerFailure.Unavailable),
            ),
            machine.sent,
        )
    }

    @Test
    fun `countdown elapses after its seconds`() = runTest {
        val machine = RecordingScope()
        val effects = effects(prompts = ScriptedPrompts())
        launch { effects.handle(AutoContinueEffect.Countdown(CHAT, 4, seconds = 5), machine) }
        advanceTimeBy(4_999)
        assertEquals(emptyList(), machine.sent)
        advanceUntilIdle()
        assertEquals(listOf<AutoContinueIntent>(Internal.CountdownElapsed(CHAT, 4)), machine.sent)
    }

    @Test
    fun `cancel stops the running job of the chat`() = runTest {
        val gate = CompletableDeferred<IsolatedAnswer>()
        val prompts = ScriptedPrompts(gate = gate)
        val machine = RecordingScope()
        val effects = effects(prompts = prompts)
        val running = launch { effects.handle(ask, machine) }
        runCurrent()
        effects.handle(AutoContinueEffect.Cancel(CHAT), machine)
        advanceUntilIdle()
        assertTrue(running.isCompleted)
        assertEquals(emptyList(), machine.sent)
    }

    @Test
    fun `a new job replaces the previous one`() = runTest {
        val machine = RecordingScope()
        val effects = effects(prompts = ScriptedPrompts())
        launch { effects.handle(AutoContinueEffect.Countdown(CHAT, 1, seconds = 5), machine) }
        runCurrent()
        launch { effects.handle(AutoContinueEffect.Countdown(CHAT, 2, seconds = 5), machine) }
        advanceUntilIdle()
        assertEquals(listOf<AutoContinueIntent>(Internal.CountdownElapsed(CHAT, 2)), machine.sent)
    }

    @Test
    fun `delivery goes to the host and fails without one`() = runTest {
        val sent = mutableListOf<Pair<String, String>>()
        val host = AutoContinueHost { chat, text ->
            sent += chat to text
            true
        }
        val machine = RecordingScope()
        effects(
            prompts = ScriptedPrompts(),
            hosts = setOf(host),
        ).handle(AutoContinueEffect.Deliver(CHAT, 3, "go"), machine)
        effects(
            prompts = ScriptedPrompts(),
            hosts = emptySet(),
        ).handle(AutoContinueEffect.Deliver(CHAT, 4, "go"), machine)
        effects(prompts = ScriptedPrompts(), hosts = setOf(AutoContinueHost { _, _ -> false }))
            .handle(AutoContinueEffect.Deliver(CHAT, 5, "go"), machine)
        assertEquals(listOf(CHAT to "go"), sent)
        assertEquals(
            listOf<AutoContinueIntent>(
                Internal.Delivered(CHAT, 3),
                Internal.DeliveryFailed(CHAT, 4),
                Internal.DeliveryFailed(CHAT, 5),
            ),
            machine.sent,
        )
    }

    @Test
    fun `a job cancelled from inside fails its request`() = runTest {
        val machine = RecordingScope()
        val prompts = IsolatedPrompts { throw kotlinx.coroutines.CancellationException("lost") }
        effects(prompts = prompts).handle(ask, machine)
        assertEquals(listOf<AutoContinueIntent>(Internal.AskFailed(CHAT, 1, ContinuerFailure.Failed)), machine.sent)
    }

    @Test
    fun `nothing is delivered while auto-continue is off`() = runTest {
        val sent = mutableListOf<String>()
        val machine = RecordingScope()
        effects(
            prompts = ScriptedPrompts(),
            hosts = setOf(
                AutoContinueHost { chat, _ ->
                    sent += chat
                    true
                },
            ),
            isEnabled = false,
        ).handle(AutoContinueEffect.Deliver(CHAT, 2, "go"), machine)
        assertTrue(sent.isEmpty())
        assertEquals(listOf<AutoContinueIntent>(Internal.DeliveryFailed(CHAT, 2)), machine.sent)
    }

    @Test
    fun `stored autopilot chats are loaded as stopped and saved as suggest chats`() = runTest {
        val store = MemoryStore()
        store.saveMode(CHAT, AutoContinueMode.Autopilot)
        store.saveMode("b", AutoContinueMode.Suggest)
        val machine = RecordingScope()
        effects(store, prompts = ScriptedPrompts()).handle(AutoContinueEffect.Load, machine)
        assertEquals(
            listOf<AutoContinueIntent>(
                Internal.Loaded(
                    AutoContinueSettings(),
                    mapOf(CHAT to AutoContinueMode.Autopilot, "b" to AutoContinueMode.Suggest),
                ),
            ),
            machine.sent,
        )
        assertEquals(mapOf(CHAT to AutoContinueMode.Suggest, "b" to AutoContinueMode.Suggest), store.modes())
    }

    @Test
    fun `switching on asks the studio to report the last turn again`() = runTest {
        val resettled = mutableListOf<String>()
        val host = object : AutoContinueHost {
            override suspend fun deliver(chat: String, text: String) = false

            override suspend fun resettle(chat: String) {
                resettled += chat
            }
        }
        val studio = effects(prompts = ScriptedPrompts(), hosts = setOf(host))
        studio.handle(AutoContinueEffect.Resettle(CHAT), RecordingScope())
        effects(prompts = ScriptedPrompts()).handle(AutoContinueEffect.Resettle("b"), RecordingScope())
        assertEquals(listOf(CHAT), resettled)
    }

    @Test
    fun `a store that cannot reset autopilots still loads`() = runTest {
        val store = object : AutoContinueStore by MemoryStore() {
            override suspend fun modes() = mapOf(CHAT to AutoContinueMode.Autopilot)

            override suspend fun saveMode(chat: String, mode: AutoContinueMode?): Unit = error("disk full")
        }
        val machine = RecordingScope()
        effects(store, prompts = ScriptedPrompts()).handle(AutoContinueEffect.Load, machine)
        assertEquals(
            listOf<AutoContinueIntent>(
                Internal.Loaded(AutoContinueSettings(), mapOf(CHAT to AutoContinueMode.Autopilot)),
            ),
            machine.sent,
        )
    }

    private fun effects(
        store: AutoContinueStore = MemoryStore(),
        prompts: IsolatedPrompts,
        hosts: Set<AutoContinueHost> = emptySet(),
        isEnabled: Boolean = true,
    ) = AutoContinueEffects(store, prompts, { hosts }, { isEnabled }, FixedClock)

    private object FixedClock : Clock {
        override fun now(): Instant = NOW
    }

    private class ScriptedPrompts(
        vararg answers: IsolatedAnswer,
        private val gate: CompletableDeferred<IsolatedAnswer>? = null,
    ) : IsolatedPrompts {
        private val queue = answers.toMutableList()
        val asked = mutableListOf<IsolatedPrompt>()

        override suspend fun ask(prompt: IsolatedPrompt): IsolatedAnswer {
            asked += prompt
            return gate?.await() ?: queue.removeFirst()
        }
    }

    private class MemoryStore : AutoContinueStore {
        private var settings = AutoContinueSettings()
        private val modes = mutableMapOf<String, AutoContinueMode>()

        override suspend fun settings() = settings

        override suspend fun modes() = modes.toMap()

        override suspend fun saveSettings(settings: AutoContinueSettings) {
            this.settings = settings
        }

        override suspend fun saveMode(chat: String, mode: AutoContinueMode?) {
            if (mode == null) modes.remove(chat) else modes[chat] = mode
        }
    }

    private class RecordingScope : EffectScope<AutoContinueIntent> {
        val sent = mutableListOf<AutoContinueIntent>()

        override suspend fun send(intent: AutoContinueIntent): SendResult {
            sent += intent
            return SendResult.Accepted
        }
    }

    private companion object {
        const val CHAT = "chat-1"
        val NOW: Instant = Instant.fromEpochSeconds(1_000)
    }
}
