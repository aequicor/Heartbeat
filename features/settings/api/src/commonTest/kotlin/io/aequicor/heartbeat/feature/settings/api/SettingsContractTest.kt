package io.aequicor.heartbeat.feature.settings.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsContractTest {
    @Test
    fun `route survives saved state with and without a section`() {
        for (route in listOf(SettingsRoute(), SettingsRoute(SettingsSection.FeatureFlags))) {
            val json = Json.encodeToString(SettingsRoute.serializer(), route)
            assertEquals(route.section, Json.decodeFromString(SettingsRoute.serializer(), json).section)
        }
    }

    @Test
    fun `every section addresses the same single settings window`() {
        assertEquals(SettingsRoute(), SettingsRoute(SettingsSection.Search))
        assertEquals(SettingsRoute().hashCode(), SettingsRoute(SettingsSection.Models).hashCode())
    }

    @Test
    fun `deep link names round trip and unknown names are rejected`() {
        SettingsSection.entries.forEach { assertEquals(it, settingsSectionOf(it.deepLinkName)) }
        assertNull(settingsSectionOf("unknown"))
    }

    @Test
    fun `unified settings are on by default`() {
        assertEquals(true, UnifiedSettings.default)
    }
}
