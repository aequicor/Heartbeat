package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EffectiveSettingsProvenanceTest {
    private val route = RouteKey(TestTarget.engine, TestTarget.binding)

    @Test
    fun `a host without the facade observes nothing and refuses a read`() = runTest {
        val service = UnavailableEffectiveSettingsProvenance

        assertNull(service.report(route, WorkspaceRef("project")))
        assertTrue(service.reports.value.isEmpty())
        val failure = assertFailsWith<ConfigException> { service.read(route, WorkspaceRef("project")) }
        assertEquals(ConfigFailure.Unsupported, failure.failure)
    }

    @Test
    fun `a source kind belongs to its layer and only a project can need trust`() {
        assertFailsWith<IllegalArgumentException> { LayerProvenance(SettingsLayer.Engine, heartbeatProfile()) }
        assertFailsWith<IllegalArgumentException> {
            LayerProvenance(SettingsLayer.Engine, SettingsProvenance.NeedsTrust)
        }
        assertEquals(SettingsLayer.Project, SettingsSourceKind.ProjectFile.layer)
        assertTrue(
            LayerProvenance(
                SettingsLayer.Project,
                SettingsProvenance.Observed(ObservedValue.Secret, SettingsSourceKind.ProjectFile),
            ).provenance is SettingsProvenance.Observed,
        )
    }

    @Test
    fun `a declared resolution needs at least one layer`() {
        assertFailsWith<IllegalArgumentException> { EffectiveResolution.Declared(ObservedValue.Secret, emptySet()) }
    }

    private fun heartbeatProfile() = SettingsProvenance.Observed(
        ObservedValue.Literal(JsonPrimitive("value")),
        SettingsSourceKind.HeartbeatProfile,
    )
}
