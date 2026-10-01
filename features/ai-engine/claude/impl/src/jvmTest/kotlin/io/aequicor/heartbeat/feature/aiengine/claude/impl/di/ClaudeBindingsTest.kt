package io.aequicor.heartbeat.feature.aiengine.claude.impl.di

import io.aequicor.heartbeat.feature.aiengine.claude.impl.domain.ClaudeBackend
import kotlin.test.Test
import kotlin.test.assertEquals

class ClaudeBindingsTest {
    /**
     * Revision 1 is the first catalog that reports image and document input support. A profile cache of an older
     * revision is rediscovered in the background, so attachments never stay hidden behind a stale discovery.
     */
    @Test
    fun `registration keeps the catalog revision that introduced input support`() {
        val registration = ClaudeBindings.registration(lazy<ClaudeBackend> { error("Must stay lazy") })
        assertEquals(1, registration.modelCatalogRevision)
    }
}
