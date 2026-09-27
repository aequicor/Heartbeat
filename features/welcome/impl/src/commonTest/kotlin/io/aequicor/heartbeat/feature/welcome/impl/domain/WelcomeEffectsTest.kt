package io.aequicor.heartbeat.feature.welcome.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.welcome.api.WelcomeEffect
import io.aequicor.heartbeat.feature.welcome.api.WelcomeIntent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WelcomeEffectsTest {
    private val sent = mutableListOf<WelcomeIntent>()
    private val feedback = object : EffectScope<WelcomeIntent> {
        override suspend fun send(intent: WelcomeIntent): SendResult {
            sent += intent
            return SendResult.Accepted
        }
    }

    @Test
    fun `both preference values reach the machine through the domain port`() = runTest {
        listOf(true, false).forEach { enabled ->
            WelcomeEffects(settings { enabled }).handle(WelcomeEffect.ReadSettings, feedback)
        }
        assertEquals(
            listOf<WelcomeIntent>(WelcomeIntent.Internal.Configured(true), WelcomeIntent.Internal.Configured(false)),
            sent,
        )
    }

    @Test
    fun `settings failure propagates without acknowledging a successful read`() = runTest {
        val effects = WelcomeEffects(settings { throw IllegalStateException("unavailable") })
        assertFailsWith<IllegalStateException> { effects.handle(WelcomeEffect.ReadSettings, feedback) }
        assertTrue(sent.isEmpty())
    }

    private fun settings(read: suspend () -> Boolean): WelcomeSettings = object : WelcomeSettings {
        override suspend fun isIntroEnabled(): Boolean = read()
    }
}
