package io.aequicor.heartbeat.feature.aiengine.connections.impl.domain

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthException
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailure
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectionOperation
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectionOperationId
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsEffect
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsIntent
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckGeneration
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckReceipt
import io.aequicor.heartbeat.feature.aiengine.connections.impl.RecordingScope
import io.aequicor.heartbeat.feature.aiengine.connections.impl.data.ModelCheckReportsKey
import io.aequicor.heartbeat.feature.aiengine.connections.impl.data.ModelSelectionGenerationsKey
import io.aequicor.heartbeat.feature.aiengine.connections.impl.data.ModelSelectionKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionEdit
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionMethodId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsModelChecksEffectsTest {
    private val id = ConnectionOperationId(42)

    @Test
    fun `stale source edit reports its conflict instead of an unknown error`() = runTest {
        val f = CoordinatorFixture(this, IsolatedPrompts { error("A rejected edit must not probe") })
        val failure = AuthFailure(AuthFailureReason.SourceChanged, CheckSource.info.id)
        f.facade.editSource = { _, _ -> throw AuthException(failure) }
        val request = ConnectionEdit(
            f.target.binding,
            ConnectionMethodId("local"),
            CheckSource,
            "Local",
            CheckSource.scope.origin,
        )
        val machine = RecordingScope<EngineConnectionsIntent>()

        f.settings().handle(EngineConnectionsEffect.Edit(request, id, emptySet(), setOf(f.target.binding)), machine)

        assertEquals(
            listOf<EngineConnectionsIntent>(
                EngineConnectionsIntent.Internal.ApplyFailed(EngineFailure.Authentication(failure), id),
            ),
            machine.intents,
        )
    }

    @Test
    fun `editing initializes legacy diagnostic generations and checks enabled models`() = runTest {
        var calls = 0
        val f = CoordinatorFixture(
            this,
            IsolatedPrompts {
                calls++
                answer(it.text)
            },
        )
        f.selections.update { it.withDefault(f.target) }
        f.disk.values.value -= ModelSelectionGenerationsKey.name
        f.facade.editSource = { edit, receipt -> receipt(edit.expected) }
        val request = ConnectionEdit(
            f.target.binding,
            ConnectionMethodId("local"),
            CheckSource,
            "Local",
            CheckSource.scope.origin,
        )

        f.settings().handle(
            EngineConnectionsEffect.Edit(request, id, setOf(f.target), setOf(f.target.binding)),
            RecordingScope(),
        )

        assertNotNull(f.selections.observe().first().checkGeneration(f.target))
        assertEquals(2, calls)
    }

    @Test
    fun `settings save preserves unrelated defaults and checks only the added model`() = runTest {
        var calls = 0
        val f = CoordinatorFixture(
            this,
            IsolatedPrompts {
                calls++
                assertEquals("added", it.target.model.value)
                answer(it.text)
            },
        )
        val before = f.selections.update { it.withDefault(f.target) }
        val added = f.target.copy(model = ModelId("added"))
        val machine = RecordingScope<EngineConnectionsIntent>()
        f.settings().handle(
            EngineConnectionsEffect.Execute(
                ConnectionOperation.SetModelsEnabled(
                    f.target.binding,
                    setOf(f.target.model, added.model),
                    setOf(f.target.model),
                ),
                id,
                checkTargets = setOf(added),
            ),
            machine,
        )
        assertEquals(2, calls)
        assertEquals(
            listOf<EngineConnectionsIntent>(
                EngineConnectionsIntent.Internal.Checking(id),
                EngineConnectionsIntent.Internal.Applied(id),
            ),
            machine.intents,
        )
        val saved = f.selections.observe().first()
        assertEquals(f.target, saved.defaultTarget)
        assertEquals(before.checkGeneration(f.target), saved.checkGeneration(f.target))
        runCurrent()
        assertEquals(added, f.coordinator.observe().first().single().revision.target)
    }

    @Test
    fun `retry with confirmed generations writes only the report key`() = runTest {
        var calls = 0
        val f = CoordinatorFixture(
            this,
            IsolatedPrompts {
                calls++
                answer(it.text)
            },
        )
        val before = f.selections.update { it.withDefault(f.target) }
        val writes = mutableListOf<String>()
        f.disk.beforeWrite = { writes += it }
        val machine = RecordingScope<EngineConnectionsIntent>()
        f.settings().handle(
            EngineConnectionsEffect.Execute(
                ConnectionOperation.CheckModel(f.target),
                id,
                checkTargets = setOf(f.target),
            ),
            machine,
        )
        runCurrent()
        assertEquals(2, calls)
        assertEquals(before, f.selections.observe().first())
        assertEquals(setOf(ModelCheckReportsKey.name), writes.toSet())
        assertTrue(f.facade.calls.isEmpty())
    }

    @Test
    fun `legacy retry initializes diagnostic generations without rewriting choices`() = runTest {
        var calls = 0
        val f = CoordinatorFixture(
            this,
            IsolatedPrompts {
                calls++
                answer(it.text)
            },
        )
        f.selections.update { it.withDefault(f.target) }
        val choices = f.disk.values.value.getValue(ModelSelectionKey.name)
        f.disk.values.value -= ModelSelectionGenerationsKey.name
        assertNull(f.selections.observe().first().checkGeneration(f.target))
        val writes = mutableListOf<String>()
        f.disk.beforeWrite = { writes += it }
        f.settings().handle(
            EngineConnectionsEffect.Execute(
                ConnectionOperation.CheckModel(f.target),
                id,
                checkTargets = setOf(f.target),
            ),
            RecordingScope(),
        )
        runCurrent()
        assertEquals(2, calls)
        assertNotNull(f.selections.observe().first().checkGeneration(f.target))
        assertEquals(f.target, f.selections.observe().first().defaultTarget)
        assertEquals(choices, f.disk.values.value.getValue(ModelSelectionKey.name))
        assertEquals(setOf(ModelSelectionGenerationsKey.name, ModelCheckReportsKey.name), writes.toSet())
        assertTrue(f.coordinator.observe().first().single().isPassed)
    }

    @Test
    fun `unconfirmed legacy generation cannot dispatch or borrow a generation from history`() = runTest {
        var calls = 0
        val f = CoordinatorFixture(
            this,
            IsolatedPrompts {
                calls++
                error("Unconfirmed generation must stay unknown")
            },
        )
        val selected = f.selections.update { it.withDefault(f.target) }
        val historical = checkReport().let {
            it.copy(
                revision = it.revision.copy(
                    target = f.target,
                    selection = ModelCheckGeneration(assertNotNull(selected.checkGeneration(f.target))),
                ),
            )
        }
        f.reports.put(historical)
        val choices = f.disk.values.value.getValue(ModelSelectionKey.name)
        f.disk.values.value -= ModelSelectionGenerationsKey.name
        f.disk.isCommitRejected = true
        val machine = RecordingScope<EngineConnectionsIntent>()
        f.settings().handle(
            EngineConnectionsEffect.Execute(
                ConnectionOperation.CheckModel(f.target),
                id,
                checkTargets = setOf(f.target),
            ),
            machine,
        )
        runCurrent()
        assertEquals(0, calls)
        assertEquals(EngineConnectionsIntent.Internal.Applied(id), machine.intents.last())
        assertNull(f.selections.observe().first().checkGeneration(f.target))
        assertEquals(choices, f.disk.values.value.getValue(ModelSelectionKey.name))
        assertTrue(f.coordinator.observe().first().isEmpty())
    }

    @Test
    fun `default only changes retain the previous current check without another request`() = runTest {
        var calls = 0
        val f = CoordinatorFixture(
            this,
            IsolatedPrompts {
                calls++
                answer(it.text)
            },
        )
        f.coordinator.saveAndCheck(setOf(f.target)) { f.selections.update { it.withDefault(f.target) } }
        runCurrent()
        val receipt = f.coordinator.observe().first().single()
        f.settings().handle(
            EngineConnectionsEffect.Execute(ConnectionOperation.SetDefaultModel(null), id),
            RecordingScope(),
        )
        runCurrent()
        assertEquals(2, calls)
        assertEquals(receipt, f.coordinator.observe().first().single())
    }

    @Test
    fun `binding disable and reenable cannot revive a receipt with an otherwise identical context`() = runTest {
        var calls = 0
        val f = CoordinatorFixture(
            this,
            IsolatedPrompts {
                calls++
                answer(it.text)
            },
        )
        f.coordinator.saveAndCheck(setOf(f.target)) { f.selections.update { it.withDefault(f.target) } }
        runCurrent()
        val old = f.coordinator.observe().first().single()
        val settings = f.settings()
        for (enabled in listOf(false, true)) {
            settings.handle(
                EngineConnectionsEffect.Execute(
                    ConnectionOperation.SetConnectionEnabled(f.target.binding, enabled),
                    id,
                ),
                RecordingScope(),
            )
        }
        assertEquals(2, calls)
        val context = ModelCheckContext(
            f.selections.observe().first(),
            f.facade.engines.state.value,
            f.facade.bindingsState.value,
            f.sources.state.value,
        )
        assertTrue(f.coordinator.current(listOf(old), context).isEmpty())
    }

    @Test
    fun `a second settings instance can remove a target while its old check is answering`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val f = CoordinatorFixture(
            this,
            IsolatedPrompts {
                calls++
                entered.complete(Unit)
                release.await()
                answer(it.text)
            },
        )
        f.selections.update { it.withDefault(f.target) }
        val first = async {
            f.settings().handle(
                EngineConnectionsEffect.Execute(
                    ConnectionOperation.CheckModel(f.target),
                    id,
                    checkTargets = setOf(f.target),
                ),
                RecordingScope(),
            )
        }
        entered.await()
        f.settings().handle(
            EngineConnectionsEffect.Execute(
                ConnectionOperation.SetModelEnabled(f.target, false),
                ConnectionOperationId(43),
                removedTargets = setOf(f.target),
            ),
            RecordingScope(),
        )
        release.complete(Unit)
        first.await()
        runCurrent()
        assertEquals(1, calls)
        assertFalse(f.selections.observe().first().isEnabled(f.target))
        assertTrue(f.coordinator.observe().first().isEmpty())
    }

    @Test
    fun `late save completion reconciles choices without a late phase or probe`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val f = CoordinatorFixture(this, IsolatedPrompts { error("No model IO after timeout") })
        f.disk.beforeWrite = {
            if (it == ModelSelectionKey.name) {
                entered.complete(Unit)
                release.await()
            }
        }
        val machine = RecordingScope<EngineConnectionsIntent>()
        val pending = async {
            f.settings().handle(
                EngineConnectionsEffect.Execute(
                    ConnectionOperation.SetModelEnabled(f.target, true),
                    id,
                    checkTargets = setOf(f.target),
                ),
                machine,
            )
        }
        entered.await()
        advanceTimeBy(5000)
        runCurrent()
        assertTrue(pending.isCompleted)
        assertEquals(
            listOf<EngineConnectionsIntent>(EngineConnectionsIntent.Internal.Applied(id, isSavePending = true)),
            machine.intents,
        )
        release.complete(Unit)
        runCurrent()
        assertTrue(f.selections.observe().first().isEnabled(f.target))
        assertEquals(1, machine.intents.size)
        assertTrue(f.coordinator.observe().first().isEmpty())
    }

    @Test
    fun `snapshot revalidates a held old report after ownership changes even if history flow stays stale`() = runTest {
        val f = CoordinatorFixture(this, IsolatedPrompts { answer(it.text) })
        f.coordinator.saveAndCheck(setOf(f.target)) { f.selections.update { it.withDefault(f.target) } }
        runCurrent()
        val old = f.coordinator.observe().first().single()
        val heldHistory = object : ModelChecks by f.coordinator {
            override fun observe(): Flow<List<ModelCheckReceipt>> = flowOf(listOf(old))
        }
        val machine = RecordingScope<EngineConnectionsIntent>()
        backgroundScope.launch { f.settings(heldHistory).handle(EngineConnectionsEffect.Observe, machine) }
        runCurrent()
        assertEquals(listOf(old), (machine.intents.last() as EngineConnectionsIntent.Internal.Snapshot).snapshot.checks)
        f.coordinator.revoke(f.target.binding)
        f.selections.update { it.copy(defaultTarget = null) }
        runCurrent()
        assertTrue((machine.intents.last() as EngineConnectionsIntent.Internal.Snapshot).snapshot.checks.isEmpty())
    }
}

private fun CoordinatorFixture.settings(checks: ModelChecks = coordinator): EngineConnectionsEffects =
    EngineConnectionsEffects(EngineServices(facade, sources), selections, checks)

private fun answer(text: String): IsolatedAnswer =
    IsolatedAnswer.Answered(if (text == ModelCheckEvaluation.RESPONSE_PROBE) "READY" else "19|cedar|ABSENT")
