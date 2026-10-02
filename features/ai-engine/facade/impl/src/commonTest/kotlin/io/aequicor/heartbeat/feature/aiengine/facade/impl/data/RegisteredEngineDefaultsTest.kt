package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthOwnerId
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFamily
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagementEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineFactory
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ProfileEnginePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class RegisteredEngineDefaultsTest {
    private val engineToggle = FeatureToggle.Flag("ai.desktop", "Desktop engine", default = true)
    private val factory = lazy<EngineFactory> { error("Defaults must not construct a factory") }
    private val desktop = EngineRegistration(
        EngineDescriptor(
            id = EngineId("desktop"),
            title = "Desktop",
            family = EngineFamily.BuiltIn,
            platforms = setOf(EnginePlatform.DesktopWindows, EnginePlatform.DesktopMacOs),
            toggle = engineToggle,
            isDefault = true,
        ),
        AuthOwnerId("desktop"),
        factory,
    )
    private val secondary = EngineRegistration(
        desktop.descriptor.copy(id = EngineId("secondary"), isDefault = false),
        AuthOwnerId("secondary"),
        factory,
    )

    @Test
    fun `desktop hosts prefer the default registration without constructing its factory`() = runTest {
        listOf(HostPlatform.Windows, HostPlatform.MacOs).forEach { host ->
            assertEquals(EngineId("desktop"), defaults(host).preferred())
        }
        assertFalse(factory.isInitialized())
    }

    @Test
    fun `hosts without a registered default engine have no preference`() = runTest {
        listOf(HostPlatform.Linux, HostPlatform.Android, HostPlatform.Ios).forEach { host ->
            assertNull(defaults(host).preferred())
        }
    }

    @Test
    fun `disabled engine catalog has no preference`() = runTest {
        assertNull(defaults(HostPlatform.Windows, mapOf(AiEngines to false)).preferred())
    }

    @Test
    fun `disabled default engine toggle has no preference`() = runTest {
        assertNull(defaults(HostPlatform.MacOs, mapOf(engineToggle to false)).preferred())
    }

    @Test
    fun `an engine switched off in the profile is no default while engine management is on`() = runTest {
        val off = ProfileEnginePreferences(disabled = setOf(EngineId("desktop")))

        assertNull(defaults(HostPlatform.MacOs, mapOf(EngineManagementEnabled to true), off).preferred())
        assertEquals(EngineId("desktop"), defaults(HostPlatform.MacOs, preferences = off).preferred())
    }

    @Test
    fun `conflicting default registrations fail validation`() = runTest {
        val conflicting = EngineRegistration(
            desktop.descriptor.copy(id = EngineId("other")),
            AuthOwnerId("other"),
            factory,
        )
        val subject = RegisteredEngineDefaults(
            setOf(desktop, conflicting),
            Host(HostPlatform.Linux),
            ProfileEngineGate(Toggles(emptyMap()), MemoryEnginePreferences()),
        )
        assertFailsWith<IllegalArgumentException> { subject.preferred() }
    }

    private fun defaults(
        host: HostPlatform,
        overrides: Map<FeatureToggle<*>, Any> = emptyMap(),
        preferences: ProfileEnginePreferences = ProfileEnginePreferences(),
    ) = RegisteredEngineDefaults(
        setOf(desktop, secondary),
        Host(host),
        ProfileEngineGate(Toggles(overrides), MemoryEnginePreferences(preferences)),
    )

    private class Host(override val host: HostPlatform) : PlatformInfo

    private class Toggles(private val overrides: Map<FeatureToggle<*>, Any>) : FeatureToggles {
        override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flowOf(value(toggle))
        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = value(toggle)

        @Suppress("UNCHECKED_CAST")
        private fun <T : Any> value(toggle: FeatureToggle<T>): T = overrides[toggle] as T? ?: toggle.default
    }
}
