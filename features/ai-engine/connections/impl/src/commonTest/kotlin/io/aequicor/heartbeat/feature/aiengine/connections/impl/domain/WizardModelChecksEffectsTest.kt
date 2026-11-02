package io.aequicor.heartbeat.feature.aiengine.connections.impl.domain

import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardEffect
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardIntent
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectionOperationId
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckIncomplete
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckOutcome
import io.aequicor.heartbeat.feature.aiengine.connections.impl.RecordingScope
import io.aequicor.heartbeat.feature.aiengine.connections.impl.data.ModelSelectionKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WizardModelChecksEffectsTest {
    private val operation = ConnectionOperationId(42)

    @Test
    fun `wizard saves and runs two real strict probes without treating a failed aspect as a save failure`() = runTest {
        var requests = 0
        val f = CoordinatorFixture(
            this,
            IsolatedPrompts {
                requests++
                assertNotNull(it.constraints)
                IsolatedAnswer.Answered(if (requests == 1) "READY" else "18|cedar|ABSENT")
            },
        )
        val machine = RecordingScope<ConnectWizardIntent>()
        val effects = ConnectWizardEffects(EngineServices(f.facade, f.sources), f.selections, f.coordinator)
        effects.handle(
            ConnectWizardEffect.SaveModels(
                f.target.binding,
                setOf(f.target.model),
                operation,
                checkTargets = setOf(f.target),
            ),
            machine,
        )
        assertEquals(2, requests)
        assertEquals(
            listOf<ConnectWizardIntent>(
                ConnectWizardIntent.Internal.Checking(operation),
                ConnectWizardIntent.Internal.Saved(operation),
            ),
            machine.intents,
        )
        runCurrent()
        assertTrue(f.selections.observe().first().isEnabled(f.target))
        val receipt = f.coordinator.observe().first().single()
        assertFalse(receipt.isPassed)
        assertEquals(ModelCheckOutcome.Passed, receipt.report?.response?.outcome)
        assertTrue(f.facade.calls.isEmpty(), "The checks must not discover models or create a chat")
    }

    @Test
    fun `deadline returns with accepted save pending and no late phase event`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val f = CoordinatorFixture(this, IsolatedPrompts { error("No probes after late save") })
        f.disk.beforeWrite = { key ->
            if (key == ModelSelectionKey.name) {
                entered.complete(Unit)
                release.await()
            }
        }
        val machine = RecordingScope<ConnectWizardIntent>()
        val effects = ConnectWizardEffects(EngineServices(f.facade, f.sources), f.selections, f.coordinator)
        val pending = async {
            effects.handle(
                ConnectWizardEffect.SaveModels(
                    f.target.binding,
                    setOf(f.target.model),
                    operation,
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
            listOf<ConnectWizardIntent>(ConnectWizardIntent.Internal.Saved(operation, isSavePending = true)),
            machine.intents,
        )
        release.complete(Unit)
        runCurrent()
        assertTrue(f.selections.observe().first().isEnabled(f.target))
        assertEquals(1, machine.intents.size)
        assertTrue(f.coordinator.observe().first().isEmpty())
    }

    @Test
    fun `a probe timeout preserves saved models and a current incomplete warning`() = runTest {
        val entered = CompletableDeferred<Unit>()
        var requests = 0
        val f = CoordinatorFixture(
            this,
            IsolatedPrompts {
                requests++
                entered.complete(Unit)
                awaitCancellation()
            },
        )
        val machine = RecordingScope<ConnectWizardIntent>()
        val effects = ConnectWizardEffects(EngineServices(f.facade, f.sources), f.selections, f.coordinator)
        val pending = async {
            effects.handle(
                ConnectWizardEffect.SaveModels(
                    f.target.binding,
                    setOf(f.target.model),
                    operation,
                    checkTargets = setOf(f.target),
                ),
                machine,
            )
        }
        entered.await()
        advanceTimeBy(5000)
        runCurrent()
        assertTrue(pending.isCompleted)
        assertEquals(ConnectWizardIntent.Internal.Saved(operation), machine.intents.last())
        assertEquals(1, requests)
        assertTrue(f.selections.observe().first().isEnabled(f.target))
        val report = assertNotNull(f.coordinator.observe().first().single().report)
        assertEquals(ModelCheckOutcome.Incomplete(ModelCheckIncomplete.Timeout), report.response.outcome)
        assertEquals(ModelCheckOutcome.Incomplete(ModelCheckIncomplete.Timeout), report.accuracy.outcome)
    }

    @Test
    fun `rejected save reports its operation and keeps the connection`() = runTest {
        val f = CoordinatorFixture(this, IsolatedPrompts { error("Failed save must not dispatch") })
        f.disk.isWriteRejected = true
        val machine = RecordingScope<ConnectWizardIntent>()
        ConnectWizardEffects(EngineServices(f.facade, f.sources), f.selections, f.coordinator).handle(
            ConnectWizardEffect.SaveModels(
                f.target.binding,
                setOf(f.target.model),
                operation,
                checkTargets = setOf(f.target),
            ),
            machine,
        )
        assertEquals(
            listOf<ConnectWizardIntent>(ConnectWizardIntent.Internal.SaveFailed(EngineFailure.Unknown(), operation)),
            machine.intents,
        )
        assertFalse(f.selections.observe().first().isEnabled(f.target))
        assertEquals(1, f.facade.bindingsState.value.size)
    }

    @Test
    fun `draft delta preserves concurrent choices and their default while checking only the added model`() = runTest {
        var requests = 0
        val f = CoordinatorFixture(
            this,
            IsolatedPrompts {
                requests++
                assertEquals("b", it.target.model.value)
                IsolatedAnswer.Answered(if (requests == 1) "READY" else "19|cedar|ABSENT")
            },
        )
        val a = f.target.copy(model = ModelId("a"))
        val b = f.target.copy(model = ModelId("b"))
        val c = f.target.copy(model = ModelId("c"))
        val concurrent = f.target.copy(model = ModelId("concurrent"))
        val initial = f.selections.update {
            it.withEnabled(f.target.binding, setOf(a.model, c.model, concurrent.model)).withDefault(concurrent)
        }
        val machine = RecordingScope<ConnectWizardIntent>()
        ConnectWizardEffects(EngineServices(f.facade, f.sources), f.selections, f.coordinator).handle(
            ConnectWizardEffect.SaveModels(
                f.target.binding,
                setOf(a.model, b.model),
                operation,
                previous = setOf(a.model, c.model),
                checkTargets = setOf(b),
                removedTargets = setOf(c),
            ),
            machine,
        )
        val saved = f.selections.observe().first()
        assertEquals(setOf(a.model, b.model, concurrent.model), saved.enabled(f.target.binding))
        assertEquals(concurrent, saved.defaultTarget)
        assertEquals(initial.checkGeneration(concurrent), saved.checkGeneration(concurrent))
        assertEquals(2, requests)
        runCurrent()
        assertEquals(b, f.coordinator.observe().first().single().revision.target)
    }
}
