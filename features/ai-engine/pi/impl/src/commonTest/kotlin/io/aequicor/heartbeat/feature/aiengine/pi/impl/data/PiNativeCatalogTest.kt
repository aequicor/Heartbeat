package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PiNativeCatalogTest {
    @Test
    fun `native defaults match each desktop shell and optional inspection stays off`() {
        for ((host, shell) in listOf(HostPlatform.MacOs to "bash", HostPlatform.Windows to "powershell")) {
            val catalog = piNativeCatalog(host)
            assertEquals(
                setOf("read", "edit", "write", shell),
                catalog.filter { it.isEnabledByDefault }.map { it.name }.toSet(),
            )
            assertEquals(
                setOf("grep", "find", "ls"),
                catalog.filterNot { it.isEnabledByDefault }.map { it.name }.toSet(),
            )
            assertTrue(catalog.all { it.isGated })
            assertFalse(catalog.any { it.name.startsWith("web_") })
            assertEquals(AgentToolAction.Command, catalog.single { it.name == shell }.action)
            catalog.forEach { assertEquals(it.action, piNativeAction(it.name)) }
        }
        assertTrue(piNativeCatalog(HostPlatform.Android).isEmpty())
        assertTrue(piNativeCatalog(HostPlatform.Ios).isEmpty())
    }
}
