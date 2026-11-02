package io.aequicor.heartbeat.feature.aiengine.connections.impl.domain

import androidx.room.RoomDatabase
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.feature.aiengine.connections.impl.FakeAuthSources
import io.aequicor.heartbeat.feature.aiengine.connections.impl.FakeEngineFacade
import io.aequicor.heartbeat.feature.aiengine.connections.impl.KoogId
import io.aequicor.heartbeat.feature.aiengine.connections.impl.data.MemorySelectionStore
import io.aequicor.heartbeat.feature.aiengine.connections.impl.data.ModelCheckReportsKey
import io.aequicor.heartbeat.feature.aiengine.connections.impl.data.StoredModelCheckReports
import io.aequicor.heartbeat.feature.aiengine.connections.impl.data.StoredModelSelections
import io.aequicor.heartbeat.feature.aiengine.connections.impl.engineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ModelChecksCoordinatorTest {
    @Test
    fun `a real saved selection is checked and persists only a text free successful receipt`() = runTest {
        var calls = 0
        val f = fixture(
            this,
            IsolatedPrompts {
                calls++
                IsolatedAnswer.Answered(
                    if (it.text == ModelCheckEvaluation.RESPONSE_PROBE) "READY" else "19|cedar|ABSENT",
                )
            },
        )
        val result = assertIs<ModelCheckBatchResult.Completed<*>>(
            f.coordinator.saveAndCheck(setOf(f.target)) { f.selections.update { it.withDefault(f.target) } },
        )
        assertEquals(2, calls)
        assertEquals(1, result.reports.size)
        runCurrent()
        val receipt = f.coordinator.observe().first().single()
        assertTrue(receipt.isPassed)
        assertNull(receipt.report)
        assertEquals(f.target, f.selections.observe().first().defaultTarget)
        assertEquals(result.reports.single().run, receipt.run)
    }

    @Test
    fun `retry supersedes an in flight run and its late answer cannot replace the new completion`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val f = fixture(
            this,
            IsolatedPrompts {
                if (++calls == 1) {
                    entered.complete(Unit)
                    release.await()
                }
                IsolatedAnswer.Answered(
                    if (it.text == ModelCheckEvaluation.RESPONSE_PROBE) "READY" else "19|cedar|ABSENT",
                )
            },
        )
        val first = async {
            f.coordinator.saveAndCheck(
                setOf(f.target),
            ) { f.selections.update { it.withDefault(f.target) } }
        }
        entered.await()
        val second = assertIs<ModelCheckBatchResult.Completed<*>>(
            f.coordinator.saveAndCheck(setOf(f.target)) { f.selections.update { it } },
        )
        runCurrent()
        val current = f.coordinator.observe().first().single()
        assertEquals(second.reports.single().run, current.run)
        release.complete(Unit)
        assertTrue(assertIs<ModelCheckBatchResult.Completed<*>>(first.await()).reports.isEmpty())
        runCurrent()
        assertEquals(current, f.coordinator.observe().first().single())
        assertEquals(3, calls)
    }

    @Test
    fun `pending publication cannot hold the UI and a revoked commit cannot overwrite a new run`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val f = fixture(this)
        var publications = 0
        f.disk.beforeWrite = { key ->
            if (key == ModelCheckReportsKey.name && ++publications == 1) {
                entered.complete(Unit)
                release.await()
            }
        }
        val first = f.coordinator.saveAndCheck(setOf(f.target)) { f.selections.update { it.withDefault(f.target) } }
        assertIs<ModelCheckBatchResult.Completed<*>>(first)
        entered.await()
        f.coordinator.revoke(f.target.binding)
        val second = assertIs<ModelCheckBatchResult.Completed<*>>(
            f.coordinator.saveAndCheck(setOf(f.target)) { f.selections.update { it } },
        )
        assertNotEquals(first.reports.single().run, second.reports.single().run)
        release.complete(Unit)
        runCurrent()
        assertEquals(second.reports.single().run, f.coordinator.observe().first().single().run)
        assertEquals(second.reports.single().run, f.reports.observe().first().single().run)
    }

    @Test
    fun `opening diagnostics neither saves choices nor invokes a model`() = runTest {
        val f = fixture(this, IsolatedPrompts { error("Observation must never request a model") })
        assertTrue(f.coordinator.observe().first().isEmpty())
        assertEquals(0, f.disk.writes)
        assertTrue(f.facade.calls.isEmpty())
    }

    @Test
    fun `changing canonical source or disabling a binding hides a previously current report`() = runTest {
        val f = fixture(this)
        f.coordinator.saveAndCheck(setOf(f.target)) { f.selections.update { it.withDefault(f.target) } }
        runCurrent()
        assertEquals(1, f.coordinator.observe().first().size)
        f.sources.state.value = emptyList()
        assertTrue(f.coordinator.observe().first().isEmpty())
        f.sources.state.value = listOf(CheckSource)
        f.facade.bindingsState.value = f.facade.bindingsState.value.map { it.copy(isEnabled = false) }
        assertTrue(f.coordinator.observe().first().isEmpty())
    }

    @Test
    fun `failed report persistence does not undo the actual saved choices`() = runTest {
        val f = fixture(this)
        f.disk.beforeWrite = { if (it == ModelCheckReportsKey.name) error("storage-failure-canary") }
        val result = f.coordinator.saveAndCheck(setOf(f.target)) { f.selections.update { it.withDefault(f.target) } }
        assertIs<ModelCheckBatchResult.Completed<*>>(result)
        runCurrent()
        assertEquals(f.target, f.selections.observe().first().defaultTarget)
        assertTrue(f.coordinator.observe().first().isEmpty())
        assertFalse(result.toString().contains("canary"))
    }
}

private fun fixture(
    scope: TestScope,
    prompts: IsolatedPrompts = IsolatedPrompts {
        IsolatedAnswer.Answered(if (it.text == ModelCheckEvaluation.RESPONSE_PROBE) "READY" else "19|cedar|ABSENT")
    },
): CoordinatorFixture = CoordinatorFixture(scope, prompts)

private class CoordinatorFixture(scope: TestScope, prompts: IsolatedPrompts) {
    val target = checkReport().revision.target.copy(engine = KoogId)
    val disk = MemorySelectionStore()
    private val stores = object : DataStores {
        override val owner = StorageOwner.App
        override fun filesDirectory(name: String): String = error("Not used")
        override fun keyValue(spec: KeyValueSpec): KeyValueStore = disk
        override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("Not used")
        override suspend fun fire(event: DataEvent) = Unit
    }
    val selections = StoredModelSelections(stores)
    val reports = StoredModelCheckReports(stores)
    val facade = FakeEngineFacade().apply {
        val binding = EngineBinding(target.binding, target.engine, CheckSource.info.id)
        bindingsState.value = listOf(binding)
        catalog.value = listOf(engineInfo(listOf(binding)))
    }
    val sources = FakeAuthSources().apply { state.value = listOf(CheckSource) }
    val coordinator = ModelChecksCoordinator(
        EngineServices(facade, sources),
        selections,
        reports,
        scope.backgroundScope,
        ModelCheckBatch(scope.backgroundScope, ModelCheckProbes(prompts), scope.testScheduler.timeSource),
    )
}
