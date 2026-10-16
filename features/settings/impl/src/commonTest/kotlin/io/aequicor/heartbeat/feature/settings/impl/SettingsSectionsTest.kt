package io.aequicor.heartbeat.feature.settings.impl

import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningRoute
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityRoute
import io.aequicor.heartbeat.feature.applogs.api.AppLogsRoute
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeRoute
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseRoute
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationRoute
import io.aequicor.heartbeat.feature.harness.api.HarnessRoute
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiLibraryRoute
import io.aequicor.heartbeat.feature.settings.api.SettingsSection
import io.aequicor.heartbeat.feature.settings.api.deepLinkName
import io.aequicor.heartbeat.feature.settings.api.settingsSectionOf
import io.aequicor.heartbeat.feature.settings.impl.domain.availableSections
import io.aequicor.heartbeat.feature.settings.impl.presentation.component.route
import io.aequicor.heartbeat.feature.settings.impl.presentation.store.SettingsScreenState
import io.aequicor.heartbeat.feature.settings.impl.presentation.store.SettingsSectionUi
import io.aequicor.heartbeat.feature.settings.impl.presentation.store.resolve
import io.aequicor.heartbeat.feature.settings.impl.presentation.store.toSection
import io.aequicor.heartbeat.feature.settings.impl.presentation.store.toUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsSectionsTest {
    private val all = SettingsSection.entries.toList()

    @Test
    fun `profile sections need their toggle and a profile feature flags are always offered`() {
        assertEquals(
            all,
            availableSections(
                isModelsEnabled = true,
                isSearchEnabled = true,
                hasProfile = true,
                isSecurityEnabled = true,
                isComputerUseEnabled = true,
                isChromiumBridgeEnabled = true,
                isLearningEnabled = true,
                isHarnessEnabled = true,
                isContextEnabled = true,
                isInterfacesEnabled = true,
                isLogsEnabled = true,
                isDesktop = true,
            ),
        )
        assertEquals(
            listOf(SettingsSection.FeatureFlags),
            availableSections(isModelsEnabled = true, isSearchEnabled = true, hasProfile = false),
        )
        assertEquals(
            listOf(SettingsSection.Search, SettingsSection.FeatureFlags),
            availableSections(isModelsEnabled = false, isSearchEnabled = true, hasProfile = true),
        )
    }

    @Test
    fun `computer use is offered only with its toggle and an active profile and opens embedded`() {
        for (enabled in listOf(false, true)) {
            for (profile in listOf(false, true)) {
                val sections = availableSections(false, false, profile, isComputerUseEnabled = enabled)
                assertEquals(enabled && profile, SettingsSection.ComputerUse in sections)
            }
        }
        assertEquals(ComputerUseRoute(isEmbedded = true), SettingsSection.ComputerUse.route())
    }

    @Test
    fun `the chromium browser bridge is offered only with its toggle and an active profile and opens embedded`() {
        for (enabled in listOf(false, true)) {
            for (profile in listOf(false, true)) {
                val sections = availableSections(false, false, profile, isChromiumBridgeEnabled = enabled)
                assertEquals(enabled && profile, SettingsSection.ChromiumBrowser in sections)
            }
        }
        assertEquals(ChromiumBridgeRoute(isEmbedded = true), SettingsSection.ChromiumBrowser.route())
        assertEquals("chromium_browser", SettingsSection.ChromiumBrowser.deepLinkName)
    }

    @Test
    fun `self-learning is offered only with its toggle and an active profile and opens embedded`() {
        for (enabled in listOf(false, true)) {
            for (profile in listOf(false, true)) {
                val sections = availableSections(false, false, profile, isLearningEnabled = enabled)
                assertEquals(enabled && profile, SettingsSection.AgentLearning in sections)
            }
        }
        assertEquals(AgentLearningRoute(isEmbedded = true), SettingsSection.AgentLearning.route())
    }

    @Test
    fun `harnesses are offered only with their toggle and an active profile and open embedded`() {
        for (enabled in listOf(false, true)) {
            for (profile in listOf(false, true)) {
                val sections = availableSections(false, false, profile, isHarnessEnabled = enabled)
                assertEquals(enabled && profile, SettingsSection.Harness in sections)
            }
        }
        assertEquals(HarnessRoute(isEmbedded = true), SettingsSection.Harness.route())
        assertEquals("harness", SettingsSection.Harness.deepLinkName)
    }

    @Test
    fun `interfaces are offered only with their toggle and an active profile and open embedded`() {
        for (enabled in listOf(false, true)) {
            for (profile in listOf(false, true)) {
                val sections = availableSections(false, false, profile, isInterfacesEnabled = enabled)
                assertEquals(enabled && profile, SettingsSection.Interfaces in sections)
            }
        }
        assertEquals(ServiceUiLibraryRoute(isEmbedded = true), SettingsSection.Interfaces.route())
        assertEquals(SettingsSection.Interfaces, settingsSectionOf("interfaces"))
    }

    @Test
    fun `context is offered only with its toggle and an active profile and opens embedded`() {
        for (enabled in listOf(false, true)) {
            for (profile in listOf(false, true)) {
                val sections = availableSections(false, false, profile, isContextEnabled = enabled)
                assertEquals(enabled && profile, SettingsSection.Context in sections)
            }
        }
        assertEquals(ContextOptimizationRoute(isEmbedded = true), SettingsSection.Context.route())
        assertEquals("context", SettingsSection.Context.deepLinkName)
    }

    @Test
    fun `security requires studio runtime and a profile and opens embedded`() {
        for (enabled in listOf(false, true)) {
            for (profile in listOf(false, true)) {
                val sections = availableSections(false, false, profile, isSecurityEnabled = enabled)
                assertEquals(enabled && profile, SettingsSection.Security in sections)
            }
        }
        assertEquals(StudioSecurityRoute(isEmbedded = true), SettingsSection.Security.route())
        assertEquals(SettingsSection.Security, settingsSectionOf("security"))
    }

    private val allUi = all.map { it.toUi() }

    @Test
    fun `a requested section waits until it becomes available`() {
        val guest = SettingsScreenState().resolve(listOf(SettingsSectionUi.FeatureFlags), SettingsSectionUi.Models)
        assertEquals(SettingsSectionUi.FeatureFlags, guest.selected)
        assertEquals(SettingsSectionUi.Models, guest.requested)
        val profile = guest.resolve(allUi, guest.requested)
        assertEquals(SettingsSectionUi.Models, profile.selected)
        assertEquals(null, profile.requested)
    }

    @Test
    fun `a section that disappears falls back to the first available one`() {
        val state = SettingsScreenState(selected = SettingsSectionUi.Models).resolve(
            listOf(SettingsSectionUi.FeatureFlags),
            wanted = null,
        )
        assertEquals(SettingsSectionUi.FeatureFlags, state.selected)
    }

    @Test
    fun `application logs need desktop and their toggle but no profile`() {
        for (desktop in listOf(false, true)) {
            for (enabled in listOf(false, true)) {
                for (profile in listOf(false, true)) {
                    val sections = availableSections(
                        false,
                        false,
                        profile,
                        isLogsEnabled = enabled,
                        isDesktop = desktop,
                    )
                    assertEquals(desktop && enabled, SettingsSection.Logs in sections)
                }
            }
        }
        assertEquals(AppLogsRoute(isEmbedded = true), SettingsSection.Logs.route())
        assertEquals(SettingsSection.Logs, settingsSectionOf("logs"))
    }

    @Test
    fun `compact windows show the list until a section is chosen and after back`() {
        val base = SettingsScreenState(selected = SettingsSectionUi.Search)
        assertTrue(base.isCompactListShown)
        assertFalse(base.copy(isSectionChosen = true).isCompactListShown)
        assertTrue(base.copy(isSectionChosen = true, isListShown = true).isCompactListShown)
    }

    @Test
    fun `screen sections map one to one to contract sections`() {
        all.forEach { assertEquals(it, it.toUi().toSection()) }
    }
}
