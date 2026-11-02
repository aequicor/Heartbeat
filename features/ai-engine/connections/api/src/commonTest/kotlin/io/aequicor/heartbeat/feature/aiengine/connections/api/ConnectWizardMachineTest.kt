package io.aequicor.heartbeat.feature.aiengine.connections.api

import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardIntent.Internal
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardIntent.Public
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState.Cancelled
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState.ChoosingEngine
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState.ChoosingMethod
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState.ChoosingModels
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState.Connecting
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState.Finished
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState.Idle
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState.RollingBack
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState.Saving
import io.aequicor.heartbeat.feature.aiengine.connections.api.TestData.apiKey
import io.aequicor.heartbeat.feature.aiengine.connections.api.TestData.codex
import io.aequicor.heartbeat.feature.aiengine.connections.api.TestData.connection
import io.aequicor.heartbeat.feature.aiengine.connections.api.TestData.koog
import io.aequicor.heartbeat.feature.aiengine.connections.api.TestData.local
import io.aequicor.heartbeat.feature.aiengine.connections.api.TestData.mobileOnly
import io.aequicor.heartbeat.feature.aiengine.connections.api.TestData.model
import io.aequicor.heartbeat.feature.aiengine.connections.api.TestData.noMethods
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ConnectWizardMachineTest {
    private val spec = ConnectWizardMachineSpec
    private val engines = listOf(koog, codex, mobileOnly, noMethods)
    private val failure = EngineFailure.Engine(EngineFailureReason.RequirementsNotMet)

    @Test
    fun `start observes the engine catalog`() {
        spec.assertTransition(
            Idle,
            Public.Start(koog.descriptor.id),
            ChoosingEngine(preselected = koog.descriptor.id),
            effects = listOf(ConnectWizardEffect.ObserveEngines),
        )
    }

    @Test
    fun `a preselected connectable engine skips the first step`() {
        spec.assertTransition(
            ChoosingEngine(preselected = koog.descriptor.id),
            Internal.EnginesChanged(engines),
            ChoosingMethod(koog),
        )
        spec.assertTransition(
            ChoosingEngine(preselected = mobileOnly.descriptor.id),
            Internal.EnginesChanged(engines),
            ChoosingEngine(engines),
        )
    }

    @Test
    fun `an empty catalog keeps the preselection until the real one arrives`() {
        val waiting = ChoosingEngine(emptyList(), preselected = koog.descriptor.id)
        spec.assertTransition(
            ChoosingEngine(preselected = koog.descriptor.id),
            Internal.EnginesChanged(emptyList()),
            waiting,
        )
        spec.assertTransition(waiting, Internal.EnginesChanged(engines), ChoosingMethod(koog))
    }

    @Test
    fun `a preselection that was not connectable is dropped`() {
        spec.assertTransition(
            ChoosingEngine(engines),
            Internal.EnginesChanged(listOf(koog)),
            ChoosingEngine(listOf(koog)),
        )
    }

    @Test
    fun `only connectable engines can be chosen`() {
        val choosing = ChoosingEngine(engines)
        spec.assertTransition(choosing, Public.ChooseEngine(codex.descriptor.id), ChoosingMethod(codex))
        spec.assertIgnored(choosing, Public.ChooseEngine(mobileOnly.descriptor.id))
        spec.assertIgnored(choosing, Public.ChooseEngine(noMethods.descriptor.id))
    }

    @Test
    fun `catalog failure is retried explicitly`() {
        val failed = ChoosingEngine(engines, failure = failure)
        spec.assertTransition(ChoosingEngine(engines), Internal.EnginesFailed(failure), failed)
        spec.assertTransition(
            failed,
            Public.Retry,
            ChoosingEngine(engines),
            effects = listOf(ConnectWizardEffect.ObserveEngines),
        )
        spec.assertIgnored(ChoosingEngine(engines), Public.Retry)
    }

    @Test
    fun `a connection check runs once and keeps the method step`() {
        val lan = CredentialInput.Existing("LAN", EndpointOrigin("http://192.168.1.5:11434"))
        val running = ChoosingMethod(koog, check = ConnectionCheck.Running)
        spec.assertTransition(
            ChoosingMethod(koog, failure),
            Public.CheckConnection(local.id, lan),
            running,
            effects = listOf(ConnectWizardEffect.CheckConnection(koog.descriptor.id, local, lan)),
        )
        spec.assertIgnored(running, Public.CheckConnection(local.id, lan))
        spec.assertIgnored(running, Public.Connect(local.id, lan))
        spec.assertIgnored(ChoosingMethod(koog), Public.CheckConnection(local.id, lan.copy(label = " ")))
        val passed = ConnectionCheck.Succeeded(3)
        spec.assertTransition(running, Internal.ConnectionChecked(passed), ChoosingMethod(koog, check = passed))
        spec.assertIgnored(ChoosingMethod(koog), Internal.ConnectionChecked(passed))
        spec.assertIgnored(running, Internal.ConnectionChecked(ConnectionCheck.Running))
        val rejected = ConnectionCheck.Failed(failure)
        spec.assertTransition(running, Internal.ConnectionChecked(rejected), ChoosingMethod(koog, check = rejected))
    }

    @Test
    fun `leaving the method step during a check returns to the engines`() {
        val running = ChoosingMethod(koog, check = ConnectionCheck.Running)
        val engines = listOf(ConnectWizardEffect.ObserveEngines)
        spec.assertTransition(running, Public.Back, ChoosingEngine(), effects = engines)
        spec.assertTransition(running, Public.Dismiss, ChoosingEngine(), effects = engines)
    }

    @Test
    fun `connect requires a credential fitting the method`() {
        Secret("sk".toCharArray()).use { key ->
            val credential = CredentialInput.ApiKey("Work", apiKey.origin, key)
            spec.assertTransition(
                ChoosingMethod(koog),
                Public.Connect(apiKey.id, credential),
                Connecting(koog, apiKey.id),
                effects = listOf(ConnectWizardEffect.Connect(koog.descriptor.id, apiKey, credential)),
            )
            val proxy = CredentialInput.ApiKey("Work", EndpointOrigin("https://proxy.example.com"), key)
            spec.assertIgnored(ChoosingMethod(koog), Public.Connect(apiKey.id, proxy))
            spec.assertIgnored(ChoosingMethod(koog), Public.Connect(local.id, credential))
            spec.assertIgnored(ChoosingMethod(codex), Public.Connect(apiKey.id, credential))
        }
        val lan = CredentialInput.Existing("LAN", EndpointOrigin("http://192.168.1.5:11434"))
        spec.assertTransition(
            ChoosingMethod(koog),
            Public.Connect(local.id, lan),
            Connecting(koog, local.id),
            effects = listOf(ConnectWizardEffect.Connect(koog.descriptor.id, local, lan)),
        )
        spec.assertIgnored(ChoosingMethod(koog), Public.Connect(local.id, lan.copy(label = " ")))
    }

    @Test
    fun `connection result leads to model discovery or back to the method`() {
        spec.assertTransition(
            Connecting(koog, apiKey.id),
            Internal.Connected(connection),
            ChoosingModels(koog.descriptor.id, connection),
            effects = listOf(ConnectWizardEffect.DiscoverModels(koog.descriptor.id, connection.binding)),
        )
        spec.assertTransition(
            Connecting(koog, apiKey.id),
            Internal.ConnectFailed(failure),
            ChoosingMethod(koog, failure),
        )
        spec.assertIgnored(Connecting(koog, apiKey.id), Public.Cancel)
    }

    @Test
    fun `a reused connection starts model selection from its saved models`() {
        val saved = setOf(ModelId("gpt-a"))
        val reused = connection.copy(isCreated = false, initialModels = saved)
        spec.assertTransition(
            Connecting(koog, apiKey.id),
            Internal.Connected(reused),
            ChoosingModels(koog.descriptor.id, reused, selected = saved),
            effects = listOf(ConnectWizardEffect.DiscoverModels(koog.descriptor.id, reused.binding)),
        )
    }

    @Test
    fun `models are chosen from discovered ones only`() {
        val discovering = ChoosingModels(koog.descriptor.id, connection)
        val models = listOf(model("gpt-a"), model("gpt-b"))
        val loaded = discovering.copy(models = models)
        spec.assertTransition(discovering, Internal.ModelsLoaded(models), loaded)
        spec.assertIgnored(discovering, Public.ToggleModel(ModelId("gpt-a")))
        spec.assertIgnored(loaded, Public.ToggleModel(ModelId("unknown")))
        spec.assertTransition(
            loaded,
            Public.ToggleModel(ModelId("gpt-a")),
            loaded.copy(selected = setOf(ModelId("gpt-a"))),
        )
        spec.assertTransition(
            loaded,
            Public.SelectAllModels(isSelected = true),
            loaded.copy(selected = setOf(ModelId("gpt-a"), ModelId("gpt-b"))),
        )
        spec.assertTransition(
            loaded.copy(selected = setOf(ModelId("gpt-a"))),
            Internal.ModelsLoaded(listOf(model("gpt-b"))),
            loaded.copy(models = listOf(model("gpt-b"))),
        )
    }

    @Test
    fun `failed discovery can be retried or skipped`() {
        val failed = ChoosingModels(koog.descriptor.id, connection, failure = failure)
        spec.assertTransition(ChoosingModels(koog.descriptor.id, connection), Internal.ModelsFailed(failure), failed)
        spec.assertTransition(
            failed,
            Public.Retry,
            ChoosingModels(koog.descriptor.id, connection),
            effects = listOf(ConnectWizardEffect.DiscoverModels(koog.descriptor.id, connection.binding)),
        )
        spec.assertTransition(
            failed,
            Public.Finish,
            Saving(koog.descriptor.id, connection, null, emptySet()),
            effects = listOf(ConnectWizardEffect.SaveModels(connection.binding, emptySet())),
        )
        spec.assertIgnored(ChoosingModels(koog.descriptor.id, connection), Public.Finish)
    }

    @Test
    fun `saving completes the wizard`() {
        val selected = setOf(ModelId("gpt-a"))
        val saving = Saving(koog.descriptor.id, connection, listOf(model("gpt-a")), selected)
        spec.assertTransition(
            saving,
            Internal.Saved(ConnectionOperationId(1)),
            Finished(connection.binding),
            outputs = listOf(ConnectWizardOutput.Completed(connection.binding)),
        )
        spec.assertTransition(
            saving,
            Internal.SaveFailed(failure, ConnectionOperationId(1)),
            ChoosingModels(
                koog.descriptor.id,
                connection,
                listOf(model("gpt-a")),
                selected,
                failure,
                operationSequence = 1,
            ),
        )
        spec.assertIgnored(saving, Public.Cancel)
        spec.assertIgnored(saving, Public.Dismiss)
        spec.assertIgnored(saving, Public.Finish)
    }

    @Test
    fun `checking stays in the saving state and stale operation results are ignored`() {
        val saving = Saving(koog.descriptor.id, connection, listOf(model("gpt-a")), setOf(ModelId("gpt-a")))
        spec.assertTransition(
            saving,
            Internal.Checking(saving.operation),
            saving.copy(phase = ModelSavePhase.Checking),
        )
        assertFalse(requireNotNull(spec.resolve(saving, Internal.Checking(saving.operation))).isStateChange)
        val stale = ConnectionOperationId(2)
        spec.assertIgnored(saving, Internal.Checking(stale))
        spec.assertIgnored(saving, Internal.Saved(stale))
        spec.assertIgnored(saving, Internal.SaveFailed(failure, stale))
        spec.assertIgnored(saving.copy(phase = ModelSavePhase.Checking), Internal.Saved(stale))
    }

    @Test
    fun `a pending accepted save closes without claiming rollback or completed persistence`() {
        val saving = Saving(koog.descriptor.id, connection, emptyList(), emptySet())
        spec.assertTransition(
            saving,
            Internal.Saved(saving.operation, isSavePending = true),
            Finished(connection.binding, isSavePending = true),
            outputs = listOf(ConnectWizardOutput.Completed(connection.binding, isSavePending = true)),
        )
    }

    @Test
    fun `a save retry keeps its sequence so a result from the first attempt cannot finish it`() {
        val selected = setOf(ModelId("gpt-a"))
        val models = listOf(model("gpt-a"))
        val failed = ChoosingModels(koog.descriptor.id, connection, models, selected, failure, operationSequence = 1)
        val retried = Saving(koog.descriptor.id, connection, models, selected, ConnectionOperationId(2))
        spec.assertTransition(
            failed,
            Public.Retry,
            retried,
            effects = listOf(
                ConnectWizardEffect.SaveModels(
                    connection.binding,
                    selected,
                    ConnectionOperationId(2),
                    checkTargets = setOf(model("gpt-a").target),
                ),
            ),
        )
        spec.assertIgnored(retried, Internal.Saved(ConnectionOperationId(1)))
        spec.assertIgnored(retried, Internal.SaveFailed(failure, ConnectionOperationId(1)))
    }

    @Test
    fun `reused connection checks only added models and revokes only explicitly removed ones`() {
        val a = ModelId("a")
        val b = ModelId("b")
        val c = ModelId("c")
        val reused = connection.copy(isCreated = false, initialModels = setOf(a, c))
        val models = listOf(model("a"), model("b"), model("c"))
        spec.assertTransition(
            ChoosingModels(koog.descriptor.id, reused, models, setOf(a, b)),
            Public.Finish,
            Saving(koog.descriptor.id, reused, models, setOf(a, b)),
            effects = listOf(
                ConnectWizardEffect.SaveModels(
                    connection.binding,
                    setOf(a, b),
                    previous = setOf(a, c),
                    checkTargets = setOf(model("b").target),
                    removedTargets = setOf(model("c").target),
                ),
            ),
        )
    }

    @Test
    fun `save effect failures retain the operation id`() {
        val operation = ConnectionOperationId(42)
        assertEquals(
            Internal.SaveFailed(failure, operation),
            spec.onEffectFailure(
                ConnectWizardEffect.SaveModels(connection.binding, emptySet(), operation),
                EngineException(failure),
            ),
        )
    }

    @Test
    fun `retry after a failed save saves again and keeps the discovered models`() {
        val selected = setOf(ModelId("gpt-a"))
        val models = listOf(model("gpt-a"))
        spec.assertTransition(
            ChoosingModels(koog.descriptor.id, connection, models, selected, failure),
            Public.Retry,
            Saving(koog.descriptor.id, connection, models, selected),
            effects = listOf(
                ConnectWizardEffect.SaveModels(
                    connection.binding,
                    selected,
                    checkTargets = setOf(model("gpt-a").target),
                ),
            ),
        )
    }

    @Test
    fun `dismiss steps back cancels or rolls back depending on the step`() {
        spec.assertTransition(
            ChoosingMethod(koog),
            Public.Dismiss,
            ChoosingEngine(),
            effects = listOf(ConnectWizardEffect.ObserveEngines),
        )
        spec.assertTransition(
            ChoosingEngine(engines),
            Public.Dismiss,
            Cancelled,
            outputs = listOf(ConnectWizardOutput.Cancelled),
        )
        spec.assertTransition(Idle, Public.Dismiss, Cancelled, outputs = listOf(ConnectWizardOutput.Cancelled))
        spec.assertTransition(
            ChoosingModels(koog.descriptor.id, connection),
            Public.Dismiss,
            RollingBack(connection),
            effects = listOf(ConnectWizardEffect.Rollback(connection)),
        )
        spec.assertIgnored(Connecting(koog, apiKey.id), Public.Dismiss)
        spec.assertIgnored(RollingBack(connection), Public.Dismiss)
        spec.assertIgnored(RollingBack(connection), Public.Cancel)
    }

    @Test
    fun `terminal states ignore further steps`() {
        spec.assertIgnored(Finished(connection.binding), Public.Start())
        spec.assertIgnored(Finished(connection.binding), Public.Dismiss)
        spec.assertIgnored(Cancelled, Public.Start())
        spec.assertIgnored(Cancelled, Public.Cancel)
    }

    @Test
    fun `cancel rolls back a created connection`() {
        spec.assertTransition(
            ChoosingModels(koog.descriptor.id, connection),
            Public.Cancel,
            RollingBack(connection),
            effects = listOf(ConnectWizardEffect.Rollback(connection)),
        )
        spec.assertTransition(
            RollingBack(connection),
            Internal.RolledBack,
            Cancelled,
            outputs = listOf(ConnectWizardOutput.Cancelled),
        )
        spec.assertTransition(
            ChoosingMethod(koog),
            Public.Cancel,
            Cancelled,
            outputs = listOf(ConnectWizardOutput.Cancelled),
        )
    }

    @Test
    fun `back returns to the engine step and observes again`() {
        spec.assertTransition(
            ChoosingMethod(koog, failure),
            Public.Back,
            ChoosingEngine(),
            effects = listOf(ConnectWizardEffect.ObserveEngines),
        )
    }

    @Test
    fun `effect failures keep their domain classification`() {
        val connect = ConnectWizardEffect.Connect(
            koog.descriptor.id,
            local,
            CredentialInput.Existing("L", local.origin),
        )
        val resolve = { effect: ConnectWizardEffect, error: Throwable -> spec.onEffectFailure(effect, error) }
        assertEquals(
            Internal.ConnectFailed(failure),
            resolve(connect, EngineException(failure)),
        )
        assertEquals(
            Internal.ModelsFailed(EngineFailure.Unknown()),
            resolve(
                ConnectWizardEffect.DiscoverModels(koog.descriptor.id, connection.binding),
                IllegalStateException(),
            ),
        )
        assertEquals(Internal.RolledBack, resolve(ConnectWizardEffect.Rollback(connection), IllegalStateException()))
    }
}
