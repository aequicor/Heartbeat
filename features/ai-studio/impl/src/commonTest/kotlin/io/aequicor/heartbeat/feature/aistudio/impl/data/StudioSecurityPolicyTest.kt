package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityIntent
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityLevel
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityOutput
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityState
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSecurityMachine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class StudioSecurityPolicyTest {
    @Test
    fun `turn waits for disk and observes only accepted policy changes`() = runTest {
        val machine = SecurityPolicyMachine()
        val policy = MachineStudioSecurityPolicy(machine)
        val seen = mutableListOf<StudioSecurityLevel>()
        backgroundScope.launch { policy.levels.collect { seen += it } }
        val waiting = async { policy.level() }
        runCurrent()
        assertFalse(waiting.isCompleted)
        machine.state.value = StudioSecurityState.Ready(StudioSecurityLevel.Ask)
        assertEquals(StudioSecurityLevel.Ask, waiting.await())

        machine.state.value = StudioSecurityState.Saving(StudioSecurityLevel.Ask, StudioSecurityLevel.Trust)
        runCurrent()
        assertEquals(StudioSecurityLevel.Ask, policy.level())
        machine.state.value = StudioSecurityState.Ready(StudioSecurityLevel.Ask, isSaveFailed = true)
        runCurrent()
        assertEquals(listOf(StudioSecurityLevel.Ask), seen)

        machine.state.value = StudioSecurityState.Ready(StudioSecurityLevel.Trust)
        runCurrent()
        assertEquals(listOf(StudioSecurityLevel.Ask, StudioSecurityLevel.Trust), seen)
    }

    @Test
    fun `unreadable profile never inherits trust from another lifetime`() = runTest {
        val first = SecurityPolicyMachine().apply { state.value = StudioSecurityState.Ready(StudioSecurityLevel.Trust) }
        assertEquals(StudioSecurityLevel.Trust, MachineStudioSecurityPolicy(first).level())
        val second = SecurityPolicyMachine().apply { state.value = StudioSecurityState.LoadError }
        assertEquals(StudioSecurityLevel.Ask, MachineStudioSecurityPolicy(second).level())
        second.state.value = StudioSecurityState.Loading
        val restored = async { MachineStudioSecurityPolicy(second).level() }
        runCurrent()
        assertFalse(restored.isCompleted)
        second.state.value = StudioSecurityState.Ready(StudioSecurityLevel.Automatic)
        assertEquals(StudioSecurityLevel.Automatic, restored.await())
    }

    @Test
    fun `settings survive recreation stay in their profile and failed saves keep the previous value`() = runTest {
        val firstProfile = ChecklistTestStores()
        val secondProfile = ChecklistTestStores()
        val first = KeyValueStudioSecurityStore(firstProfile)
        assertNull(first.load())
        first.save(StudioSecurityLevel.Ask)
        assertEquals(StudioSecurityLevel.Ask, KeyValueStudioSecurityStore(firstProfile).load())
        assertNull(KeyValueStudioSecurityStore(secondProfile).load())

        firstProfile.stores.values.single().failWrites = true
        assertFailsWith<IllegalStateException> { first.save(StudioSecurityLevel.Trust) }
        assertEquals(StudioSecurityLevel.Ask, KeyValueStudioSecurityStore(firstProfile).load())
    }

    @Test
    fun `unknown and malformed values fail loading instead of enabling the automatic default`() = runTest {
        val stores = ChecklistTestStores()
        val repository = KeyValueStudioSecurityStore(stores)
        listOf("", "Unknown", "{broken", "\"Trust\"").forEach { raw ->
            stores.stores.values.single().values.value = mapOf("confirmation_level" to raw)
            assertFailsWith<IllegalStateException> { repository.load() }
            assertEquals(raw, stores.stores.values.single().values.value["confirmation_level"])
        }
    }
}

private class SecurityPolicyMachine : StudioSecurityMachine {
    override val name = "studio-security"
    override val state = MutableStateFlow<StudioSecurityState>(StudioSecurityState.Loading)
    override val outputs = MutableSharedFlow<StudioSecurityOutput>()
    override suspend fun send(intent: StudioSecurityIntent): SendResult = error("policy must be read-only")
}
