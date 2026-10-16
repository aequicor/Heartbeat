package io.aequicor.heartbeat.feature.harness.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOwner
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessTools
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.impl.data.delivery.HarnessContentHook
import io.aequicor.heartbeat.feature.harness.impl.data.delivery.KeyValueHarnessDeliveryStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HARNESS_CONTEXT_USAGE
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessActiveAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessContextDelivery
import io.aequicor.heartbeat.feature.harness.impl.domain.harness
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HarnessContentHookTest {
    private val context = SessionHookContext(
        dispatchSession,
        null,
        RequestId("request"),
        TurnId("turn"),
        SessionOwner("owner"),
    )

    @Test
    fun `hook preloads instructions once and restores them after compaction without loading skill bodies`() = runTest {
        val clock = RunDeliveryClock()
        val storage = KeyValueHarnessDeliveryStorage(RunDeliveryTestStore(clock), clock)
        val delivery = HarnessContextDelivery(storage, backgroundScope) { it.encodeUtf8().sha256().hex() }
        val instruction = HarnessItem.Instruction(ItemId("note"), ItemName("note"), "Follow this instruction")
        val skill = HarnessItem.Skill(ItemId("skill"), ItemName("skill"), "Use for coding", "On-demand skill body")
        var active = listOf(harness.copy(items = listOf(instruction, skill)))
        val hook = HarnessContentHook(
            { true },
            { false },
            lazy { HarnessActiveAccess { _, _ -> active } },
            lazy { delivery },
        )
        val first = assertNotNull(hook.preparePrompt(context, "hello", "retained"))
        assertTrue(first.text.contains(instruction.text))
        assertTrue(first.text.contains(HARNESS_CONTEXT_USAGE))
        assertTrue(first.text.contains(skill.description))
        assertFalse(first.text.contains(skill.body))
        assertNull(storage.snapshot(context.session).activeSetSha)
        first.receipt!!.accepted(context, "retained")
        assertNull(hook.preparePrompt(context, "next", "retained"))
        val restored = assertNotNull(hook.preparePrompt(context, "next", "compacted"))
        assertEquals(first.text, restored.text)
        restored.receipt!!.accepted(context, "compacted")
        assertNull(hook.preparePrompt(context, "next", "compacted"))
        val updated = instruction.copy(text = "Updated instruction")
        active = listOf(harness.copy(revision = harness.revision + 1, items = listOf(updated, skill)))
        val changed = assertNotNull(hook.preparePrompt(context, "next", "compacted"))
        assertTrue(changed.text.contains(updated.text))
        assertFalse(changed.text.contains(instruction.text))
        changed.receipt!!.accepted(context, "compacted")
        assertNull(hook.preparePrompt(context, "next", "compacted"))
        active = emptyList()
        val disabled = assertNotNull(hook.preparePrompt(context, "next", "compacted"))
        assertTrue(disabled.text.contains("отключён"))
    }

    @Test
    fun `disabled refreshed and slash prompt paths do not construct storage or read content`() = runTest {
        var enabled = false
        var refreshed = false
        var reads = 0
        val hook = HarnessContentHook(
            { enabled },
            { refreshed },
            lazy {
                HarnessActiveAccess { _, _ ->
                    reads++
                    emptyList()
                }
            },
            lazy { error("Delivery must remain lazy") },
        )
        assertNull(hook.preparePrompt(context, "hello", null))
        enabled = true
        refreshed = true
        assertNull(hook.preparePrompt(context, "hello", null))
        refreshed = false
        assertNull(hook.preparePrompt(context, "/compact", null))
        assertEquals(0, reads)
    }

    @Test
    fun `truncated frozen context never points at a tool the session may not declare`() = runTest {
        val clock = RunDeliveryClock()
        val storage = KeyValueHarnessDeliveryStorage(RunDeliveryTestStore(clock), clock)
        val delivery = HarnessContextDelivery(storage, backgroundScope) { it.encodeUtf8().sha256().hex() }
        val items = (0..8).map { HarnessItem.Instruction(ItemId("i$it"), ItemName("i$it"), "$it".repeat(2_000)) }
        val hook = HarnessContentHook(
            { true },
            { false },
            lazy { HarnessActiveAccess { _, _ -> listOf(harness.copy(items = items)) } },
            lazy { delivery },
        )
        val block = assertNotNull(hook.preparePrompt(context, "hello", "native")).text
        assertTrue(block.endsWith("Контекст усечён."))
        assertFalse(block.contains(HarnessTools.CONTEXT))
    }
}
