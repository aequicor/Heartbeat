package io.aequicor.heartbeat.feature.harness.impl.data.script

import io.aequicor.heartbeat.feature.harness.api.HarnessValidationCode
import io.aequicor.heartbeat.feature.harness.api.HarnessValidationException
import java.lang.reflect.InvocationTargetException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class HarnessEvaluationFailureTest {
    @Test
    fun `wrapped known host code exposes only its static description`() {
        val failure = harnessEvaluationFailure(
            listOf(
                InvocationTargetException(
                    HarnessValidationException(HarnessValidationCode.InvalidItemName),
                    "private wrapper",
                ),
            ),
        )
        assertEquals(HarnessValidationCode.InvalidItemName, failure.reason)
        assertEquals(HarnessValidationCode.InvalidItemName.message, failure.diagnostics.single().message)
        assertFalse("private wrapper" in failure.diagnostics.single().message)
    }

    @Test
    fun `arbitrary exceptions and matching text cannot impersonate a host validation code`() {
        for (error in listOf(
            IllegalStateException("private source and token"),
            IllegalArgumentException(HarnessValidationCode.InvalidItemName.message),
            InvocationTargetException(IllegalStateException("private cause"), "private wrapper"),
        )) {
            val failure = harnessEvaluationFailure(listOf(error))
            assertNull(failure.reason)
            assertEquals(harnessHostFailure(), failure.diagnostics)
        }
    }

    @Test
    fun `cyclic exception causes are bounded and remain generic`() {
        val outer = IllegalStateException("private outer")
        val inner = IllegalStateException("private inner", outer)
        outer.initCause(inner)
        val failure = harnessEvaluationFailure(listOf(outer))
        assertNull(failure.reason)
        assertEquals(harnessHostFailure(), failure.diagnostics)
    }
}
