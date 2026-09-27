package io.aequicor.heartbeat.feature.welcome.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WelcomeMachineTest {
    @Test
    fun `start reads settings and enabled config starts intro`() {
        WelcomeMachineSpec.assertTransition(
            WelcomeState.Idle,
            WelcomeIntent.Public.Start,
            WelcomeState.Checking,
            effects = listOf(WelcomeEffect.ReadSettings),
        )
        WelcomeMachineSpec.assertTransition(
            WelcomeState.Checking,
            WelcomeIntent.Internal.Configured(true),
            WelcomeState.Intro,
        )
        WelcomeMachineSpec.assertTransition(
            WelcomeState.Checking,
            WelcomeIntent.Internal.Configured(false),
            WelcomeState.Ready,
        )
    }

    @Test
    fun `skip always exits preparation and completion ends intro`() {
        listOf(WelcomeState.Idle, WelcomeState.Checking, WelcomeState.Intro).forEach {
            WelcomeMachineSpec.assertTransition(it, WelcomeIntent.Public.Skip, WelcomeState.Ready)
        }
        WelcomeMachineSpec.assertTransition(WelcomeState.Intro, WelcomeIntent.Internal.Finished, WelcomeState.Ready)
        WelcomeMachineSpec.assertIgnored(WelcomeState.Ready, WelcomeIntent.Internal.Finished)
        WelcomeMachineSpec.assertIgnored(WelcomeState.Ready, WelcomeIntent.Internal.Configured(true))
    }

    @Test
    fun `both destinations are acknowledged and back returns to ready`() {
        WelcomeDestination.entries.forEach { destination ->
            val open = WelcomeIntent.Public.Open(destination)
            val opening = WelcomeState.Opening(destination)
            WelcomeMachineSpec.assertIgnored(WelcomeState.Intro, open)
            WelcomeMachineSpec.assertTransition(WelcomeState.Ready, open, opening)
            WelcomeMachineSpec.assertIgnored(opening, open)
            WelcomeMachineSpec.assertTransition(opening, WelcomeIntent.Internal.NavigationHandled, WelcomeState.Away)
            WelcomeMachineSpec.assertIgnored(WelcomeState.Away, open)
            WelcomeMachineSpec.assertTransition(WelcomeState.Away, WelcomeIntent.Internal.Returned, WelcomeState.Ready)
        }
    }

    @Test
    fun `restored sessions never replay intro or pending navigation`() {
        listOf(
            WelcomeState.Idle,
            WelcomeState.Checking,
            WelcomeState.Intro,
            WelcomeState.Ready,
            WelcomeState.Opening(WelcomeDestination.Studio),
            WelcomeState.Away,
        ).forEach {
            val restored = WelcomeMachineSpec.restore(it)
            assertEquals(WelcomeState.Ready, restored.state)
            assertTrue(restored.effects.isEmpty())
        }
        assertEquals(WelcomeState.Idle, WelcomeMachineSpec.initial)
    }

    @Test
    fun `settings failures leave the user with usable actions`() {
        val failure = WelcomeMachineSpec.onEffectFailure(WelcomeEffect.ReadSettings, IllegalStateException("disk"))
        assertEquals(WelcomeIntent.Internal.Failed, failure)
        WelcomeMachineSpec.assertTransition(WelcomeState.Checking, requireNotNull(failure), WelcomeState.Ready)
    }
}
