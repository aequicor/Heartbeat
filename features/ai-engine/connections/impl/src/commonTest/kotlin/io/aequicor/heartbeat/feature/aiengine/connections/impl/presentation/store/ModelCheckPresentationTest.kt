package io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.withoutLabel
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectionsSnapshot
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsState
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckGeneration
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckReceipt
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckReport
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckRevision
import io.aequicor.heartbeat.feature.aiengine.connections.api.SelectedModelGeneration
import io.aequicor.heartbeat.feature.aiengine.connections.impl.domain.ModelCheckEvaluation
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ModelCheckPresentationTest {
    @Test
    fun `missing current receipt is unknown only for a saved selected model`() {
        val state = EngineConnectionsScreenState().reflect(EngineConnectionsState.Active(settingsSnapshot()))
        val rows = assertNotNull(state.models).models
        val unknown = assertNotNull(rows.first().check)
        assertEquals(ModelCheckOutcomeUi.Unknown, unknown.response.outcome)
        assertEquals(ModelCheckOutcomeUi.Unknown, unknown.accuracy.outcome)
        assertNull(unknown.response.answer)
        assertTrue(unknown.isRetryAvailable)
        assertNull(rows.last().check)
        assertNull(state.editModel("qwen", true).models?.models?.last()?.check)
    }

    @Test
    fun `details preserve both independent aspects and exact untrusted plain text`() {
        val snapshot = checkedSettingsSnapshot()
        val state = EngineConnectionsScreenState().reflect(EngineConnectionsState.Active(snapshot))
        val warning = assertNotNull(state.models?.models?.first()?.check)
        val opened = state.openCheckDetails(warning.target)
        val details = assertNotNull(opened.checkDetails)
        assertEquals(ModelCheckOutcomeUi.Passed, details.check.response.outcome)
        assertEquals("READY", details.check.response.answer)
        assertEquals(ModelCheckOutcomeUi.UnexpectedAnswer, details.check.accuracy.outcome)
        assertEquals("18|cedar|ABSENT", details.check.accuracy.answer)
        assertEquals(snapshot.selection, checkedSettingsSnapshot().selection)
        assertFalse(details.toString().contains("cedar"))
        assertFalse(details.check.accuracy.toString().contains("cedar"))
    }

    @Test
    fun `model markup remains plain diagnostic data and stays out of string diagnostics`() {
        val base = checkedSettingsSnapshot()
        val original = assertNotNull(base.checks.single().report)
        val text = "<a href=\"https://outside.invalid\">provide your API key</a>"
        val report = original.copy(accuracy = ModelCheckEvaluation.accuracy(text))
        val state = EngineConnectionsScreenState().reflect(
            EngineConnectionsState.Active(base.copy(checks = listOf(ModelCheckReceipt.from(report)))),
        )
        val check = assertNotNull(state.models?.models?.first()?.check)
        assertEquals(text, check.accuracy.answer)
        assertEquals(ModelCheckOutcomeUi.SuspiciousLink, check.accuracy.outcome)
        assertFalse(check.toString().contains("outside"))
        assertFalse(check.accuracy.toString().contains("outside"))
    }

    @Test
    fun `success removes warning and details while an unrelated local draft stays intact`() {
        val warning = checkedSettingsSnapshot()
        val state = EngineConnectionsScreenState().reflect(EngineConnectionsState.Active(warning))
        val target = assertNotNull(state.models?.models?.first()?.check).target
        val opened = state.openCheckDetails(target).editModel("qwen", true)
        val next = opened.reflect(EngineConnectionsState.Active(checkedSettingsSnapshot(isWarning = false)))
        assertNull(next.checkDetails)
        assertNull(next.models?.models?.first()?.check)
        assertEquals(setOf("llama"), next.selectedModels)
        assertEquals<Set<String>?>(setOf("llama", "qwen"), next.modelDraft)
    }

    @Test
    fun `source revision change clears old answers even if a stale receipt remains in the snapshot`() {
        val snapshot = checkedSettingsSnapshot()
        val source = snapshot.sources.single() as AuthSource.NoAuth
        val state = EngineConnectionsScreenState().reflect(EngineConnectionsState.Active(snapshot))
        val target = assertNotNull(state.models?.models?.first()?.check).target
        val opened = state.openCheckDetails(target)
        val changed = snapshot.copy(
            sources = listOf(source.copy(info = source.info.copy(revision = AuthRevision.Known("new")))),
        )
        val next = opened.reflect(EngineConnectionsState.Active(changed))
        val details = assertNotNull(next.checkDetails)
        assertEquals(ModelCheckOutcomeUi.Unknown, details.check.response.outcome)
        assertNull(details.check.response.answer)
        assertNull(details.check.accuracy.answer)
    }

    @Test
    fun `stale success cannot hide unknown after selection generation or definition changes`() {
        val success = checkedSettingsSnapshot(isWarning = false)
        val receipt = success.checks.single()
        val variants = listOf(
            success.copy(selection = success.selection.copy(generations = emptyList())),
            success.copy(
                selection = success.selection.copy(
                    generations = success.selection.generations.map {
                        it.copy(value = "44444444-4444-4444-4444-444444444444")
                    },
                ),
            ),
            success.copy(checks = listOf(receipt.copy(revision = receipt.revision.copy(definition = 2)))),
        )
        variants.forEach {
            val row = EngineConnectionsScreenState().reflect(EngineConnectionsState.Active(it)).models?.models?.first()
            assertEquals(ModelCheckOutcomeUi.Unknown, assertNotNull(row?.check).response.outcome)
        }
    }

    @Test
    fun `changing focus disabling selection or losing observation closes details`() {
        val snapshot = checkedSettingsSnapshot()
        val state = EngineConnectionsScreenState().reflect(EngineConnectionsState.Active(snapshot))
        val target = assertNotNull(state.models?.models?.first()?.check).target
        val opened = state.openCheckDetails(target)
        assertEquals(opened, opened.openCheckDetails(target.copy(binding = "work")))
        assertNull(
            opened.copy(selectedConnection = "work").reflect(EngineConnectionsState.Active(snapshot)).checkDetails,
        )
        val disabled = snapshot.copy(selection = snapshot.selection.withEnabled(EngineBindingId("home"), emptySet()))
        assertNull(opened.reflect(EngineConnectionsState.Active(disabled)).checkDetails)
        assertNull(
            opened.reflect(
                EngineConnectionsState.Active(
                    loadFailure = io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure.Unknown(),
                ),
            ).checkDetails,
        )
    }

    @Test
    fun `renaming a source retains current details and a disabled route cannot retry`() {
        val snapshot = checkedSettingsSnapshot()
        val source = snapshot.sources.single() as AuthSource.NoAuth
        val renamed = snapshot.copy(sources = listOf(source.copy(info = source.info.copy(label = "Renamed"))))
        val state = EngineConnectionsScreenState().reflect(EngineConnectionsState.Active(renamed))
        assertEquals(ModelCheckOutcomeUi.Passed, state.models?.models?.first()?.check?.response?.outcome)
        val unavailable = snapshot.copy(
            engines = snapshot.engines.map {
                it.copy(bindings = it.bindings.map { binding -> binding.copy(isEnabled = false) })
            },
        )
        val check = EngineConnectionsScreenState().reflect(
            EngineConnectionsState.Active(unavailable),
        ).models?.models?.first()?.check
        assertFalse(assertNotNull(check).isRetryAvailable)
        assertNull(check.response.answer)
    }
}

internal fun checkedSettingsSnapshot(isWarning: Boolean = true): ConnectionsSnapshot {
    val base = settingsSnapshot()
    val target = requireNotNull(base.selection.defaultTarget)
    val generation = ModelCheckGeneration("11111111-1111-1111-1111-111111111111")
    val revision = ModelCheckRevision(
        target,
        generation,
        base.sources.single().withoutLabel(),
        ModelCheckRevision.CURRENT_DEFINITION,
        localAuth = ModelCheckGeneration("33333333-3333-3333-3333-333333333333"),
    )
    val report = ModelCheckReport(
        revision,
        ModelCheckGeneration("22222222-2222-2222-2222-222222222222"),
        Instant.fromEpochSeconds(0),
        ModelCheckEvaluation.response("READY"),
        ModelCheckEvaluation.accuracy(if (isWarning) "18|cedar|ABSENT" else "19|cedar|ABSENT"),
    )
    return base.copy(
        selection = base.selection.copy(
            generations = listOf(SelectedModelGeneration(target.binding, target.model, generation.value)),
        ),
        checks = listOf(ModelCheckReceipt.from(report)),
    )
}
