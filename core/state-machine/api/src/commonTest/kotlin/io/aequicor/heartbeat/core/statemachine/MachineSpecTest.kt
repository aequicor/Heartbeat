package io.aequicor.heartbeat.core.statemachine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MachineSpecTest {

    @Test
    fun `goto moves to the target state with effects`() {
        val resolution = ChatMachineSpec.assertTransition(
            from = ChatState.Idle,
            intent = ChatIntent.Public.Open("c1"),
            to = ChatState.Loading("c1"),
            effects = listOf(ChatEffect.Load("c1")),
        )
        assertTrue(resolution.isStateChange)
    }

    @Test
    fun `stay updates data without leaving the state`() {
        val resolution = ChatMachineSpec.assertTransition(
            from = ChatState.Ready("c1"),
            intent = ChatIntent.Internal.Draft("hi"),
            to = ChatState.Ready("c1", draft = "hi"),
        )
        assertFalse(resolution.isStateChange)
    }

    @Test
    fun `outputs are produced by the transition`() {
        ChatMachineSpec.assertTransition(
            from = ChatState.Generating("c1"),
            intent = ChatIntent.Internal.Completed,
            to = ChatState.Ready("c1"),
            outputs = listOf(ChatOutput.Generated),
        )
    }

    @Test
    fun `guard rejects the intent`() {
        ChatMachineSpec.assertIgnored(ChatState.Ready("c1"), ChatIntent.Public.SendPrompt(" "))
    }

    @Test
    fun `intent without a transition in the state is ignored`() {
        ChatMachineSpec.assertIgnored(ChatState.Idle, ChatIntent.Public.SendPrompt("hi"))
        ChatMachineSpec.assertIgnored(ChatState.Error("x"), ChatIntent.Public.Open("c1"))
    }

    @Test
    fun `any transition applies in every state`() {
        listOf(ChatState.Idle, ChatState.Loading("c1"), ChatState.Error("x")).forEach { state ->
            ChatMachineSpec.assertTransition(from = state, intent = ChatIntent.Public.Reset, to = ChatState.Idle)
        }
    }

    @Test
    fun `state transition takes precedence over any`() {
        val spec = machineSpec(ChatMachineKey, initial = ChatState.Idle) {
            state<ChatState.Idle> { on<ChatIntent.Public.Reset> { goto<ChatState.Error> { ChatState.Error("own") } } }
            state<ChatState.Error>()
            any { on<ChatIntent.Public.Reset> { goto<ChatState.Idle> { ChatState.Idle } } }
        }
        spec.assertTransition(from = ChatState.Idle, intent = ChatIntent.Public.Reset, to = ChatState.Error("own"))
        spec.assertTransition(from = ChatState.Error("x"), intent = ChatIntent.Public.Reset, to = ChatState.Idle)
    }

    @Test
    fun `ambiguous transitions fail`() {
        val spec = machineSpec(ChatMachineKey, initial = ChatState.Idle) {
            state<ChatState.Idle> {
                on<ChatIntent.Public.Open> { goto<ChatState.Error> { ChatState.Error("a") } }
                on<ChatIntent.Public> { goto<ChatState.Error> { ChatState.Error("b") } }
            }
            state<ChatState.Error>()
        }
        assertFailsWith<IllegalStateException> { spec.resolve(ChatState.Idle, ChatIntent.Public.Open("c1")) }
        assertEquals(ChatState.Error("b"), spec.resolve(ChatState.Idle, ChatIntent.Public.Cancel)?.to)
    }

    @Test
    fun `undeclared target fails on build`() {
        assertFailsWith<IllegalStateException> {
            machineSpec(ChatMachineKey, initial = ChatState.Idle) {
                state<ChatState.Idle> { on<ChatIntent.Public.Open> { goto<ChatState.Error> { ChatState.Error("x") } } }
            }
        }
    }

    @Test
    fun `undeclared initial and duplicate states fail on build`() {
        assertFailsWith<IllegalStateException> {
            machineSpec(ChatMachineKey, initial = ChatState.Idle) { state<ChatState.Error>() }
        }
        assertFailsWith<IllegalStateException> {
            machineSpec(ChatMachineKey, initial = ChatState.Idle) {
                state<ChatState.Idle>()
                state<ChatState.Idle>()
            }
        }
    }

    @Test
    fun `two goto in one transition fail on build`() {
        assertFailsWith<IllegalStateException> {
            machineSpec(ChatMachineKey, initial = ChatState.Idle) {
                state<ChatState.Idle> {
                    on<ChatIntent.Public.Open> {
                        goto<ChatState.Idle> { ChatState.Idle }
                        stay { state }
                    }
                }
            }
        }
    }

    @Test
    fun `undeclared current state fails on resolve`() {
        assertFailsWith<IllegalStateException> {
            machineSpec(ChatMachineKey, initial = ChatState.Idle) { state<ChatState.Idle>() }
                .resolve(ChatState.Error("x"), ChatIntent.Public.Reset)
        }
    }

    @Test
    fun `transition without goto or stay keeps the state`() {
        val spec = machineSpec(ChatMachineKey, initial = ChatState.Idle) {
            state<ChatState.Idle> { on<ChatIntent.Public.Cancel>() }
        }
        val resolution = spec.assertTransition(ChatState.Idle, ChatIntent.Public.Cancel, to = ChatState.Idle)
        assertFalse(resolution.isStateChange)
        assertNull(spec.onEffectFailure(ChatEffect.Load("c1"), IllegalStateException()))
    }

    @Test
    fun `effect failure maps to an intent`() {
        assertEquals(
            ChatIntent.Internal.Failed("boom"),
            ChatMachineSpec.onEffectFailure(ChatEffect.Load("c1"), IllegalStateException("boom")),
        )
    }

    @Test
    fun `assertTransition reports a wrong state`() {
        assertFailsWith<AssertionError> {
            ChatMachineSpec.assertTransition(ChatState.Idle, ChatIntent.Public.Open("c1"), to = ChatState.Idle)
        }
        assertFailsWith<AssertionError> {
            ChatMachineSpec.assertIgnored(ChatState.Idle, ChatIntent.Public.Open("c1"))
        }
    }

    @Test
    fun `descriptors describe the graph`() {
        val described = ChatMachineSpec.transitions.map { it.describe() }
        assertEquals("Idle --Open--> Loading", described.first())
        assertTrue("Ready --Draft--> (stay)" in described)
        assertEquals("* --Reset--> Idle", described.last())
        assertEquals(described.indices.toList(), ChatMachineSpec.transitions.map { it.id }.sorted())
    }

    @Test
    fun `mermaid export lists states and transitions`() {
        val mermaid = ChatMachineSpec.toMermaid()
        assertTrue(mermaid.startsWith("stateDiagram-v2"))
        assertTrue("[*] --> Idle" in mermaid)
        assertTrue("Ready --> Generating : SendPrompt [guard]" in mermaid)
        assertTrue("Ready --> Ready : Draft (stay)" in mermaid)
        assertTrue("Error --> Idle : Reset" in mermaid)
    }

    @Test
    fun `restore maps saved states and relaunches effects`() {
        val spec = machineSpec(ChatMachineKey, initial = ChatState.Idle) {
            state<ChatState.Idle>()
            state<ChatState.Loading>()
            state<ChatState.Ready>()
            state<ChatState.Generating>()
            persist(ChatState.serializer()) { saved ->
                when (saved) {
                    is ChatState.Loading -> restore(saved, ChatEffect.Load(saved.chatId))
                    is ChatState.Generating -> restore(ChatState.Ready(saved.chatId))
                    else -> restore(saved)
                }
            }
        }

        assertEquals(listOf(ChatEffect.Load("c1")), spec.restore(ChatState.Loading("c1")).effects)
        assertEquals(ChatState.Ready("c1"), spec.restore(ChatState.Generating("c1")).state)
        assertEquals(ChatState.Idle, spec.restore(ChatState.Idle).state)
        // Error is not declared any more: start over
        assertEquals(ChatState.Idle, spec.restore(ChatState.Error("x")).state)
    }

    @Test
    fun `restore without mapping keeps the saved state`() {
        val restoration = ChatMachineSpec.restore(ChatState.Ready("c1", draft = "d"))

        assertEquals(ChatState.Ready("c1", draft = "d"), restoration.state)
        assertTrue(restoration.effects.isEmpty())
    }
}
