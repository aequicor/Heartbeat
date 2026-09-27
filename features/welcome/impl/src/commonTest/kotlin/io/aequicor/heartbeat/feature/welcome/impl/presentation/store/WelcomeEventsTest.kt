package io.aequicor.heartbeat.feature.welcome.impl.presentation.store

import io.aequicor.heartbeat.feature.welcome.api.WelcomeDestination
import io.aequicor.heartbeat.feature.welcome.api.WelcomeIntent
import kotlin.test.Test
import kotlin.test.assertEquals

class WelcomeEventsTest {
    @Test
    fun `presentation events preserve the existing machine protocol`() {
        assertEquals(WelcomeIntent.Public.Skip, WelcomeScreenIntent.Skip.toMachineIntent())
        assertEquals(WelcomeIntent.Internal.Finished, WelcomeScreenIntent.IntroFinished.toMachineIntent())
        assertEquals(
            WelcomeIntent.Public.Open(WelcomeDestination.Studio),
            WelcomeScreenIntent.OpenStudio.toMachineIntent(),
        )
        assertEquals(
            WelcomeIntent.Public.Open(WelcomeDestination.Toggles),
            WelcomeScreenIntent.OpenToggles.toMachineIntent(),
        )
    }
}
