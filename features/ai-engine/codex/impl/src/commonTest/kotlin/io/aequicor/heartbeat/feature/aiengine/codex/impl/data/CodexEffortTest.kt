package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class CodexEffortTest {
    @Test
    fun `catalog preserves advertised effort identifiers and its native default`() = runTest {
        val fixture = Fixture(this)
        fixture.offerEfforts("low", "future-effort", "low")
        val model = fixture.runtime.models(fixture.target.binding).single()
        assertEquals(listOf("low", "future-effort"), model.reasoningEfforts)
        assertEquals("future-effort", model.defaultReasoningEffort)
    }

    @Test
    fun `selected advertised effort is sent unchanged to the native turn`() = runTest {
        val fixture = Fixture(this)
        fixture.offerEfforts("low", "future-effort")
        fixture.open().feature(SendsPrompts).send(Prompt.copy(reasoningEffort = "future-effort"))
        val params = fixture.wire.written.single { it.text("method") == "turn/start" }.obj("params")
        assertEquals("future-effort", params.text("effort"))
    }

    @Test
    fun `default prompt leaves native effort unset`() = runTest {
        val fixture = Fixture(this)
        fixture.open().feature(SendsPrompts).send(Prompt)
        val params = fixture.wire.written.single { it.text("method") == "turn/start" }.obj("params")
        assertFalse("effort" in params)
    }

    @Test
    fun `unsupported effort is rejected before a native turn starts`() = runTest {
        val fixture = Fixture(this)
        fixture.offerEfforts("low")
        val session = fixture.open()
        val error = assertFailsWith<EngineException> {
            session.feature(SendsPrompts).send(Prompt.copy(reasoningEffort = "future-effort"))
        }
        assertEquals(EngineFailure.Request(RequestFailureReason.Invalid, Prompt.id), error.failure)
        assertFalse(fixture.wire.written.any { it.text("method") == "turn/start" })
    }
}

private fun Fixture.offerEfforts(vararg efforts: String) {
    modelList = listOf(
        json(
            "model" to target.model.value.json(),
            "displayName" to "Model".json(),
            "defaultReasoningEffort" to "future-effort".json(),
            "supportedReasoningEfforts" to JsonArray(efforts.map { json("reasoningEffort" to it.json()) }),
        ),
    )
}
