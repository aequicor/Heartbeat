package io.aequicor.heartbeat.core.statemachine.impl

import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.core.statemachine.machineSpec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MachineGraphNamesTest {
    private val runtime = MachineRuntime()

    @Test
    fun `guarded transitions with identical endpoints both process intents`() = runTest {
        val spec = machineSpec(ChatMachineKey, initial = ChatState.Idle) {
            state<ChatState.Idle> {
                on<ChatIntent.Public.Open>(guard = { intent.chatId == "a" }) {
                    goto<ChatState.Loading> { ChatState.Loading("primary") }
                }
                on<ChatIntent.Public.Open>(guard = { intent.chatId != "a" }) {
                    goto<ChatState.Loading> { ChatState.Loading("fallback") }
                }
            }
            state<ChatState.Loading>()
            any { on<ChatIntent.Public.Reset> { goto<ChatState.Idle> { ChatState.Idle } } }
        }
        val machine = runtime.launch(spec, FakeScope(backgroundScope), EffectHandler.None)

        assertEquals(SendResult.Accepted, machine.send(ChatIntent.Public.Open("a")))
        assertEquals(ChatState.Loading("primary"), machine.state.value)
        assertEquals(SendResult.Accepted, machine.send(ChatIntent.Public.Reset))
        assertEquals(SendResult.Accepted, machine.send(ChatIntent.Public.Open("b")))
        assertEquals(ChatState.Loading("fallback"), machine.state.value)
    }

    @Test
    fun `distinct leaf states with the same simple name remain separate`() = runTest {
        val spec = machineSpec(RepeatedKey, initial = RepeatedState.Download.Loading) {
            state<RepeatedState.Download.Loading> {
                on<ChatIntent.Public.Reset> {
                    goto<RepeatedState.Upload.Loading> { RepeatedState.Upload.Loading }
                }
            }
            state<RepeatedState.Upload.Loading> {
                on<ChatIntent.Public.Reset> {
                    goto<RepeatedState.Download.Loading> { RepeatedState.Download.Loading }
                }
            }
        }
        val machine = runtime.launch(spec, FakeScope(backgroundScope), EffectHandler.None)

        assertEquals(RepeatedState.Download.Loading, machine.state.value)
        assertEquals(SendResult.Accepted, machine.send(ChatIntent.Public.Reset))
        assertEquals(RepeatedState.Upload.Loading, machine.state.value)
        assertEquals(SendResult.Accepted, machine.send(ChatIntent.Public.Reset))
        assertEquals(RepeatedState.Download.Loading, machine.state.value)
    }

    @Test
    fun `machine name may equal a state simple name`() = runTest {
        val key = object : MachineKey<ChatState, ChatIntent, ChatIntent.Public, ChatEffect, ChatOutput> {
            override val name = "Idle"
        }
        val spec = machineSpec(key, initial = ChatState.Idle) {
            state<ChatState.Idle> {
                on<ChatIntent.Public.Open> { goto<ChatState.Loading> { ChatState.Loading(intent.chatId) } }
            }
            state<ChatState.Loading>()
        }
        val machine = runtime.launch(spec, FakeScope(backgroundScope), EffectHandler.None)

        assertEquals("Idle", machine.name)
        assertEquals(ChatState.Idle, machine.state.value)
        assertEquals(SendResult.Accepted, machine.send(ChatIntent.Public.Open("c1")))
        assertEquals(ChatState.Loading("c1"), machine.state.value)
    }

    private object RepeatedKey :
        MachineKey<RepeatedState, ChatIntent, ChatIntent.Public, ChatEffect, ChatOutput> {
        override val name = "repeated-states"
    }

    private sealed interface RepeatedState : MachineState {
        sealed interface Download : RepeatedState {
            data object Loading : Download
        }

        sealed interface Upload : RepeatedState {
            data object Loading : Upload
        }
    }
}
