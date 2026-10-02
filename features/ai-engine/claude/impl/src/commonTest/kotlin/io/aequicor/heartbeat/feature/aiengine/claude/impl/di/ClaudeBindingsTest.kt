package io.aequicor.heartbeat.feature.aiengine.claude.impl.di

import io.aequicor.heartbeat.feature.aiengine.claude.impl.domain.ClaudeBackend
import io.aequicor.heartbeat.feature.aiengine.claude.impl.domain.ClaudeEngineManager
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnvironmentEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.validateLaunchSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class ClaudeBindingsTest {
    private val registration = ClaudeBindings.registration(
        lazy<ClaudeBackend> { error("Must stay lazy") },
        lazy<ClaudeEngineManager> { error("Must stay lazy") },
    )

    /**
     * Revision 1 is the first catalog that reports image and document input support. A profile cache of an older
     * revision is rediscovered in the background, so attachments never stay hidden behind a stale discovery.
     */
    @Test
    fun `registration keeps the catalog revision that introduced input support`() {
        assertEquals(1, registration.modelCatalogRevision)
    }

    @Test
    fun `Heartbeat installs its own copy and keeps the variables it sets reserved`() {
        val spec = registration.management
        assertEquals(InstallSupport.Managed, spec.install)
        assertEquals(
            setOf(LaunchOption.Executable, LaunchOption.HomeDirectory, LaunchOption.Environment),
            spec.launch.options,
        )

        val settings = LaunchSettings(
            environment = listOf(
                EnvironmentEntry("HTTPS_PROXY", "http://proxy:3128"),
                EnvironmentEntry("CLAUDE_CONFIG_DIR", "/elsewhere"),
                EnvironmentEntry("DISABLE_AUTOUPDATER", "0"),
            ),
        )
        val problems = validateLaunchSettings(settings, spec.launch, EnginePlatform.DesktopMacOs)
        assertEquals(listOf(1, 2), problems.map { it.index })
    }
}
