package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.di

import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.domain.DeepSeekBackend
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.domain.DeepSeekEngineManager
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTrees
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeepSeekBindingsTest {
    @Test
    fun `the user's CLI is discovered, never installed, and managed without touching it`() {
        val registration = DeepSeekBindings.registration(
            backend = lazy<DeepSeekBackend> { error("The backend must not be constructed") },
            manager = lazy<DeepSeekEngineManager> { error("The manager must not be constructed") },
        )

        assertEquals(InstallSupport.External, registration.management.install)
        assertEquals(LoginSupport.Connections, registration.management.login)
        assertNotNull(registration.manager)
        assertEquals("DeepSeek Harness", registration.descriptor.title)
        assertTrue(SessionTrees.id in registration.descriptor.declaredFeatures)
        assertEquals(3, registration.modelCatalogRevision)
    }
}
