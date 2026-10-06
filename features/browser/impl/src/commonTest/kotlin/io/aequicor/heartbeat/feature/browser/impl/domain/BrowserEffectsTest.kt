package io.aequicor.heartbeat.feature.browser.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.browser.api.BrowserEffect
import io.aequicor.heartbeat.feature.browser.api.BrowserIntent
import io.aequicor.heartbeat.feature.browser.api.BrowserPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BrowserEffectsTest {
    @Test
    fun `live toggle revokes the session before notifying the machine and observation survives`() = runTest {
        val enabled = MutableStateFlow(false)
        val session = TestBrowserSession()
        val sent = mutableListOf<BrowserIntent>()
        val feedback = object : EffectScope<BrowserIntent> {
            override suspend fun send(intent: BrowserIntent): SendResult {
                if (intent is BrowserIntent.Internal.AvailabilityChanged) {
                    assertEquals(intent.isEnabled, session.availability.last())
                }
                sent += intent
                return SendResult.Accepted
            }
        }
        val effects = BrowserEffects(BrowserAvailability { enabled }, session)
        val observation = backgroundScope.launch { effects.handle(BrowserEffect.Observe, feedback) }
        runCurrent()
        enabled.value = true
        runCurrent()
        session.pages.value = BrowserPage("https://example.com", isLoading = true)
        runCurrent()
        enabled.value = false
        runCurrent()
        enabled.value = true
        runCurrent()
        assertEquals(listOf(false, true, false, true), session.availability)
        assertTrue(BrowserIntent.Internal.PageChanged(session.pages.value) in sent)
        observation.cancel()
        runCurrent()
        assertEquals(0, enabled.subscriptionCount.value)
        assertEquals(0, session.pages.subscriptionCount.value)
    }

    @Test
    fun `navigation effects use domain commands without accessing presentation`() = runTest {
        val session = TestBrowserSession()
        val effects = BrowserEffects(BrowserAvailability { MutableStateFlow(true) }, session)
        val feedback = object : EffectScope<BrowserIntent> {
            override suspend fun send(intent: BrowserIntent) = SendResult.Accepted
        }
        listOf(
            BrowserEffect.Load("https://example.com"),
            BrowserEffect.Back,
            BrowserEffect.Forward,
            BrowserEffect.Reload,
            BrowserEffect.Stop,
        ).forEach { effects.handle(it, feedback) }
        assertEquals(
            listOf(
                BrowserCommand.Load("https://example.com"),
                BrowserCommand.Back,
                BrowserCommand.Forward,
                BrowserCommand.Reload,
                BrowserCommand.Stop,
            ),
            session.commands,
        )
    }
}

private class TestBrowserSession : BrowserSession {
    override val pages = MutableStateFlow(BrowserPage())
    val availability = mutableListOf<Boolean>()
    val commands = mutableListOf<BrowserCommand>()

    override suspend fun setEnabled(isEnabled: Boolean) {
        availability += isEnabled
    }

    override suspend fun execute(command: BrowserCommand) {
        commands += command
    }
}
