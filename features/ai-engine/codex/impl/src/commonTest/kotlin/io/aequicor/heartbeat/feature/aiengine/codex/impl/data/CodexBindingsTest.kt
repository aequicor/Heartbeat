package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.codex.api.CodexLocalConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CodexBindingsTest {
    /**
     * Revision 1 is the first catalog that reports image and document input support. A profile cache of an older
     * revision is rediscovered in the background, so attachments never stay hidden behind a stale discovery.
     */
    @Test
    fun `registration keeps the catalog revision that introduced input support`() {
        val factory = lazy<CodexEngineFactory> { error("Must stay lazy") }
        val manager = lazy<CodexEngineManager> { error("Must stay lazy") }
        val registration = CodexBindings.registration(factory, manager, CodexLocalConfiguration())
        assertEquals(1, registration.modelCatalogRevision)
        assertEquals(CodexManagementSpec, registration.management)
        assertFalse(manager.isInitialized())
    }
}
