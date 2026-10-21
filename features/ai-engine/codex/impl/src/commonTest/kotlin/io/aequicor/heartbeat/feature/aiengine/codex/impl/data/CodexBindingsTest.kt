package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.codex.api.CodexLocalConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.StopsOwnedTurns
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodexBindingsTest {
    /**
     * Revision 2 corrects the native default for legacy model metadata. Previously cached text-only capabilities
     * must be rediscovered so image attachments become available without manually refreshing the catalog.
     */
    @Test
    fun `registration invalidates catalog entries that lost legacy image support`() {
        val factory = lazy<CodexEngineFactory> { error("Must stay lazy") }
        val manager = lazy<CodexEngineManager> { error("Must stay lazy") }
        val registration = CodexBindings.registration(factory, manager, CodexLocalConfiguration())
        assertEquals(2, registration.modelCatalogRevision)
        assertEquals(CodexManagementSpec, registration.management)
        assertFalse(manager.isInitialized())
        assertTrue(EnginePlatform.DesktopLinux in registration.descriptor.platforms)
        assertTrue(StopsOwnedTurns.id in registration.descriptor.declaredFeatures)
    }
}
