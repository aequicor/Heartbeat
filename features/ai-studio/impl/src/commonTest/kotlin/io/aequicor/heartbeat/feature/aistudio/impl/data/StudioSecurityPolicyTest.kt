package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSecuritySettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.SecurityMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class StudioSecurityPolicyTest {
    @Test
    fun `turn waits for profile storage and observes updates without a settings screen`() = runTest {
        val settings = SecurityPolicySettings()
        val policy = ProfileStudioSecurityPolicy(settings)
        val seen = mutableListOf<SecurityMode>()
        backgroundScope.launch { policy.levels.collect { seen += it } }
        val waiting = async { policy.level() }
        runCurrent()
        assertFalse(waiting.isCompleted)

        settings.save(SecurityMode.Ask)
        assertEquals(SecurityMode.Ask, waiting.await())
        settings.save(SecurityMode.Trust)
        runCurrent()
        assertEquals(SecurityMode.Trust, policy.level())
        settings.save(SecurityMode.Automatic)
        runCurrent()
        assertEquals(SecurityMode.Automatic, policy.level())
        assertEquals(listOf(SecurityMode.Ask, SecurityMode.Trust, SecurityMode.Automatic), seen)
    }

    @Test
    fun `new profile never inherits an already observed trust mode`() = runTest {
        val first = SecurityPolicySettings().apply { save(SecurityMode.Trust) }
        assertEquals(SecurityMode.Trust, ProfileStudioSecurityPolicy(first).level())
        val second = SecurityPolicySettings()
        val restored = async { ProfileStudioSecurityPolicy(second).level() }
        runCurrent()
        assertFalse(restored.isCompleted)
        second.save(SecurityMode.Ask)
        assertEquals(SecurityMode.Ask, restored.await())
    }
}

private class SecurityPolicySettings : AgentSecuritySettings {
    override val modes = MutableSharedFlow<SecurityMode>(replay = 1)
    override suspend fun load(): SecurityMode = modes.first()
    override suspend fun save(mode: SecurityMode) = modes.emit(mode)
}
