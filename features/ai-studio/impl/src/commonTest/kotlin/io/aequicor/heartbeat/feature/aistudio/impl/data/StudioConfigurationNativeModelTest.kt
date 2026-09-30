package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SwitchesModels
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StudioConfigurationNativeModelTest {
    @Test
    fun `an acknowledged live model remains usable when the stored route is stale`() = runTest {
        val fixture = StudioConfigurationFixture(this)
        val native = fixture.add()
        val session = fixture.access.sessions.getValue("chat")
        val saved = fixture.access.records.getValue("chat").target?.model
        val next = ModelId("next-model")
        native.configuration.value = fixture.initial.copy(model = next)
        assertEquals(FeatureAccess.Unsupported, session.features.resolve(SwitchesModels))
        assertEquals(fixture.target.model, saved)
        assertEquals(next, session.configurationModel(saved))
    }

    @Test
    fun `a later native model correction takes precedence over the last saved route`() = runTest {
        val fixture = StudioConfigurationFixture(this)
        val native = fixture.add()
        val session = fixture.access.sessions.getValue("chat")
        val acknowledged = ModelId("acknowledged")
        native.configuration.value = fixture.initial.copy(model = acknowledged)
        assertEquals(acknowledged, session.configurationModel(fixture.target.model))
        val corrected = ModelId("corrected")
        native.configuration.value = fixture.initial.copy(model = corrected)
        assertEquals(corrected, session.configurationModel(acknowledged))
    }

    @Test
    fun `a legacy handle without configuration retains the saved model fallback`() = runTest {
        val fixture = StudioConfigurationFixture(this)
        fixture.add()
        val session = fixture.access.sessions.getValue("chat")
        session.native = null
        assertEquals(fixture.target.model, session.configurationModel(fixture.target.model))
        assertEquals(null, session.configurationModel(null))
    }
}
