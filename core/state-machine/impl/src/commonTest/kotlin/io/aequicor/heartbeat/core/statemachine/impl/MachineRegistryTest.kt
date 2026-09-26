package io.aequicor.heartbeat.core.statemachine.impl

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.LogLevel
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.SendResult
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class MachineRegistryTest {
    private val logs = LogCapture()
    private val runtime = MachineRuntime()

    @BeforeTest
    fun setUp() = logs.install()

    @AfterTest
    fun tearDown() = Log.init(isDebug = false)

    @Test
    fun `machine is addressable while its scope is open`() = runTest {
        val registry = runtime
        val observed = registry.observe(ChatMachineKey)
        assertNull(observed.value)

        val scope = FakeScope(backgroundScope)
        val machine = runtime.launch(chatSpec(), scope, FakeEffects())
        assertSame(machine.state, registry.find(ChatMachineKey)?.state)
        assertSame(machine.state, observed.value?.state)

        scope.close()
        assertNull(registry.find(ChatMachineKey))
        assertNull(observed.value)
        assertEquals(
            listOf("registered (instances: 1)", "unregistered (instances: 0)"),
            logs.messages().filter { "registered" in it },
        )
    }

    @Test
    fun `send through the registry reaches the machine and is logged`() = runTest {
        val machine = runtime.launch(chatSpec(), FakeScope(backgroundScope), FakeEffects())

        assertEquals(SendResult.Accepted, runtime.send(ChatMachineKey, ChatIntent.Public.Open("c1")))

        assertEquals(ChatState.Loading("c1"), machine.state.value)
        assertEquals("send Open → chat", logs.messages(level = LogLevel.INFO)[1])
    }

    @Test
    fun `send to a machine that is not running`() = runTest {
        assertEquals(SendResult.NotRunning, runtime.send(ChatMachineKey, ChatIntent.Public.Open("c1")))
        assertEquals(listOf("send Open → chat: machine is not running"), logs.messages(level = LogLevel.WARNING))
    }

    @Test
    fun `latest instance is addressed and the previous one comes back`() = runTest {
        val first = runtime.launch(chatSpec(), FakeScope(backgroundScope), FakeEffects())
        val secondScope = FakeScope(backgroundScope)
        val second = runtime.launch(chatSpec(), secondScope, FakeEffects())

        assertSame(second.state, runtime.find(ChatMachineKey)?.state)
        assertEquals(
            listOf("2 instances running; the latest one is addressed"),
            logs.messages(level = LogLevel.WARNING),
        )

        secondScope.close()
        assertSame(first.state, runtime.find(ChatMachineKey)?.state)
    }

    @Test
    fun `two keys with the same name fail`() = runTest {
        val impostor = object : MachineKey<ChatState, ChatIntent, ChatIntent.Public, ChatEffect, ChatOutput> {
            override val name = "chat"
        }
        runtime.launch(chatSpec(), FakeScope(backgroundScope), FakeEffects())

        assertFailsWith<IllegalStateException> { runtime.find(impostor) }
    }

    @Test
    fun `launch in a closed scope fails`() = runTest {
        val scope = FakeScope(backgroundScope).apply { close() }

        assertFailsWith<IllegalStateException> { runtime.launch(chatSpec(), scope, EffectHandler.None) }
    }
}
