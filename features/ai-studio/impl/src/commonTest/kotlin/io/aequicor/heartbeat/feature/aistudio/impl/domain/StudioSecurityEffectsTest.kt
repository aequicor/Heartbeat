package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityEffect
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityIntent
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StudioSecurityEffectsTest {
    @Test
    fun `save is acknowledged only after durable storage completes`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = SecurityEffectStore().apply {
            beforeSave = {
                entered.complete(Unit)
                release.await()
            }
        }
        val scope = SecurityEffectScope()
        val saving = launch {
            StudioSecurityEffects(store).handle(StudioSecurityEffect.Save(StudioSecurityLevel.Trust), scope)
        }
        entered.await()
        assertTrue(scope.sent.isEmpty())
        assertEquals(StudioSecurityLevel.Ask, store.value)
        release.complete(Unit)
        saving.join()
        assertEquals(StudioSecurityLevel.Trust, store.value)
        assertEquals<List<StudioSecurityIntent>>(listOf(StudioSecurityIntent.Internal.Saved), scope.sent)
    }

    @Test
    fun `storage failure cannot acknowledge a more permissive policy`() = runTest {
        val store = SecurityEffectStore().apply { beforeSave = { error("disk unavailable") } }
        val scope = SecurityEffectScope()
        assertFailsWith<IllegalStateException> {
            StudioSecurityEffects(store).handle(StudioSecurityEffect.Save(StudioSecurityLevel.Trust), scope)
        }
        assertTrue(scope.sent.isEmpty())
        assertEquals(StudioSecurityLevel.Ask, store.value)
    }

    @Test
    fun `load returns the saved policy without writing a default`() = runTest {
        val store = SecurityEffectStore().apply { beforeSave = { error("must not write during load") } }
        val scope = SecurityEffectScope()
        StudioSecurityEffects(store).handle(StudioSecurityEffect.Load, scope)
        assertEquals<List<StudioSecurityIntent>>(
            listOf(StudioSecurityIntent.Internal.Loaded(StudioSecurityLevel.Ask)),
            scope.sent,
        )
    }
}

private class SecurityEffectStore : StudioSecurityStore {
    var value = StudioSecurityLevel.Ask
    var beforeSave: suspend () -> Unit = {}
    override suspend fun load(): StudioSecurityLevel = value
    override suspend fun save(level: StudioSecurityLevel) {
        beforeSave()
        value = level
    }
}

private class SecurityEffectScope : EffectScope<StudioSecurityIntent> {
    val sent = mutableListOf<StudioSecurityIntent>()
    override suspend fun send(intent: StudioSecurityIntent): SendResult {
        sent += intent
        return SendResult.Accepted
    }
}
