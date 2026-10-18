package io.aequicor.heartbeat.feature.serviceasui.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentResourcePolicy
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiEnabled
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.ServiceUiWeb
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.ServiceUiWebSafeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServiceUiWebConfigurationTest {
    @Test
    fun `default Safe Mode and all availability gates apply to live and preview settings`() = runTest {
        val toggles = Toggles()
        toggles.values.value = mapOf(ServiceUiEnabled.key to false, ServiceUiWeb.key to false)
        assertTrue(ServiceUiWebSafeMode.default)
        assertTrue(toggles.uiWebPolicyInstruction().contains("Web documents are disabled"))
        assertFalse(toggles.uiWebConfiguration(isDesktop = true).first().isEnabled)
        assertEquals(BrowserDocumentResourcePolicy.Isolated, toggles.uiWebResourcePolicy())
        toggles.values.value = mapOf(ServiceUiEnabled.key to true, ServiceUiWeb.key to true)
        assertTrue(toggles.uiWebPolicyInstruction().contains("Safe Mode: on"))
        assertTrue(toggles.uiWebConfiguration(isDesktop = true).first().isEnabled)
        assertFalse(toggles.uiWebConfiguration(isDesktop = false).first().isEnabled)
        toggles.values.value += ServiceUiWebSafeMode.key to false
        assertEquals(
            BrowserDocumentResourcePolicy.ExternalAssets,
            toggles.uiWebConfiguration(isDesktop = true).first().resourcePolicy,
        )
        assertEquals(BrowserDocumentResourcePolicy.ExternalAssets, toggles.uiWebResourcePolicy())
        assertTrue(toggles.uiWebPolicyInstruction().contains("Safe Mode: off"))
        toggles.values.value += ServiceUiEnabled.key to false
        assertFalse(toggles.uiWebConfiguration(isDesktop = true).first().isEnabled)
    }

    @Test
    fun `disabled web guidance does not promise external assets when Safe Mode is off`() = runTest {
        val toggles = Toggles()
        toggles.values.value = mapOf(
            ServiceUiEnabled.key to false,
            ServiceUiWeb.key to false,
            ServiceUiWebSafeMode.key to false,
        )
        val instruction = toggles.uiWebPolicyInstruction()
        assertTrue(instruction.contains("Web documents are disabled"))
        assertTrue(instruction.contains("native nodes"))
        assertTrue(instruction.contains("do not claim HTML was rendered"))
        assertFalse(instruction.contains("permitted"))
    }

    private class Toggles : FeatureToggles {
        val values = MutableStateFlow(emptyMap<String, Boolean>())

        @Suppress("UNCHECKED_CAST") // The tested feature settings are Boolean flags.
        override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> =
            values.map { it[toggle.key] as T? ?: toggle.default }

        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = observe(toggle).first()
    }
}
