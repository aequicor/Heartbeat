package io.aequicor.heartbeat.feature.aiengine.pi.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionAccessGroup
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFamily
import kotlin.test.Test
import kotlin.test.assertEquals

class PiAccessGroupsTest {
    @Test
    fun `Pi is described as an external multi provider engine`() {
        assertEquals(EngineFamily.MultiProvider, PiDescriptor.family)
    }

    @Test
    fun `access categories distinguish plans provider presets and compatible APIs`() {
        val groups = PiDescriptor.connectionMethods.groupBy { it.accessGroup }.mapValues { (_, methods) ->
            methods.map { it.id.value }.toSet()
        }
        assertEquals(
            setOf("zai", "qwen-token-plan", "qwen-token-plan-individual", "qwen-token-plan-cn"),
            groups[ConnectionAccessGroup.Subscription],
        )
        assertEquals(setOf("openai", "anthropic", "google"), groups[ConnectionAccessGroup.Provider])
        assertEquals(setOf("openai-compatible"), groups[ConnectionAccessGroup.OpenAiCompatible])
        assertEquals(setOf("anthropic-compatible"), groups[ConnectionAccessGroup.AnthropicCompatible])
    }
}
