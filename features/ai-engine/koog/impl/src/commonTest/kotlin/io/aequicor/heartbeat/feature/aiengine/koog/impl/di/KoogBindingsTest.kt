package io.aequicor.heartbeat.feature.aiengine.koog.impl.di

import io.aequicor.heartbeat.feature.aiengine.facade.api.ChangesSessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.AttachesSessions
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KoogBindingsTest {
    @Test
    fun `descriptor advertises live configuration without creating the adapter`() {
        val adapter = lazy<KoogEngineAdapter> { error("Descriptor access must stay lazy") }
        val registration = KoogBindings.registration(adapter)
        assertTrue(EnginePlatform.DesktopLinux in registration.descriptor.platforms)
        assertTrue(ChangesSessionConfiguration.id in registration.descriptor.declaredFeatures)
        assertFalse(adapter.isInitialized())
    }

    /**
     * The facade offers a stored chat for continuation only when the engine declares runtime attachment:
     * without it every Koog chat turned read-only after an app restart.
     */
    @Test
    fun `descriptor declares runtime attachment so stored chats resume after restart`() {
        val adapter = lazy<KoogEngineAdapter> { error("Descriptor access must stay lazy") }
        val registration = KoogBindings.registration(adapter)
        assertTrue(AttachesSessions.id in registration.descriptor.declaredFeatures)
        assertFalse(adapter.isInitialized())
    }

    /**
     * Revision 2 adds reasoning effort to compatible vendor routes. A profile cache of an older revision is
     * rediscovered in the background, so effort selectors do not stay hidden behind a stale discovery.
     */
    @Test
    fun `registration upgrades cached compatible route reasoning capabilities`() {
        val adapter = lazy<KoogEngineAdapter> { error("Revision access must stay lazy") }
        assertEquals(2, KoogBindings.registration(adapter).modelCatalogRevision)
        assertFalse(adapter.isInitialized())
    }
}
