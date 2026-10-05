package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlRequest
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlResult
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Real graph and engine; drawing is awaited on [Dispatchers.Default] so the time limit follows the wall clock. */
class PlantUmlIntegrationTest {
    private val persisted = PersistedProfile()
    private val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
    private val toggles = app as TestToggleAccessors
    private val plantUml = (app as PlantUmlAccessors).plantUml
    private val request = PlantUmlRequest(
        source = "@startuml\nUser -> Studio : draw\n@enduml",
        style = PlantUmlStyle(-1, -1, -1, 0, -1, 0, fontSize = 14f, isDark = true),
        scale = 1f,
    )

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        (app.appScope as OwnedScope).close()
        Dispatchers.resetMain()
        File(persisted.storageRoot).deleteRecursively()
    }

    @Test
    fun `the desktop graph registers the toggle and keeps diagrams as code by default`() = runTest {
        assertTrue(toggles.toggleControl.registered.any { it.key == "plantuml.enabled" })
        assertEquals(false, plantUml.availability.first())
        assertEquals(PlantUmlResult.Unsupported, plantUml.render(request))
    }

    @Test
    fun `an enabled renderer draws in-process`() = runTest {
        val toggle = toggles.toggleControl.registered.filterIsInstance<FeatureToggle.Flag>()
            .single { it.key == "plantuml.enabled" }
        toggles.toggleControl.setOverride(toggle, true)
        assertEquals(true, plantUml.availability.first())
        val image = assertIs<PlantUmlResult.Image>(withContext(Dispatchers.Default) { plantUml.render(request) })
        assertTrue(image.width > 0f && image.png.isNotEmpty())
    }
}
