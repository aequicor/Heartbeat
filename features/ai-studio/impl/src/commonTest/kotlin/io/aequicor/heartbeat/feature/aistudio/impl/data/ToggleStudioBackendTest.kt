package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineRuntime
import io.aequicor.heartbeat.feature.aistudio.api.StudioRuntimeState
import io.aequicor.heartbeat.feature.aistudio.impl.domain.AiStudioEffects
import io.aequicor.heartbeat.feature.aistudio.impl.domain.EngineStudioEffects
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioAvailability
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.aistudio.impl.domain.TestClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame

class ToggleStudioBackendTest {
    @Test
    fun `toggle off picks the demo workspace and never creates the engine backend`() = runTest {
        val demo = InMemoryStudioRepository(TestClock(this))
        val backend = ToggleStudioBackend(
            RuntimeToggle(isEnabled = false),
            lazy { error("engine repository must not be created") },
            lazy { error("engine runtime must not be created") },
            lazy { demo },
            AlwaysAvailable,
            TestClock(this),
        )
        assertSame(demo, backend.repository())
        assertIs<AiStudioEffects>(backend.effects())
    }

    @Test
    fun `toggle on picks the engine runtime and never creates the demo workspace`() = runTest {
        val engine = InMemoryStudioRepository(TestClock(this))
        val backend = ToggleStudioBackend(
            RuntimeToggle(isEnabled = true),
            lazy { engine },
            lazy { IdleRuntime },
            lazy { error("demo workspace must not be created") },
            AlwaysAvailable,
            TestClock(this),
        )
        assertSame(engine, backend.repository())
        assertIs<EngineStudioEffects>(backend.effects())
    }
}

private class RuntimeToggle(private val isEnabled: Boolean) : FeatureToggles {
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> {
        check(toggle == StudioEngineRuntime) { "unexpected toggle ${toggle.key}" }
        // The only toggle read here is the Boolean engine runtime flag checked above.
        @Suppress("UNCHECKED_CAST")
        return flowOf(isEnabled) as Flow<T>
    }

    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T {
        check(toggle == StudioEngineRuntime) { "unexpected toggle ${toggle.key}" }
        // The only toggle read here is the Boolean engine runtime flag checked above.
        @Suppress("UNCHECKED_CAST")
        return isEnabled as T
    }
}

private object AlwaysAvailable : StudioAvailability {
    override suspend fun isEnabled(): Boolean = true

    override fun observe(): Flow<Boolean> = flowOf(true)
}

private object IdleRuntime : StudioRuntime {
    override val state = MutableStateFlow(StudioRuntimeState())

    override suspend fun defaults(): RunSettings = error("unused")

    override suspend fun run(sessionId: String, prompt: String, settings: RunSettings): RunOutcome = error("unused")

    override suspend fun cancel(sessionId: String): Unit = error("unused")

    override suspend fun respond(sessionId: String, requestId: String, optionId: String): Unit = error("unused")
}
