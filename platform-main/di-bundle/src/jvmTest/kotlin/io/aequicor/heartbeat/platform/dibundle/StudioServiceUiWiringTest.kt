package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** Service-as-ui in the `studio` bundle: its toggles reach the application; its providers come from other bundles. */
class StudioServiceUiWiringTest {
    private val persisted = PersistedProfile()
    private val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = runTest {
        app.closeAndAwaitStorages()
        Dispatchers.resetMain()
        File(persisted.storageRoot).deleteRecursively()
    }

    @Test
    fun `toggles of service-as-ui reach the application toggles`() {
        val registered = (app as TestToggleAccessors).toggleControl.registered.map { it.key }
        assertTrue(ServiceUiEnabled.key in registered, registered.toString())
    }
}
