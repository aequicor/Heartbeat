package io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store

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
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalogSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.Observation
import io.aequicor.heartbeat.feature.aiengine.facade.api.scopeFor
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `changes target the focused engine and connection`() {
        assertEquals(
            ConnectionOperation.SetModelEnabled(modelInfo(EngineBindingId("home"), "qwen").target, true),
            loaded.operationFor(EngineConnectionsScreenIntent.SetModelEnabled("qwen", true)),
        )
        assertEquals(
            ConnectionOperation.SetModelsEnabled(EngineBindingId("home"), setOf(ModelId("llama"), ModelId("qwen"))),
            loaded.operationFor(EngineConnectionsScreenIntent.SetAllModels(true)),
        )
        assertEquals(
            ConnectionOperation.Disconnect(EngineBindingId("home")),
            loaded.copy(confirmDisconnect = "home").operationFor(EngineConnectionsScreenIntent.ConfirmDisconnect),
        )
        assertNull(loaded.operationFor(EngineConnectionsScreenIntent.ConfirmDisconnect))
        assertNull(EngineConnectionsScreenState().operationFor(EngineConnectionsScreenIntent.RefreshModels))
    }
}
