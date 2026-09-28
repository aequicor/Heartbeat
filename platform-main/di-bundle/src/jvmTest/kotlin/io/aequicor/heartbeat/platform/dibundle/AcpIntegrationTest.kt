package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.OwnedScope
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AcpIntegrationTest {
    @Test
    fun `application graph supplies ACP client and desktop transport without starting processes`() {
        val app = createGraphFactory<TestAppGraph.Factory>().create(PersistedProfile())
        try {
            assertNotNull(app.acpClients)
            assertTrue(app.acpStdio.isSupported)
        } finally {
            (app.appScope as OwnedScope).close()
        }
    }
}
