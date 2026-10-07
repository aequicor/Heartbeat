package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.impl.data.services.HarnessOwnedContext
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class HarnessOwnedContextTest {
    @Test
    fun `durable ownership roundtrip retains exact restriction and all relay depths`() {
        val codec = HarnessOwnedContext()
        val owner = HarnessId("private-owner")
        val origin = HarnessCallOrigin(true, mapOf(owner to 3, HarnessId("other") to 2))
        val restored = codec.decode(codec.encode(owner, origin))!!
        assertEquals(owner, restored.harness)
        assertEquals(origin, restored.origin())
        assertFalse(restored.toString().contains("private-owner"))
    }

    @Test
    fun `missing malformed unknown version and oversized ancestry are never neutral`() {
        val codec = HarnessOwnedContext()
        assertNull(codec.decode(null))
        assertNull(codec.decode("private malformed payload"))
        assertNull(codec.decode("x".repeat(4097)))
        val valid = codec.encode(HarnessId("owner"), HarnessCallOrigin())
        assertNull(codec.decode(valid.replace("\"version\":1", "\"version\":2")))
        assertFailsWith<IllegalArgumentException> {
            codec.encode(HarnessId("owner"), HarnessCallOrigin(sendChain = mapOf(HarnessId("owner") to 4)))
        }
    }

    @Test
    fun `send increments only current owner and refuses fourth send or hook relay`() {
        val first = HarnessId("first")
        val second = HarnessId("second")
        val origin = HarnessCallOrigin().forSend(first).forSend(second).forSend(first).forSend(first)
        assertEquals(mapOf(first to 3, second to 1), origin.sendChain)
        assertFailsWith<IllegalStateException> { origin.forSend(first) }
        assertFailsWith<IllegalStateException> { HarnessCallOrigin(true).forSend(second) }
    }
}
