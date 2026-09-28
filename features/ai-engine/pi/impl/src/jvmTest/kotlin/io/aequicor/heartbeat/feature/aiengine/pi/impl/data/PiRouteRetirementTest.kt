package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSecretId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceInfo
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import kotlin.test.Test
import kotlin.test.assertEquals

class PiRouteRetirementTest {
    @Test
    fun `moving a binding to another source retires the unused source`() {
        assertEquals(setOf(first.info.id), retiredSources(mapOf("a" to first), mapOf("a" to second)))
    }

    @Test
    fun `a source still used by another binding keeps its runtime`() {
        val before = mapOf("a" to first, "b" to first)
        assertEquals(emptySet(), retiredSources(before, mapOf("a" to second, "b" to first)))
        assertEquals(emptySet(), retiredSources(before, mapOf("b" to first)))
    }

    @Test
    fun `a revision change retires the pooled runtime of that source`() {
        val rotated = first.copy(info = first.info.copy(revision = AuthRevision.Known("2")))
        assertEquals(setOf(first.info.id), retiredSources(mapOf("a" to first), mapOf("a" to rotated)))
    }

    @Test
    fun `rebinding an identical route and removing the last binding behave as expected`() {
        assertEquals(emptySet(), retiredSources(mapOf("a" to first), mapOf("a" to first)))
        assertEquals(setOf(first.info.id), retiredSources(mapOf("a" to first), emptyMap()))
    }

    private companion object {
        val first = key("first")
        val second = key("second")

        fun key(id: String) = AuthSource.ManagedKey(
            AuthSourceInfo(AuthSourceId(id), "Key $id", AuthRevision.Known("1")),
            AuthScope(ProviderId("anthropic"), EndpointOrigin("https://api.anthropic.com")),
            AuthSecretId(id),
        )
    }
}
