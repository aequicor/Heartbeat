package io.aequicor.heartbeat.core.statemachine

import kotlinx.serialization.Serializable

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

val ChatMachineSpec = machineSpec(ChatMachineKey, initial = ChatState.Idle) {
    state<ChatState.Idle> {
        on<ChatIntent.Public.Open> {
            goto<ChatState.Loading> { ChatState.Loading(intent.chatId) }
            effect { ChatEffect.Load(intent.chatId) }
        }
    }
    state<ChatState.Loading> {
        on<ChatIntent.Internal.Loaded> { goto<ChatState.Ready> { ChatState.Ready(intent.chatId) } }
        on<ChatIntent.Internal.Failed> { goto<ChatState.Error> { ChatState.Error(intent.reason) } }
    }
    state<ChatState.Ready> {
        on<ChatIntent.Public.SendPrompt>(guard = { intent.text.isNotBlank() }) {
            goto<ChatState.Generating> { ChatState.Generating(state.chatId) }
            effect { ChatEffect.Generate(intent.text) }
        }
        on<ChatIntent.Internal.Draft> { stay { state.copy(draft = intent.text) } }
    }
    state<ChatState.Generating> {
        on<ChatIntent.Internal.Completed> {
            goto<ChatState.Ready> { ChatState.Ready(state.chatId) }
            output { ChatOutput.Generated }
        }
        on<ChatIntent.Public.Cancel> { goto<ChatState.Ready> { ChatState.Ready(state.chatId) } }
        on<ChatIntent.Internal.Failed> { goto<ChatState.Error> { ChatState.Error(intent.reason) } }
    }
    state<ChatState.Error>()
    any { on<ChatIntent.Public.Reset> { goto<ChatState.Idle> { ChatState.Idle } } }
    onEffectFailure { _, error -> ChatIntent.Internal.Failed(error.message.orEmpty()) }
}
