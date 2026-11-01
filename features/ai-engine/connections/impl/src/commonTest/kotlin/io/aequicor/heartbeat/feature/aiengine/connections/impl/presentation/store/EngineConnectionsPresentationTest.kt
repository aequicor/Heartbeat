package io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceInfo
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectionOperation
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectionsSnapshot
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsState
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelection
import io.aequicor.heartbeat.feature.aiengine.connections.impl.KoogId
import io.aequicor.heartbeat.feature.aiengine.connections.impl.OllamaMethod
import io.aequicor.heartbeat.feature.aiengine.connections.impl.engineInfo
import io.aequicor.heartbeat.feature.aiengine.connections.impl.modelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.DisabledReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineEnablement
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFamily
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagementState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalogSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.Observation
import io.aequicor.heartbeat.feature.aiengine.facade.api.scopeFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

internal fun settingsSnapshot(): ConnectionsSnapshot {
    val home = EngineBinding(EngineBindingId("home"), KoogId, AuthSourceId("home-source"))
    val work = EngineBinding(EngineBindingId("work"), KoogId, AuthSourceId("work-source"), isEnabled = false)
    val source = AuthSource.NoAuth(
        AuthSourceInfo(AuthSourceId("home-source"), "Home server", AuthRevision.Unknown),
        OllamaMethod.scopeFor(),
    )
    val models = listOf(modelInfo(home.id, "llama"), modelInfo(home.id, "qwen"))
    val selection = ModelSelection().withDefault(models.first().target)
    return ConnectionsSnapshot(
        engines = listOf(engineInfo(listOf(home, work))),
        sources = listOf(source),
        models = mapOf(
            home.id to ModelCatalogSnapshot(models, Observation(Instant.fromEpochSeconds(1), isStale = false)),
        ),
        selection = selection,
    )
}

/** [settingsSnapshot] with engine management on: Koog (on) and Codex, switched off and never connected. */
internal fun managedSettingsSnapshot(): ConnectionsSnapshot {
    val base = settingsSnapshot()
    val koog = base.engines.single()
    val codex = EngineDescriptor(
        EngineId("codex"),
        "Codex",
        EngineFamily.Vendor,
        setOf(EnginePlatform.DesktopMacOs),
        FeatureToggle.Flag("ai.codex", "Codex"),
    )
    val switchedOff = EngineEnablement(isUserEnabled = false, reasons = setOf(DisabledReason.DisabledByUser))
    val engines = listOf(
        ManagedEngine(koog.descriptor, ManagementSpec(), EngineEnablement(), koog.availability),
        ManagedEngine(codex, ManagementSpec(), switchedOff, EngineAvailability.Available),
    )
    return base.copy(management = EngineManagementState(true, EnginePlatform.DesktopMacOs, engines))
}

class EngineConnectionsPresentationTest {
    private val loaded = EngineConnectionsScreenState().reflect(EngineConnectionsState.Active(settingsSnapshot()))

    @Test
    fun `first engine and connection are focused with provider names and model choice`() {
        assertEquals("koog", loaded.selectedEngine)
        assertEquals("home", loaded.selectedConnection)
        val home = loaded.connections.first()
        assertEquals(
            ConnectionRowUi("home", "Home server", "Ollama", MethodKindUi.NoAuth, OllamaMethod.origin.value, true, 1),
            home,
        )
        assertEquals("work-source", loaded.connections[1].label)
        val models = loaded.models!!.models
        assertEquals(listOf(true, false), models.map { it.isEnabled })
        assertTrue(models.first().isDefault)
    }

    @Test
    fun `focus survives snapshots and falls back when its entry disappears`() {
        val focused = loaded.copy(
            selectedConnection = "work",
        ).reflect(EngineConnectionsState.Active(settingsSnapshot()))
        assertEquals("work", focused.selectedConnection)
        assertTrue(focused.models!!.isNeverSynced)
        val gone = loaded.copy(
            selectedConnection = "removed",
        ).reflect(EngineConnectionsState.Active(settingsSnapshot()))
        assertEquals("home", gone.selectedConnection)
    }

    @Test
    fun `model edits stay local until save targets the focused connection`() {
        val edited = loaded.editModel("qwen", true)
        assertEquals(listOf(true, true), edited.models!!.models.map { it.isEnabled })
        assertEquals(
            ConnectionOperation.SetModelsEnabled(
                EngineBindingId("home"),
                setOf(ModelId("llama"), ModelId("qwen")),
                previous = setOf(ModelId("llama")),
            ),
            edited.operationFor(EngineConnectionsScreenIntent.SaveModels),
        )
        assertEquals(
            ConnectionOperation.Disconnect(EngineBindingId("home")),
            loaded.copy(confirmDisconnect = "home").operationFor(EngineConnectionsScreenIntent.ConfirmDisconnect),
        )
        assertNull(loaded.operationFor(EngineConnectionsScreenIntent.ConfirmDisconnect))
        assertNull(EngineConnectionsScreenState().operationFor(EngineConnectionsScreenIntent.RefreshModels))
    }

    @Test
    fun `model draft survives pending and clears after its saved snapshot arrives`() {
        val edited = loaded.editModel("qwen", true)
        val operation = ConnectionOperation.SetModelsEnabled(
            EngineBindingId("home"),
            setOf(ModelId("llama"), ModelId("qwen")),
            previous = setOf(ModelId("llama")),
        )
        val pending = edited.reflect(
            EngineConnectionsState.Active(settingsSnapshot(), pending = operation),
        )
        assertEquals<Set<String>?>(setOf("llama", "qwen"), pending.modelDraft)
        assertTrue(pending.isModelSavePending)
        val savedSnapshot = settingsSnapshot().let { snapshot ->
            val target = modelInfo(EngineBindingId("home"), "qwen").target
            snapshot.copy(selection = snapshot.selection.withModel(target, true))
        }
        val saved = pending.reflect(EngineConnectionsState.Active(savedSnapshot))
        assertNull(saved.modelDraft)
        assertFalse(saved.isModelSavePending)
    }

    @Test
    fun `pending draft rebases onto concurrent changes and clears only after its delta is saved`() {
        val binding = EngineBindingId("home")
        val removed = modelInfo(binding, "llama").target
        val concurrent = modelInfo(binding, "qwen").target
        val edited = loaded.editModel(removed.model.value, false)
        val operation = ConnectionOperation.SetModelsEnabled(
            binding,
            emptySet(),
            previous = setOf(removed.model),
        )
        val concurrentSnapshot = settingsSnapshot().let { snapshot ->
            snapshot.copy(selection = snapshot.selection.withModel(concurrent, true))
        }

        val pending = edited.reflect(EngineConnectionsState.Active(concurrentSnapshot, pending = operation))

        assertEquals<Set<String>?>(setOf(concurrent.model.value), pending.modelDraft)
        assertEquals(setOf(removed.model.value, concurrent.model.value), pending.selectedModels)
        assertTrue(pending.isModelSavePending)
        assertEquals(
            ConnectionOperation.SetModelsEnabled(
                binding,
                setOf(concurrent.model),
                previous = setOf(removed.model, concurrent.model),
            ),
            pending.operationFor(EngineConnectionsScreenIntent.SaveModels),
        )
        assertEquals(
            ConnectionOperation.SetModelsEnabled(
                binding,
                emptySet(),
                previous = setOf(removed.model, concurrent.model),
            ),
            pending.editModel(concurrent.model.value, false)
                .operationFor(EngineConnectionsScreenIntent.SaveModels),
        )

        val savedSnapshot = concurrentSnapshot.copy(
            selection = concurrentSnapshot.selection.withModel(removed, false),
        )
        val saved = pending.reflect(EngineConnectionsState.Active(savedSnapshot))
        assertEquals(setOf(concurrent.model.value), saved.selectedModels)
        assertNull(saved.modelDraft)
        assertNull(saved.modelDraftBaseline)
        assertFalse(saved.isModelSavePending)
    }

    @Test
    fun `with engine management every engine is listed and a switched-off one has no connections`() {
        assertFalse(loaded.isManaged)
        val managed = EngineConnectionsScreenState().reflect(EngineConnectionsState.Active(managedSettingsSnapshot()))

        assertTrue(managed.isManaged)
        assertEquals(listOf(KoogId.value, "codex"), managed.engines.map { it.id })
        assertEquals(listOf(true, false), managed.engines.map { it.isEnabled })
        val codex = managed.copy(selectedEngine = "codex")
            .reflect(EngineConnectionsState.Active(managedSettingsSnapshot()))
        assertTrue(codex.connections.isEmpty())
        assertNull(codex.models)
    }
}
