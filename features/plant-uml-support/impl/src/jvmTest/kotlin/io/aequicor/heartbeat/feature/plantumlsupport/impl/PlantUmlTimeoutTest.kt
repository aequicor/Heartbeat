package io.aequicor.heartbeat.feature.plantumlsupport.impl

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlFailure
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlRequest
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlResult
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlStyle
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.DefaultPlantUmlRenderer
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.PlantUmlEngine
import io.aequicor.heartbeat.feature.plantumlsupport.impl.domain.PlantUmlLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** A started drawing that hangs: the engine thread is real, the waits follow the wall clock. */
class PlantUmlTimeoutTest {
    private val style = PlantUmlStyle(0, 0, 0, 0, 0, 0, fontSize = 14f, isDark = false)
    private val hung = PlantUmlRequest("@startuml\nA -> B\n@enduml", style, scale = 1f)
    private val other = hung.copy(source = "@startuml\nB -> C\n@enduml")

    @Test
    fun `a hung drawing times out and keeps the engine busy until it ends`() = runTest {
        val release = CountDownLatch(1)
        val engine = PlantUmlEngine { source, _, _ ->
            if ("A -> B" in source.text) release.await(5, TimeUnit.SECONDS)
            PlantUmlResult.Image(ByteArray(8), 10f, 10f, 1f)
        }
        val executor = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(SupervisorJob())
        val renderer = DefaultPlantUmlRenderer(
            engine = engine,
            toggles = EnabledToggles,
            scope = scope,
            worker = executor.asCoroutineDispatcher(),
            limits = PlantUmlLimits(timeout = 300.milliseconds),
        )
        try {
            withContext(Dispatchers.Default) {
                assertEquals(PlantUmlResult.Failed(PlantUmlFailure.Timeout), renderer.render(hung))
                assertEquals(PlantUmlResult.Failed(PlantUmlFailure.Busy), renderer.render(other))
                release.countDown()
                executor.submit {}.get(5, TimeUnit.SECONDS)
                assertTrue(renderer.render(hung) is PlantUmlResult.Image, "the late result is cached")
                assertTrue(renderer.render(other) is PlantUmlResult.Image)
            }
        } finally {
            release.countDown()
            scope.cancel()
            executor.shutdownNow()
        }
    }

    private object EnabledToggles : FeatureToggles {
        override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> {
            @Suppress("UNCHECKED_CAST") // The renderer reads only its Boolean flag.
            return flowOf(true as T)
        }

        @Suppress("UNCHECKED_CAST") // The renderer reads only its Boolean flag.
        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = true as T
    }
}
