package io.aequicor.heartbeat.feature.applogs.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppLogsMachineTest {
    @Test
    fun `each operation starts one effect and ignores concurrent requests`() {
        AppLogsOperation.entries.forEach { operation ->
            val busy = AppLogsState.Working(operation)
            AppLogsMachineSpec.assertTransition(
                AppLogsState.Ready(),
                AppLogsIntent.Public.Execute(operation),
                busy,
                effects = listOf(AppLogsEffect.Execute(operation)),
            )
            AppLogsOperation.entries.forEach { other ->
                AppLogsMachineSpec.assertIgnored(busy, AppLogsIntent.Public.Execute(other))
            }
            AppLogsMachineSpec.assertIgnored(busy, AppLogsIntent.Public.Retry)
        }
    }

    @Test
    fun `every outcome releases the busy state and permits another operation`() {
        AppLogsOutcome.entries.forEach { outcome ->
            val operation = AppLogsOperation.SaveArchive
            val ready = AppLogsState.Ready(operation, outcome)
            AppLogsMachineSpec.assertTransition(
                AppLogsState.Working(operation),
                AppLogsIntent.Internal.Finished(outcome),
                ready,
            )
            AppLogsMachineSpec.assertTransition(
                ready,
                AppLogsIntent.Public.Execute(AppLogsOperation.CopyRecent),
                AppLogsState.Working(AppLogsOperation.CopyRecent),
                effects = listOf(AppLogsEffect.Execute(AppLogsOperation.CopyRecent)),
            )
        }
    }

    @Test
    fun `only a failed operation can be retried`() {
        AppLogsOperation.entries.forEach { operation ->
            AppLogsMachineSpec.assertTransition(
                AppLogsState.Ready(operation, AppLogsOutcome.Failure),
                AppLogsIntent.Public.Retry,
                AppLogsState.Working(operation),
                effects = listOf(AppLogsEffect.Execute(operation)),
            )
            AppLogsOutcome.entries.filter { it != AppLogsOutcome.Failure }.forEach { outcome ->
                AppLogsMachineSpec.assertIgnored(AppLogsState.Ready(operation, outcome), AppLogsIntent.Public.Retry)
            }
        }
        AppLogsMachineSpec.assertIgnored(AppLogsState.Ready(), AppLogsIntent.Public.Retry)
        AppLogsMachineSpec.assertIgnored(
            AppLogsState.Ready(),
            AppLogsIntent.Internal.Finished(AppLogsOutcome.Success),
        )
    }

    @Test
    fun `effect failures are recoverable and the machine does not persist`() {
        assertEquals(
            AppLogsIntent.Internal.Finished(AppLogsOutcome.Failure),
            AppLogsMachineSpec.onEffectFailure(
                AppLogsEffect.Execute(AppLogsOperation.OpenFolder),
                IllegalStateException("native failure"),
            ),
        )
        assertNull(AppLogsMachineSpec.persistence)
    }
}
