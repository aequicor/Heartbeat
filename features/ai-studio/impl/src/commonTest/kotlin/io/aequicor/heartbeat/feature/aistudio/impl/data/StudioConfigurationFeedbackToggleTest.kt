package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingChange
import io.aequicor.heartbeat.feature.feedback.api.FeedbackOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StudioConfigurationFeedbackToggleTest {
    @Test
    fun `turning feedback off after pending still settles its card for later display`() = runTest {
        val fixture = StudioConfigurationFixture(this)
        val native = fixture.add()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        native.onApply = { _, _ ->
            started.complete(Unit)
            release.await()
            fixture.initial.copy(reasoningEffort = "high")
        }
        val operation = launch {
            fixture.controller.configure(fixture.access, "chat", StudioSettingChange.Effort("high"))
        }
        started.await()
        assertIs<FeedbackOutcome.Pending>(fixture.controller.feedback("chat").first().single().outcome)
        fixture.isFeedbackEnabled = false
        assertTrue(fixture.controller.feedback("chat").first().isEmpty())
        release.complete(Unit)
        operation.join()
        runCurrent()
        assertEquals(listOf(0L, 1L), fixture.registry.publications.map { it.revision })
        fixture.isFeedbackEnabled = true
        val displayed = fixture.controller.feedback("chat").first().single()
        assertEquals(fixture.registry.publications.first().id, displayed.id)
        assertEquals("high", assertIs<FeedbackOutcome.Applied>(displayed.outcome).configuration.reasoningEffort)
        assertEquals(null, fixture.access.states.getValue("chat").pendingOperation)
    }
}
