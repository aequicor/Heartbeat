package io.aequicor.heartbeat.feature.aiengine.pi.impl.di

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnvironmentEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.validateLaunchSettings
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.PiAdapter
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.PiConfigSchema
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.PiConfigurator
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.PiEngineManager
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PiRegistrationBindingsTest {
    private val registration = registration(HostPlatform.MacOs)

    /**
     * Revision 4 replaces synthetic compatible context windows with explicit catalog limits.
     * Cached discoveries must be refreshed so an unknown capacity never remains a confirmed window.
     */
    @Test
    fun `registration invalidates cached synthetic compatible context limits`() {
        assertTrue(EnginePlatform.DesktopLinux in registration.descriptor.platforms)
        assertEquals(4, registration.modelCatalogRevision)
    }

    @Test
    fun `the bundled Pi may be replaced and Pi's own variables stay reserved`() {
        assertEquals(InstallSupport.Bundled, registration.management.install)
        val settings = LaunchSettings(
            environment = listOf(
                EnvironmentEntry("HTTPS_PROXY", "http://proxy:3128"),
                EnvironmentEntry("PI_OFFLINE", "0"),
                EnvironmentEntry("HEARTBEAT_SEARCH_BRIDGE_URL", "http://elsewhere"),
            ),
        )

        val problems = validateLaunchSettings(settings, registration.management.launch, EnginePlatform.DesktopMacOs)

        assertEquals(listOf(1, 2), problems.map { it.index })
    }

    @Test
    fun `Pi declares its configuration on the desktop only and stays lazy`() {
        assertEquals(PiConfigSchema, registration.configuration)
        assertTrue(registration.configurator != null)
        listOf(HostPlatform.Android, HostPlatform.Ios).forEach { host ->
            val mobile = registration(host)
            assertNull(mobile.configuration)
            assertNull(mobile.configurator)
        }
    }

    private fun registration(host: HostPlatform) = PiRegistrationBindings.registration(
        lazy<PiAdapter> { error("Must stay lazy") },
        lazy<PiEngineManager> { error("Must stay lazy") },
        lazy<PiConfigurator> { error("Must stay lazy") },
        object : PlatformInfo {
            override val host = host
        },
        object : LocalWorkspaces {
            override val isAvailable = false
            override fun observe() = kotlinx.coroutines.flow.flowOf(emptyList<LocalWorkspace>())
            override suspend fun register(directory: String): Nothing = error("unused")
            override suspend fun resolve(ref: WorkspaceRef): String? = null
        },
        object : DispatcherProvider {
            override val main = Dispatchers.Unconfined
            override val default = Dispatchers.Unconfined
            override val io = Dispatchers.Unconfined
        },
    )
}
