package io.aequicor.heartbeat.feature.aistudio.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class StudioSecurityMachineTest {
    @Test
    fun `profile starts by reading settings and missing settings use automatic review`() {
        StudioSecurityMachineSpec.assertTransition(
            StudioSecurityState.Idle,
            StudioSecurityIntent.Public.Start,
            StudioSecurityState.Loading,
            effects = listOf(StudioSecurityEffect.Load),
        )
        StudioSecurityMachineSpec.assertTransition(
            StudioSecurityState.Loading,
            StudioSecurityIntent.Internal.Loaded(null),
            StudioSecurityState.Ready(StudioSecurityLevel.Automatic),
        )
        StudioSecurityLevel.entries.forEach { level ->
            StudioSecurityMachineSpec.assertTransition(
                StudioSecurityState.Loading,
                StudioSecurityIntent.Internal.Loaded(level),
                StudioSecurityState.Ready(level),
            )
        }
    }

    @Test
    fun `unreadable settings forbid edits and can be reloaded without overwriting storage`() {
        StudioSecurityMachineSpec.assertTransition(
            StudioSecurityState.Loading,
            StudioSecurityIntent.Internal.LoadFailed,
            StudioSecurityState.LoadError,
        )
        StudioSecurityMachineSpec.assertIgnored(
            StudioSecurityState.LoadError,
            StudioSecurityIntent.Public.SetLevel(StudioSecurityLevel.Trust),
        )
        StudioSecurityMachineSpec.assertTransition(
            StudioSecurityState.LoadError,
            StudioSecurityIntent.Public.Retry,
            StudioSecurityState.Loading,
            effects = listOf(StudioSecurityEffect.Load),
        )
    }

    @Test
    fun `policy changes only after the selected level is saved`() {
        val ready = StudioSecurityState.Ready(StudioSecurityLevel.Ask)
        val saving = StudioSecurityState.Saving(StudioSecurityLevel.Ask, StudioSecurityLevel.Trust)
        StudioSecurityMachineSpec.assertTransition(
            ready,
            StudioSecurityIntent.Public.SetLevel(StudioSecurityLevel.Trust),
            saving,
            effects = listOf(StudioSecurityEffect.Save(StudioSecurityLevel.Trust)),
        )
        StudioSecurityMachineSpec.assertTransition(
            saving,
            StudioSecurityIntent.Internal.Saved,
            StudioSecurityState.Ready(StudioSecurityLevel.Trust),
        )
        StudioSecurityMachineSpec.assertTransition(
            saving,
            StudioSecurityIntent.Internal.SaveFailed,
            ready.copy(isSaveFailed = true),
        )
    }

    @Test
    fun `loading duplicate and overlapping changes cannot start stale writes`() {
        val intent = StudioSecurityIntent.Public.SetLevel(StudioSecurityLevel.Trust)
        listOf(
            StudioSecurityState.Idle,
            StudioSecurityState.Loading,
            StudioSecurityState.Ready(StudioSecurityLevel.Trust),
            StudioSecurityState.Saving(StudioSecurityLevel.Ask, StudioSecurityLevel.Automatic),
        ).forEach { StudioSecurityMachineSpec.assertIgnored(it, intent) }
        StudioSecurityMachineSpec.assertIgnored(
            StudioSecurityState.Ready(StudioSecurityLevel.Ask),
            StudioSecurityIntent.Internal.Saved,
        )
    }

    @Test
    fun `a failed write can be retried and effect failures have explicit outcomes`() {
        StudioSecurityMachineSpec.assertTransition(
            StudioSecurityState.Ready(StudioSecurityLevel.Ask, isSaveFailed = true),
            StudioSecurityIntent.Public.SetLevel(StudioSecurityLevel.Trust),
            StudioSecurityState.Saving(StudioSecurityLevel.Ask, StudioSecurityLevel.Trust),
            effects = listOf(StudioSecurityEffect.Save(StudioSecurityLevel.Trust)),
        )
        val failure = IllegalStateException("storage unavailable")
        assertEquals(
            StudioSecurityIntent.Internal.LoadFailed,
            StudioSecurityMachineSpec.onEffectFailure(StudioSecurityEffect.Load, failure),
        )
        assertEquals(
            StudioSecurityIntent.Internal.SaveFailed,
            StudioSecurityMachineSpec.onEffectFailure(StudioSecurityEffect.Save(StudioSecurityLevel.Trust), failure),
        )
    }

    @Test
    fun `embedded route survives navigation restoration`() {
        val route = StudioSecurityRoute(isEmbedded = true)
        assertEquals(route, Json.decodeFromString<StudioSecurityRoute>(Json.encodeToString(route)))
    }
}
