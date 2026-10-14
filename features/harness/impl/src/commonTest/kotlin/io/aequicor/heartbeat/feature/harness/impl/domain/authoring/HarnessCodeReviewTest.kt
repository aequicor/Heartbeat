package io.aequicor.heartbeat.feature.harness.impl.domain.authoring

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HarnessCodeReviewTest {
    private val target = EngineTarget(EngineId("pi"), EngineBindingId("b"), ModelId("m"))

    @Test
    fun `review is reused only for the exact key and execution cannot start it`() = runTest {
        var calls = 0
        val reviews = HarnessCodeReviews { _, _ ->
            calls++
            CodeReview("safe", "Formatting")
        }
        assertNull(reviews.check("a", target, "source", canStart = false))
        assertTrue(reviews.check("a", target, "source", canStart = true)!!.isSafe)
        assertTrue(reviews.check("a", target, "source", canStart = false)!!.isSafe)
        assertNull(reviews.check("b", target, "changed", canStart = false))
        assertEquals(1, calls)
    }

    @Test
    fun `unavailable model and provider errors refuse automatic authorization`() = runTest {
        val reviews = HarnessCodeReviews { _, _ -> error("private source must not be surfaced") }
        val missing = reviews.check("a", null, "source", canStart = true)!!
        val failed = reviews.check("b", target, "source", canStart = true)!!
        assertFalse(missing.isSafe)
        assertFalse(failed.isSafe)
        assertFalse(failed.reason.contains("private source"))
    }

    @Test
    fun `cancellation propagates and is not cached as an assessment`() = runTest {
        var calls = 0
        val reviews = HarnessCodeReviews { _, _ ->
            calls++
            if (calls == 1) throw CancellationException("cancelled")
            CodeReview("safe", "Formatting")
        }
        assertFailsWith<CancellationException> { reviews.check("a", target, "source", canStart = true) }
        assertTrue(reviews.check("a", target, "source", canStart = true)!!.isSafe)
        assertEquals(2, calls)
    }
}
