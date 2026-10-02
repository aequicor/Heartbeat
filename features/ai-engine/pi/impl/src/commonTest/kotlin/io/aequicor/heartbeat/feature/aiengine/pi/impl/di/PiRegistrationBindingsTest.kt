package io.aequicor.heartbeat.feature.aiengine.pi.impl.di

import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnvironmentEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.validateLaunchSettings
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.PiAdapter
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.PiEngineManager
import kotlin.test.Test
import kotlin.test.assertEquals

class PiRegistrationBindingsTest {
    private val registration = PiRegistrationBindings.registration(
        lazy<PiAdapter> { error("Must stay lazy") },
        lazy<PiEngineManager> { error("Must stay lazy") },
    )

    /**
     * Revision 3 is the first catalog that reports image and document input support. A profile cache of an older
     * revision is rediscovered in the background, so attachments never stay hidden behind a stale discovery.
     */
    @Test
    fun `registration keeps the catalog revision that introduced input support`() {
        assertEquals(3, registration.modelCatalogRevision)
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
}
