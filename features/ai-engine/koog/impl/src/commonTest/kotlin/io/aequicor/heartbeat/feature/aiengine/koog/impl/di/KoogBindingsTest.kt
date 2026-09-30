package io.aequicor.heartbeat.feature.aiengine.koog.impl.di

import io.aequicor.heartbeat.feature.aiengine.facade.api.ChangesSessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineAdapter
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KoogBindingsTest {
    @Test
    fun `descriptor advertises live configuration without creating the adapter`() {
        val adapter = lazy<KoogEngineAdapter> { error("Descriptor access must stay lazy") }
        val registration = KoogBindings.registration(adapter)
        assertTrue(ChangesSessionConfiguration.id in registration.descriptor.declaredFeatures)
        assertFalse(adapter.isInitialized())
    }
}
