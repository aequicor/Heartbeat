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
        val shells = listOf(
            HostPlatform.MacOs to "bash",
            HostPlatform.Windows to "powershell",
            HostPlatform.Linux to "bash",
        )
        for ((host, shell) in shells) {
            val all = piNativeCatalog(host)
            val groups = PiToolGroups.entries.map { it.policyName }.toSet()
            val catalog = all.filterNot { it.name in groups }
            assertEquals(
                setOf("read", "edit", "write", shell),
                catalog.filter { it.isEnabledByDefault }.map { it.name }.toSet(),
            )
            assertEquals(
                setOf("grep", "find", "ls"),
                catalog.filterNot { it.isEnabledByDefault }.map { it.name }.toSet(),
            )
            assertTrue(catalog.all { it.isGated })
            assertTrue(all.filter { it.name in groups }.all { it.isEnabledByDefault && !it.isGated })
            assertFalse(catalog.any { it.name.startsWith("web_") })
            assertEquals(AgentToolAction.Command, catalog.single { it.name == shell }.action)
            catalog.forEach { assertEquals(it.action, piNativeAction(it.name)) }
        }
        assertTrue(piNativeCatalog(HostPlatform.Android).isEmpty())
        assertTrue(piNativeCatalog(HostPlatform.Ios).isEmpty())
    }

    @Test
    fun `extension tools fall into the group that turns them on`() {
        assertEquals(PiToolGroups.Mcp, piToolGroup("mcp__github__search"))
        assertEquals(PiToolGroups.Codemode, piToolGroup("codemode"))
        assertEquals(PiToolGroups.ToolSearch, piToolGroup("tool_search"))
        assertEquals(PiToolGroups.Extension, piToolGroup("my_tool"))
        assertEquals(null, piToolGroup("bash"))
        assertEquals(null, piToolGroup("read"))
    }
}
