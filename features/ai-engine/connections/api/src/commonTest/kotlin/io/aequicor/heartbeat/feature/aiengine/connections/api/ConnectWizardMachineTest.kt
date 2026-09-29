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
            Internal.Saved,
            Finished(connection.binding),
            outputs = listOf(ConnectWizardOutput.Completed(connection.binding)),
        )
        spec.assertTransition(
            saving,
            Internal.SaveFailed(failure),
            ChoosingModels(koog.descriptor.id, connection, listOf(model("gpt-a")), selected, failure),
        )
        spec.assertIgnored(saving, Public.Cancel)
        spec.assertIgnored(saving, Public.Dismiss)
        spec.assertIgnored(saving, Public.Finish)
    }

    @Test
    fun `retry after a failed save saves again and keeps the discovered models`() {
        val selected = setOf(ModelId("gpt-a"))
        val models = listOf(model("gpt-a"))
        spec.assertTransition(
            ChoosingModels(koog.descriptor.id, connection, models, selected, failure),
            Public.Retry,
            Saving(koog.descriptor.id, connection, models, selected),
            effects = listOf(ConnectWizardEffect.SaveModels(connection.binding, selected)),
        )
    }

    @Test
    fun `dismiss steps back, cancels or rolls back depending on the step`() {
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
