package io.aequicor.heartbeat.feature.aiengine.connections.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettingsSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettingsToken
import kotlin.test.Test
import kotlin.test.assertFalse

class WizardRuntimeChoiceTest {
    @Test
    fun `diagnostics do not expose selected or previous paths`() {
        val token = object : LaunchSettingsToken {}
        val previous = LaunchSettingsSnapshot(LaunchSettings(executable = "/private/previous"), token)
        val choice = WizardRuntimeChoice(previous, LaunchSettings(executable = "/private/selected"), null)

        assertFalse(choice.toString().contains("/private/"))
    }
}
