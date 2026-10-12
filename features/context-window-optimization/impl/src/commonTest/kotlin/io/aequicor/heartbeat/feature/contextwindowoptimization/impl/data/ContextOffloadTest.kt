package io.aequicor.heartbeat.feature.contextwindowoptimization.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationSettings
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationState
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationTools
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContextOffloadTest {
    private val outputs = MemoryOutputs()
    private val view = object : ContextOptimizationView {
        override val state = MutableStateFlow<ContextOptimizationState>(
            ContextOptimizationState.Ready(ContextOptimizationSettings(offloadThresholdChars = 4_000)),
        )
    }
    private val offload = ContextOutputOffload(view, EnabledToggles(), outputs)
    private val recall = ContextRecallTools(EnabledToggles(), outputs)

    @Test
    fun `long output is stored and shortened, short output stays`() = runTest {
        val output = "a".repeat(3_000) + "b".repeat(3_000) + "c".repeat(3_000)

        val stub = assertNotNull(offload.offload(Session, "run_command", output))

        assertTrue(stub.startsWith("a".repeat(2_000)) && stub.contains("c".repeat(1_000)) && "context_recall" in stub)
        assertTrue(stub.length < output.length)
        assertNull(offload.offload(Session, "run_command", "short"))
        assertNull(offload.offload(Session, ContextOptimizationTools.RECALL, output))
    }

    @Test
    fun `recall reads parts of an output of the same session only`() = runTest {
        val output = "x".repeat(5_000) + "TAIL"
        val stub = assertNotNull(offload.offload(Session, "read_file", output))
        val id = Regex("id=\"(\\w+)\"").find(stub)!!.groupValues[1]

        val part = recall.execute(context(Session), ContextOptimizationTools.RECALL, arguments(id, 5_000))
        val foreign = recall.execute(context(Other), ContextOptimizationTools.RECALL, arguments(id, 0))

        assertTrue(part.text.startsWith("TAIL") && "This is the end" in part.text)
        assertTrue(foreign.isError)
    }

    private fun context(session: SessionRef) = AgentToolContext(session = session, workspace = null, turn = TurnId("t"))

    private fun arguments(id: String, offset: Int) =
        JsonObject(mapOf("id" to JsonPrimitive(id), "offset" to JsonPrimitive(offset)))

    private class EnabledToggles : FeatureToggles {
        override fun <T : Any> observe(toggle: FeatureToggle<T>) = flowOf(enabled<T>())
        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = enabled()

        @Suppress("UNCHECKED_CAST") // The offload reads only flags.
        private fun <T : Any> enabled(): T = true as T
    }

    private class MemoryOutputs : ContextOutputs {
        private val stored = mutableMapOf<String, StoredOutput>()

        override suspend fun put(output: StoredOutput): Stored {
            val id = "out${stored.size}"
            stored[id] = output
            return Stored(id, output.text.length)
        }

        override suspend fun get(id: String): StoredOutput? = stored[id]
    }

    private companion object {
        val Session = SessionRef(EngineId("koog"), SessionSourceId("s"), "one")
        val Other = SessionRef(EngineId("koog"), SessionSourceId("s"), "two")
    }
}
