package io.aequicor.heartbeat.lint.detekt

import dev.detekt.api.RuleSetProvider
import org.junit.jupiter.api.Test
import java.util.ServiceLoader
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeartbeatRuleSetProviderTest {

    @Test
    fun `provider is discoverable and exposes all rules`() {
        val provider = ServiceLoader.load(RuleSetProvider::class.java).single { it is HeartbeatRuleSetProvider }
        val rules = provider.instance().rules.keys.map { it.value }

        assertEquals("heartbeat", provider.ruleSetId.value)
        assertEquals(11, rules.size)
        assertTrue("SwallowedError" in rules)
        assertTrue("FeatureLayerPlacement" in rules)
        assertTrue("FeatureLayerDependency" in rules)
    }

    @Test
    fun `default config lists every rule`() {
        val config = javaClass.classLoader.getResource("config/config.yml")?.readText().orEmpty()
        HeartbeatRuleSetProvider().instance().rules.keys.forEach { rule ->
            assertTrue("  ${rule.value}:" in config, "config/config.yml misses ${rule.value}")
        }
    }
}
