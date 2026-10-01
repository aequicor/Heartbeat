package io.aequicor.heartbeat.core.statemachine.impl

import io.aequicor.heartbeat.core.di.SavedBundle
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.LogLevel
import io.aequicor.heartbeat.core.logging.LogSink
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.machineSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object ChatMachineKey : MachineKey<ChatState, ChatIntent, ChatIntent.Public, ChatEffect, ChatOutput> {
    override val name = "chat"
}

@Serializable
sealed interface ChatState : MachineState {
    @Serializable
    data object Idle : ChatState

    @Serializable
    data class Loading(val chatId: String) : ChatState

    @Serializable
    data class Ready(val chatId: String, val draft: String = "") : ChatState

    @Serializable
    data class Generating(val chatId: String) : ChatState

    @Serializable
    data class Error(val reason: String) : ChatState
}

sealed interface ChatIntent : MachineIntent {
    sealed interface Public : ChatIntent {
        data class Open(val chatId: String) : Public
        data class SendPrompt(val text: String) : Public
        data object Cancel : Public
        data object Reset : Public
    }

    sealed interface Internal : ChatIntent {
        data class Loaded(val chatId: String) : Internal
        data class Draft(val text: String) : Internal
        data object Completed : Internal
        data class Failed(val reason: String) : Internal
    }
}

sealed interface ChatEffect : MachineEffect {
    data class Load(val chatId: String) : ChatEffect
    data class Generate(val text: String) : ChatEffect
}

sealed interface ChatOutput : MachineOutput {
    data object Generated : ChatOutput
}

fun chatSpec(persist: Boolean = false) = machineSpec(ChatMachineKey, initial = ChatState.Idle) {
    state<ChatState.Idle> {
        on<ChatIntent.Public.Open> {
            goto<ChatState.Loading> { ChatState.Loading(intent.chatId) }
            effect { ChatEffect.Load(intent.chatId) }
        }
    }
    state<ChatState.Loading> {
        on<ChatIntent.Public.Open> {
            goto<ChatState.Loading> { ChatState.Loading(intent.chatId) }
            effect { ChatEffect.Load(intent.chatId) }
        }
        on<ChatIntent.Internal.Loaded> { goto<ChatState.Ready> { ChatState.Ready(intent.chatId) } }
        on<ChatIntent.Internal.Failed> { goto<ChatState.Error> { ChatState.Error(intent.reason) } }
    }
    state<ChatState.Ready> {
        on<ChatIntent.Public.SendPrompt> {
            goto<ChatState.Generating> { ChatState.Generating(state.chatId) }
            effect { ChatEffect.Generate(intent.text) }
        }
    }
    state<ChatState.Generating> {
        on<ChatIntent.Internal.Draft> { stay { state } }
        on<ChatIntent.Internal.Completed> {
            goto<ChatState.Ready> { ChatState.Ready(state.chatId) }
            output { ChatOutput.Generated }
        }
        on<ChatIntent.Public.Cancel> { goto<ChatState.Ready> { ChatState.Ready(state.chatId) } }
    }
    state<ChatState.Error>()
    any { on<ChatIntent.Public.Reset> { goto<ChatState.Idle> { ChatState.Idle } } }
    onEffectFailure { _, error -> ChatIntent.Internal.Failed(error.message.orEmpty()) }
    if (persist) {
        persist(ChatState.serializer()) { saved ->
            when (saved) {
                is ChatState.Loading -> restore(saved, ChatEffect.Load(saved.chatId))
                is ChatState.Generating -> restore(ChatState.Ready(saved.chatId))
                else -> restore(saved)
            }
        }
    }
}

/** Effects controlled by the test: each effect waits for its answer. */
class FakeEffects : EffectHandler<ChatEffect, ChatIntent> {
    val loads = mutableMapOf<String, CompletableDeferred<ChatIntent>>()
    val generation = CompletableDeferred<Unit>()
    var generationScope: EffectScope<ChatIntent>? = null

    override suspend fun handle(effect: ChatEffect, machine: EffectScope<ChatIntent>) {
        when (effect) {
            is ChatEffect.Load -> machine.send(loads.getOrPut(effect.chatId) { CompletableDeferred() }.await())

            is ChatEffect.Generate -> {
                generationScope = machine
                generation.await()
                machine.send(ChatIntent.Internal.Completed)
            }
        }
    }
}

/**
 * Minimal feature scope: coroutines are children of [parent], close actions run in reverse order.
 * With `backgroundScope` as the parent, drive coroutines with `runCurrent()`:
 * `advanceUntilIdle()` skips background work.
 */
class FakeScope(
    parent: CoroutineScope,
    override val savedState: FakeSavedState = FakeSavedState(),
    override val name: String = "app/profile/chat",
) : ScopeHandle {
    override val coroutineScope: CoroutineScope =
        CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val actions = mutableListOf<() -> Unit>()
    override var isClosed: Boolean = false
        private set

    override fun onClose(action: () -> Unit): DisposableHandle {
        if (isClosed) action() else actions += action
        return DisposableHandle { actions -= action }
    }

    fun close() {
        if (isClosed) return
        isClosed = true
        coroutineScope.cancel()
        actions.asReversed().toList().forEach { it() }
    }
}

/** Saved state that round-trips through JSON, like the real one. */
class FakeSavedState(restored: SavedBundle? = null) : ScopeSavedState {
    private val values = restored?.entries?.toMutableMap() ?: mutableMapOf()
    private val suppliers = mutableMapOf<String, () -> String?>()

    override fun <T : Any> consume(key: String, serializer: KSerializer<T>): T? =
        values.remove(key)?.let { Json.decodeFromString(serializer, it) }

    override fun <T : Any> register(key: String, serializer: KSerializer<T>, supplier: () -> T?) {
        suppliers[key] = { supplier()?.let { Json.encodeToString(serializer, it) } }
    }

    override fun unregister(key: String) {
        suppliers -= key
    }

    override fun snapshot(): SavedBundle =
        SavedBundle(values + suppliers.mapNotNull { (key, supplier) -> supplier()?.let { key to it } })
}

data class Record(val level: LogLevel, val tag: String, val error: Throwable?, val message: String)

/** Captures log records of the test. */
class LogCapture {
    val records = mutableListOf<Record>()

    fun install(isTrace: Boolean = false) {
        val sink = LogSink { level, tag, error, message -> records += Record(level, tag, error, message) }
        Log.init(isDebug = true, isTrace = isTrace, sinks = listOf(sink))
    }

    /** Messages of [tag] with exactly [level], or of every level except the engine's VERBOSE trace. */
    fun messages(tag: String = "SM/chat", level: LogLevel? = null): List<String> =
        records.filter { it.tag == tag && (if (level == null) it.level != LogLevel.VERBOSE else it.level == level) }
            .map { it.message }
}
