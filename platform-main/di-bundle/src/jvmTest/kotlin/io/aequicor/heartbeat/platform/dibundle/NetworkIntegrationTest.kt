package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.OwnedScope
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NetworkIntegrationTest {

    @Test
    fun `one http client per process, closed with its engine together with the app scope`() {
        val app = createGraphFactory<TestAppGraph.Factory>().create(PersistedProfile())
        val client = app.httpClient
        val clientJob = client.coroutineContext[Job]!!
        val engineJob = app.httpEngine.coroutineContext[Job]!!

        assertSame(client, app.httpClient)
        assertSame(app.httpEngine, client.engine)
        assertTrue(clientJob.isActive && engineJob.isActive)

        (app.appScope as OwnedScope).close()

        assertFalse(clientJob.isActive)
        assertFalse(engineJob.isActive)
    }
}
