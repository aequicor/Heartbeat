package io.aequicor.heartbeat.feature.plantumlsupport.impl

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlFailure
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlRequest
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlResult
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlStyle
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.DefaultPlantUmlRenderer
import io.aequicor.heartbeat.feature.plantumlsupport.impl.data.PlantUmlEngine
import io.aequicor.heartbeat.feature.plantumlsupport.impl.domain.PlantUmlEnabled
import io.aequicor.heartbeat.feature.plantumlsupport.impl.domain.PlantUmlLimits
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DefaultPlantUmlRendererTest {
    private val style = PlantUmlStyle(0, 0, 0, 0, 0, 0, fontSize = 14f, isDark = false)
    private val request = PlantUmlRequest("@startuml\nA -> B\n@enduml", style, scale = 2f)
    private val toggles = Toggles(isEnabled = true)
    private val preambles = mutableListOf<List<String>>()
    private var draw: () -> PlantUmlResult = { PlantUmlResult.Image(ByteArray(8), 10f, 10f, 2f) }
    private val engine = PlantUmlEngine { _, preamble, _ ->
        preambles += preamble
        draw()
    }

    private fun TestScope.renderer(worker: CoroutineDispatcher = StandardTestDispatcher(testScheduler)) =
        DefaultPlantUmlRenderer(
            engine = engine,
            toggles = toggles,
            // Like the application scope, a failed drawing does not cancel its siblings.
            scope = CoroutineScope(
                backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext.job),
            ),
            worker = worker,
            limits = PlantUmlLimits(maxSourceCharacters = 100),
            timeSource = testScheduler.timeSource,
        )

    @Test
    fun `nothing is drawn while the feature is off`() = runTest {
        toggles.isEnabled.value = false
        val renderer = renderer()
        assertEquals(false, renderer.availability.first())
        assertEquals(PlantUmlResult.Unsupported, renderer.render(request))
        assertTrue(preambles.isEmpty())
    }

    @Test
    fun `oversized sources and external-tool types never reach the engine`() = runTest {
        val renderer = renderer()
        assertEquals(
            PlantUmlResult.Failed(PlantUmlFailure.TooLarge),
            renderer.render(request.copy(source = "x".repeat(101))),
        )
        assertEquals(PlantUmlResult.Unsupported, renderer.render(request.copy(source = "@startdot\na\n@enddot")))
        assertTrue(preambles.isEmpty())
    }

    @Test
    fun `a drawn result is cached and concurrent requests share one drawing`() = runTest {
        val worker = GatedDispatcher()
        val renderer = renderer(worker)
        val first = async { renderer.render(request) }
        val second = async { renderer.render(request) }
        testScheduler.runCurrent()
        worker.runAll()
        testScheduler.runCurrent()
        assertSame(first.await(), second.await())
        assertSame(first.await(), renderer.render(request))
        assertEquals(1, preambles.size)
    }

    @Test
    fun `waiting in the queue is not a timeout and never blocks later requests`() = runTest {
        val worker = GatedDispatcher()
        val renderer = renderer(worker)
        val other = request.copy(source = "@startuml\nB -> C\n@enduml")
        val first = async { renderer.render(request) }
        val queued = async { renderer.render(other) }
        testScheduler.runCurrent()
        advanceTimeBy(15.seconds)
        worker.runNext()
        testScheduler.runCurrent()
        assertTrue(first.await() is PlantUmlResult.Image)
        advanceTimeBy(10.seconds)
        assertEquals(PlantUmlResult.Failed(PlantUmlFailure.Busy), queued.await(), "not started within the limit")
        val later = async { renderer.render(request.copy(source = "@startuml\nC -> D\n@enduml")) }
        testScheduler.runCurrent()
        worker.runAll()
        assertTrue(later.await() is PlantUmlResult.Image, "no drawing was abandoned")
    }

    @Test
    fun `an engine error does not leave a drawing that later requests would join`() = runTest {
        draw = { throw AssertionError("engine error") }
        val renderer = renderer()
        assertFailsWith<AssertionError> { renderer.render(request) }
        draw = { PlantUmlResult.Image(ByteArray(8), 10f, 10f, 2f) }
        assertTrue(renderer.render(request) is PlantUmlResult.Image)
        assertEquals(2, preambles.size)
    }

    @Test
    fun `an image too large at display scale is drawn again at scale one`() = runTest {
        val tooLarge = PlantUmlResult.Failed(PlantUmlFailure.TooLarge)
        draw = { if (preambles.size == 1) tooLarge else PlantUmlResult.Unsupported }
        renderer().render(request.copy(scale = 10f))
        assertEquals(2, preambles.size)
        assertTrue("skinparam dpi 288" in preambles[0], "scale is capped")
        assertTrue("skinparam dpi 96" in preambles[1])
    }

    @Test
    fun `engine exceptions become internal failures that are not cached`() = runTest {
        draw = { error("engine broke") }
        val renderer = renderer()
        assertEquals(PlantUmlResult.Failed(PlantUmlFailure.Internal), renderer.render(request))
        assertEquals(PlantUmlResult.Failed(PlantUmlFailure.Internal), renderer.render(request))
        assertEquals(2, preambles.size)
    }

    /** Holds drawings until the test releases them, like an engine thread that is still busy. */
    private class GatedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue += block
        }

        fun runNext() {
            queue.removeFirst().run()
        }

        fun runAll() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    private class Toggles(isEnabled: Boolean) : FeatureToggles {
        val isEnabled = MutableStateFlow(isEnabled)

        override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> {
            check(toggle == PlantUmlEnabled) { "unexpected toggle ${toggle.key}" }
            @Suppress("UNCHECKED_CAST") // PlantUmlEnabled is a Boolean flag.
            return isEnabled as Flow<T>
        }

        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = observe(toggle).first()
    }
}
