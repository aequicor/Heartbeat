package io.aequicor.heartbeat.feature.settings.impl

import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.profilefacade.ProfileGraph
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.profilefacade.ProfileSession
import io.aequicor.heartbeat.core.profilefacade.ProfileSessions
import io.aequicor.heartbeat.feature.settings.api.SettingsSection
import io.aequicor.heartbeat.feature.settings.impl.data.ToggleSettingsSections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToggleSettingsSectionsTest {
    @Test
    fun `the first sections are the read toggles without an ungated placeholder`() = runTest {
        val sessions = Sessions(ProfileSession(ProfileId("p1"), UnusedGraph))
        val emitted = mutableListOf<List<SettingsSection>>()
        val platform = object : PlatformInfo {
            override val host = HostPlatform.Linux
        }
        backgroundScope.launch {
            ToggleSettingsSections(EnabledToggles, sessions, platform).available.collect { emitted += it }
        }
        runCurrent()
        assertEquals(1, emitted.size, "A requested section opens with a single navigation")
        assertTrue(SettingsSection.Models in emitted.single())
    }

    private object EnabledToggles : FeatureToggles {
        @Suppress("UNCHECKED_CAST") // The sections read only flag toggles.
        override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flowOf(true as T)
        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = observe(toggle).first()
    }

    private class Sessions(session: ProfileSession) : ProfileSessions {
        override val active = MutableStateFlow<ProfileSession?>(session)
        override suspend fun restore(): ProfileSession? = active.value
        override suspend fun open(id: ProfileId): ProfileSession = error("unused")
        override suspend fun close() = Unit
    }

    private object UnusedGraph : ProfileGraph {
        override val scope get() = error("unused")
        override val sharedScopes get() = error("unused")
    }
}
