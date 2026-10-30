package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class KimiUsageTest {
    @Test
    fun `local API quota distinguishes monthly buckets and scales ratios`() {
        val result = kimiLocalProviderUsage(
            Json.parseToJsonElement(
                """{"code":0,"data":{"kind":"ok","quota":{"usages":{
                "limit5h":{"usedRatio":0.25,"resetAt":"2026-10-30T12:00:00Z"},
                "monthTotal":{"usedRatio":0.1734},"monthCode":{"usedRatio":0.0934},
                "invalid":{"usedRatio":-1}},"extraUsage":{"balanceCents":250,"totalCents":500,"currency":"USD"}}}}""",
            ).jsonObject,
            NOW,
        )
        assertEquals(listOf("limit5h", "monthTotal", "monthCode"), result?.windows?.map { it.id })
        assertEquals(listOf(25.0, 17.34, 9.34), result?.windows?.map { it.usedPercent })
        assertEquals(300L, result?.windows?.first()?.windowDurationMinutes)
        assertEquals(2.5, result?.credits?.remaining)
    }

    @Test
    fun `upstream quota error remains unknown rather than zero`() {
        assertNull(
            kimiLocalProviderUsage(
                Json.parseToJsonElement(
                    """{"code":0,"data":{"kind":"error","message":"unavailable"}}""",
                ).jsonObject,
                NOW,
            ),
        )
    }

    @Test
    fun `native model capacities use model identities and reject invalid capacities`() {
        val limits = kimiLocalModelLimits(
            Json.parseToJsonElement(
                """{"data":{"items":[{"model":"kimi-code/k3-256k","max_context_size":262144},
                {"model":"invalid","max_context_size":0}]}}""",
            ).jsonObject,
        )
        assertEquals(mapOf("kimi-code/k3-256k" to 262144L), limits)
    }

    @Test
    fun `local quota refresh is gated and failed refresh preserves stale data`() = runTest {
        var isEnabled = true
        var calls = 0
        val fresh = kimiLocalProviderUsage(
            Json.parseToJsonElement(
                """{"data":{"kind":"ok","quota":{"usages":{"limit5h":{"usedRatio":0.25}}}}}""",
            ).jsonObject,
            NOW,
        )
        val usage = KimiProviderUsage(fetch = {
            calls++
            if (calls == 1) fresh else null
        }) { isEnabled }
        assertEquals(25.0, usage.refresh().windows.single().usedPercent)
        assertEquals(true, usage.refresh().observation.isStale)
        assertEquals(25.0, usage.state.value.windows.single().usedPercent)
        isEnabled = false
        assertEquals(emptyList(), usage.refresh().windows)
        assertEquals(2, calls)
    }

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
    fun `invalid update keeps the previously reported context and provider state`() {
        val contextUsage = KimiContextUsage()
        contextUsage.receive(
            Json.parseToJsonElement("""{"sessionUpdate":"usage_update","used":10,"size":1000}""").jsonObject,
        )
        contextUsage.receive(
            Json.parseToJsonElement("""{"sessionUpdate":"usage_update","used":-1,"size":0}""").jsonObject,
        )
        assertEquals(10L, contextUsage.state.value?.usedTokens)
        assertEquals(1000L, contextUsage.state.value?.capacityTokens)

        val providerUsage = KimiProviderUsage { true }
        providerUsage.receive(
            Json.parseToJsonElement(
                """{"sessionUpdate":"usage_update","_meta":{"kimiCode":{"rateLimits":{"summary":{"window":"1w"}}}}}""",
            ).jsonObject,
        )
        providerUsage.receive(
            Json.parseToJsonElement("""{"sessionUpdate":"usage_update","_meta":{"kimiCode":{}}}""").jsonObject,
        )
        assertEquals(listOf("1w"), providerUsage.state.value.windows.map { it.id })
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
