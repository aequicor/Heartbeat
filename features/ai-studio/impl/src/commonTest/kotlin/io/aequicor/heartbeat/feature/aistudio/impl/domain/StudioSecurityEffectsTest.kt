package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityEffect
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityIntent
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StudioSecurityEffectsTest {
    @Test
    fun `observation follows external writes and releases its subscription on cancellation`() = runTest {
        val store = SecurityEffectStore()
        val scope = SecurityEffectScope()
        val observer = launch { StudioSecurityEffects(store).handle(StudioSecurityEffect.Observe, scope) }
        runCurrent()
        store.value = StudioSecurityLevel.Trust
        runCurrent()
        store.value = StudioSecurityLevel.Automatic
        runCurrent()
        assertEquals<List<StudioSecurityIntent>>(
            listOf(StudioSecurityLevel.Ask, StudioSecurityLevel.Trust, StudioSecurityLevel.Automatic)
                .map { StudioSecurityIntent.Internal.Observed(it) },
            scope.sent,
        )
        observer.cancelAndJoin()
        assertEquals(0, store.levels.subscriptionCount.value)
    }

    @Test
    fun `external updates remain observable while a write succeeds`() = verifyConcurrentWrite(false)

    @Test
    fun `external updates remain observable while a write fails`() = verifyConcurrentWrite(true)

    private fun verifyConcurrentWrite(isFailure: Boolean) = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = SecurityEffectStore().apply {
            beforeSave = {
                entered.complete(Unit)
                release.await()
                if (isFailure) error("disk unavailable")
            }
        }
        val scope = SecurityEffectScope()
        val effects = StudioSecurityEffects(store)
        val observer = launch { effects.handle(StudioSecurityEffect.Observe, scope) }
        val saving = launch {
            if (isFailure) {
                assertFailsWith<IllegalStateException> {
                    effects.handle(StudioSecurityEffect.Save(StudioSecurityLevel.Trust), scope)
                }
            } else {
                effects.handle(StudioSecurityEffect.Save(StudioSecurityLevel.Trust), scope)
            }
        }
        entered.await()
        store.value = StudioSecurityLevel.Automatic
        runCurrent()
        assertEquals(StudioSecurityIntent.Internal.Observed(StudioSecurityLevel.Automatic), scope.sent.last())
        assertTrue(saving.isActive)
        release.complete(Unit)
        saving.join()
        runCurrent()
        val expected = if (isFailure) StudioSecurityLevel.Automatic else StudioSecurityLevel.Trust
        assertEquals(expected, store.value)
        assertEquals(!isFailure, StudioSecurityIntent.Internal.Saved in scope.sent)
        assertEquals(expected, scope.sent.filterIsInstance<StudioSecurityIntent.Internal.Observed>().last().level)
        observer.cancelAndJoin()
        assertEquals(0, store.levels.subscriptionCount.value)
    }

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
    override val levels = MutableStateFlow(StudioSecurityLevel.Ask)
    var value: StudioSecurityLevel
        get() = levels.value
        set(value) {
            levels.value = value
        }
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
