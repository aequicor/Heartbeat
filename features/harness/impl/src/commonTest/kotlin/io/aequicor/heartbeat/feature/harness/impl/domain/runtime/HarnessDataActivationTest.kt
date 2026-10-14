package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.harness.api.script.ScriptDataWriteResult
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class HarnessDataActivationTest {
    @Test
    fun `candidate cannot access data and replacement preserves data while revoking old references`() = runTest {
        val fixture = HarnessRegistrationFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        fixture.evaluate = { script ->
            assertFailsWith<IllegalStateException> { script.data.read("state") }
            assertFailsWith<IllegalStateException> { script.data.write("state", JsonPrimitive(99), 0) }
        }
        fixture.activate()
        val old = fixture.scripts.single().data
        assertIs<ScriptDataWriteResult.Written>(old.write("state", JsonPrimitive(5), 0))
        fixture.desired = scriptRequest(2)
        fixture.activate()
        assertFailsWith<IllegalStateException> { old.write("state", JsonPrimitive(6), 1) }
        val fresh = fixture.scripts.last().data
        assertEquals(JsonPrimitive(5), fresh.read("state").value)
        fixture.isEnabled = false
        assertFailsWith<IllegalStateException> { fresh.read("state") }
        fixture.isEnabled = true
        assertEquals(JsonPrimitive(5), fresh.read("state").value)
    }
}
