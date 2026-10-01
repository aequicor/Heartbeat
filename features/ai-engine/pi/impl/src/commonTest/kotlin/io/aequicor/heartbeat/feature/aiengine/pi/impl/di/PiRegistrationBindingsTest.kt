package io.aequicor.heartbeat.feature.aiengine.pi.impl.di

import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.PiAdapter
import kotlin.test.Test
import kotlin.test.assertEquals

class PiRegistrationBindingsTest {
    /**
     * Revision 3 is the first catalog that reports image and document input support. A profile cache of an older
     * revision is rediscovered in the background, so attachments never stay hidden behind a stale discovery.
     */
    @Test
    fun `registration keeps the catalog revision that introduced input support`() {
        val registration = PiRegistrationBindings.registration(lazy<PiAdapter> { error("Must stay lazy") })
        assertEquals(3, registration.modelCatalogRevision)
    }
}
