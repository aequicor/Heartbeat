package io.aequicor.heartbeat.feature.applogs.impl.domain

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.applogs.api.AppLogsEffect
import io.aequicor.heartbeat.feature.applogs.api.AppLogsIntent
import io.aequicor.heartbeat.feature.applogs.api.AppLogsOperation
import io.aequicor.heartbeat.feature.applogs.api.AppLogsOutcome
import io.aequicor.heartbeat.feature.applogs.api.AppLogsState
import io.aequicor.heartbeat.feature.applogs.impl.data.AppLogsEffects
import io.aequicor.heartbeat.feature.applogs.impl.presentation.AppLogsNoticeUi
import io.aequicor.heartbeat.feature.applogs.impl.presentation.toScreenState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLogsEffectsTest {
    private val requests = mutableListOf<AppLogsOperation>()
    private val events = mutableListOf<AppLogsIntent>()
    private var outcome = AppLogsOutcome.Success
    private var failure: Exception? = null
    private val actions = object : AppLogsActions {
        override suspend fun execute(operation: AppLogsOperation): AppLogsOutcome {
            requests += operation
            failure?.let { throw it }
            return outcome
        }
    }
    private val feedback = object : EffectScope<AppLogsIntent> {
        override suspend fun send(intent: AppLogsIntent): SendResult {
            events += intent
            return SendResult.Accepted
        }
    }

    @Test
    fun `native outcomes return to the machine without content or paths`() = runTest {
        val effects = AppLogsEffects(actions, TestToggles())
        AppLogsOperation.entries.forEach { operation ->
            AppLogsOutcome.entries.forEach { result ->
                outcome = result
                effects.handle(AppLogsEffect.Execute(operation), feedback)
                assertEquals(operation, requests.last())
                assertEquals(AppLogsIntent.Internal.Finished(result), events.last())
            }
        }
    }

    @Test
    fun `disabled toggle blocks native access`() = runTest {
        AppLogsEffects(actions, TestToggles(false)).handle(AppLogsEffect.Execute(AppLogsOperation.CopyRecent), feedback)
        assertTrue(requests.isEmpty())
        assertEquals(listOf<AppLogsIntent>(AppLogsIntent.Internal.Finished(AppLogsOutcome.Disabled)), events)
    }

    @Test
    fun `native failure is safe and cancellation propagates without a result`() = runTest {
        val effects = AppLogsEffects(actions, TestToggles())
        failure = IllegalStateException("private path or content")
        effects.handle(AppLogsEffect.Execute(AppLogsOperation.SaveRecent), feedback)
        assertEquals(listOf<AppLogsIntent>(AppLogsIntent.Internal.Finished(AppLogsOutcome.Failure)), events)
        events.clear()
        failure = CancellationException("closed screen")
        assertFailsWith<CancellationException> {
            effects.handle(AppLogsEffect.Execute(AppLogsOperation.SaveArchive), feedback)
        }
        assertTrue(events.isEmpty())
    }

    @Test
    fun `presentation clears previous notices while working and maps operation results`() {
        assertTrue(AppLogsState.Working(AppLogsOperation.SaveRecent).toScreenState().isBusy)
        val copied = AppLogsState.Ready(AppLogsOperation.CopyRecent, AppLogsOutcome.Success).toScreenState()
        assertFalse(copied.isBusy)
        assertEquals(AppLogsNoticeUi.Copied, copied.notice)
        assertEquals(
            AppLogsNoticeUi.Cancelled,
            AppLogsState.Ready(AppLogsOperation.SaveRecent, AppLogsOutcome.Cancelled).toScreenState().notice,
        )
    }

    private class TestToggles(private val isEnabled: Boolean = true) : FeatureToggles {
        override fun <T : Any> observe(toggle: FeatureToggle<T>) = flowOf(value(toggle))
        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = value(toggle)

        @Suppress("UNCHECKED_CAST") // The feature reads only its boolean rollout flag.
        private fun <T : Any> value(toggle: FeatureToggle<T>): T = when (toggle) {
            is FeatureToggle.Flag -> isEnabled as T
            is FeatureToggle.Choice -> toggle.default as T
        }
    }
}
