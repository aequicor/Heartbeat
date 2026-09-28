package io.aequicor.heartbeat.platform.dibundle

import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.secrets.SecretStorageProtection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmHeartbeatGraphTest {

    @Test
    fun `production and development entry points select isolated credential backends`() {
        val production = createHeartbeatGraph() as JvmHeartbeatGraph
        val development = createHeartbeatGraph(isDevelopment = true) as JvmHeartbeatGraph
        assertEquals(SecretStorageProtection.System, production.secretStorage.protection)
        assertEquals(SecretStorageProtection.LocalDevelopment, development.secretStorage.protection)
    }

    @Test
    fun `graph resolves common and jvm-specific contributions from core modules`() {
        val graph = createHeartbeatGraph()

        // jvmMain contribution of :core:common (internal class, exposed via generated provider)
        assertTrue(graph.platformInfo.host in setOf(HostPlatform.MacOs, HostPlatform.Windows, HostPlatform.Linux))
        // commonMain contribution of :core:common
        assertEquals("Dispatchers.Default", graph.dispatchers.default.toString())
    }
}
