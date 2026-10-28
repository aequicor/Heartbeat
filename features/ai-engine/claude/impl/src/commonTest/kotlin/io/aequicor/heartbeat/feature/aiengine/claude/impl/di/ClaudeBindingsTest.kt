package io.aequicor.heartbeat.feature.aiengine.claude.impl.di

import io.aequicor.heartbeat.feature.aiengine.claude.impl.domain.ClaudeBackend
import io.aequicor.heartbeat.feature.aiengine.claude.impl.domain.ClaudeEngineManager
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnvironmentEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.RuntimePreference
import io.aequicor.heartbeat.feature.aiengine.facade.api.validateLaunchSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClaudeBindingsTest {
    private val registration = ClaudeBindings.registration(
        lazy<ClaudeBackend> { error("Must stay lazy") },
        lazy<ClaudeEngineManager> { error("Must stay lazy") },
    )

    /**
     * Revision 2 judges image input of an alias by the model the CLI resolves it to. A profile cache of an older
     * revision is rediscovered in the background, so attachments never stay hidden behind a stale discovery.
     */
    @Test
    fun `registration keeps the catalog revision that reports input support of resolved aliases`() {
        assertTrue(EnginePlatform.DesktopLinux in registration.descriptor.platforms)
        assertEquals(2, registration.modelCatalogRevision)
    }

    @Test
    fun `Heartbeat installs its own copy and keeps the variables it sets reserved`() {
        val spec = registration.management
        assertEquals(InstallSupport.Managed, spec.install)
        assertEquals(
            setOf(
                LaunchOption.Executable,
                LaunchOption.Runtime,
                LaunchOption.HomeDirectory,
                LaunchOption.Environment,
                LaunchOption.Mcp,
            ),
            spec.launch.options,
        )
        assertEquals("Claude", spec.hostApp?.title)
        val methods = registration.descriptor.connectionMethods.filterIsInstance<ConnectionMethod.CliLogin>()
        assertEquals(listOf("cli" to null, "desktop" to spec.hostApp), methods.map { it.id.value to it.hostApp })
        assertEquals(
            setOf(RuntimePreference.Managed, RuntimePreference.HostApp, RuntimePreference.System),
            spec.launch.runtimes,
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
