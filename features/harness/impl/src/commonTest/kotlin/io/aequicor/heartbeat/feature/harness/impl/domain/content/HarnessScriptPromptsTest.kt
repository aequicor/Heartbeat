package io.aequicor.heartbeat.feature.harness.impl.domain.content

import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRegistrationFixture
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HarnessScriptPromptsTest {
    @Test
    fun `evaluation can render its own approved templates and replacement revokes the old facade`() = runTest {
        val fixture = HarnessRegistrationFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val template = HarnessItem.Template(ItemId("prompt"), ItemName("draft"), "", "{{value}}", setOf("value"))
        fixture.desired = fixture.desired.let {
            it.copy(harness = it.harness.copy(items = it.harness.items + template))
        }
        fixture.evaluate = { script ->
            assertEquals(
                "literal {{nested}}",
                script.prompts.render(ItemName("draft"), mapOf("value" to "literal {{nested}}")),
            )
        }
        fixture.activate()
        val old = fixture.scripts.single().prompts
        assertEquals("live", old.render(ItemName("draft"), mapOf("value" to "live")))
        assertFailsWith<IllegalArgumentException> { old.render(ItemName("missing")) }
        fixture.evaluate = {}
        fixture.desired = fixture.desired.copy(generation = 2)
        fixture.activate()
        assertFailsWith<IllegalStateException> { old.render(ItemName("draft"), mapOf("value" to "stale")) }
        assertEquals("new", fixture.scripts.last().prompts.render(ItemName("draft"), mapOf("value" to "new")))
        fixture.isEnabled = false
        assertFailsWith<IllegalStateException> {
            fixture.scripts.last().prompts.render(ItemName("draft"), mapOf("value" to "disabled"))
        }
    }
}
