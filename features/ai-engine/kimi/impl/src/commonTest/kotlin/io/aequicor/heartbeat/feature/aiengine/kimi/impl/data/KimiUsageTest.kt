package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class KimiUsageTest {
    @Test
    fun `usage update exposes native context occupancy and capacity`() {
        val update = Json.parseToJsonElement(
            """{"sessionUpdate":"usage_update","used":182344,"size":1048576}""",
        ).jsonObject

        val usage = kimiContextUsage(update, NOW)

        assertEquals(182344L, usage?.usedTokens)
        assertEquals(1048576L, usage?.capacityTokens)
        assertEquals(NOW, usage?.observation?.checkedAt)
        assertEquals(false, usage?.observation?.isStale)
    }

    @Test
    fun `invalid context values stay unknown`() {
        val negative = Json.parseToJsonElement(
            """{"sessionUpdate":"usage_update","used":-1,"size":1000}""",
        ).jsonObject
        val zeroCapacity = Json.parseToJsonElement(
            """{"sessionUpdate":"usage_update","used":10,"size":0}""",
        ).jsonObject

        assertNull(kimiContextUsage(negative, NOW))
        assertNull(kimiContextUsage(zeroCapacity, NOW))
    }

    @Test
    fun `kimi metadata exposes quota windows and booster credits`() {
        val update = Json.parseToJsonElement(
            """{"sessionUpdate":"usage_update","used":10,"size":1000,"_meta":{"kimiCode":{
                "rateLimits":{
                    "summary":{"window":"1w","used":35,"limit":100,"resetAt":"2026-08-06T13:38:24Z"},
                    "limits":[{"window":"5h","used":20,"limit":50,"resetAt":"2026-07-31T14:38:24Z"}],
                    "booster":{"balanceCents":500,"totalCents":1000,"currency":"USD"}
                }
            }}}""",
        ).jsonObject

        val usage = kimiProviderUsage(update, NOW)

        assertEquals(listOf("1w", "5h"), usage?.windows?.map { it.id })
        assertEquals(listOf(35.0, 40.0), usage?.windows?.map { it.usedPercent })
        assertEquals(listOf(10080L, 300L), usage?.windows?.map { it.windowDurationMinutes })
        assertEquals(Instant.parse("2026-08-06T13:38:24Z"), usage?.windows?.first()?.resetsAt)
        assertEquals(5.0, usage?.credits?.remaining)
        assertEquals(10.0, usage?.credits?.total)
        assertEquals("USD", usage?.credits?.currency)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-29T12:00:00Z")
    }
}
