package io.aequicor.heartbeat.core.featuretoggles.impl

import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ToggleAdmissionTest {
    private val enabled = FeatureToggle.Flag("test.enabled", "Enabled", default = true)
    private val stores = FakeDataStores()
    private val registry = ToggleRegistry(setOf(enabled))
    private val overrides = ToggleOverrides(stores, registry)
    private val toggles = DataStoreFeatureToggles(registry, overrides)
    private val control = FeatureToggleControlImpl(registry, overrides)

    @Test
    fun `set reset and clear wait for admission delivery`() = runTest {
        val writers: List<suspend () -> Unit> = listOf(
            { control.setOverride(enabled, false) },
            { control.reset(enabled) },
            { control.resetAll() },
        )
        for (write in writers) {
            control.setOverride(enabled, true)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val admission = async {
                toggles.admitEnabled(setOf(enabled)) {
                    entered.complete(Unit)
                    release.await()
                    assertTrue(toggles.get(enabled))
                    true
                }
            }
            entered.await()
            val writer = async { write() }
            runCurrent()
            assertFalse(writer.isCompleted)
            release.complete(Unit)
            assertTrue(admission.await())
            writer.await()
        }
    }

    @Test
    fun `disabled flag and unreadable enabled default deny without delivering`() = runTest {
        control.setOverride(enabled, false)
        assertFalse(toggles.admitEnabled(setOf(enabled)) { error("must not deliver") })
        control.reset(enabled)
        val store = stores.keyValue(KeyValueSpec("core_feature_toggles")) as FakeKeyValueStore
        store.readFailure = IllegalStateException("unreadable")
        assertFalse(toggles.admitEnabled(setOf(enabled)) { error("must not deliver") })
    }

    @Test
    fun `cancellation releases admission for writers`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val admission = async {
            toggles.admitEnabled(setOf(enabled)) {
                entered.complete(Unit)
                CompletableDeferred<Unit>().await()
                true
            }
        }
        entered.await()
        admission.cancel()
        admission.join()
        control.setOverride(enabled, false)
        assertFalse(toggles.admitEnabled(setOf(enabled)) { error("must not deliver") })
    }

    @Test
    fun `action failure propagates and releases admission for a writer`() = runTest {
        val failure = IllegalStateException("delivery failed")
        val thrown = assertFailsWith<IllegalStateException> {
            toggles.admitEnabled(setOf(enabled)) { throw failure }
        }
        assertEquals(failure, thrown)
        control.setOverride(enabled, false)
        assertFalse(toggles.admitEnabled(setOf(enabled)) { error("must not deliver") })
    }
}
