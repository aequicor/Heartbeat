package io.aequicor.heartbeat.feature.harness.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOwner
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.harness.impl.data.delivery.HarnessContentHook
import io.aequicor.heartbeat.feature.harness.impl.data.delivery.KeyValueHarnessDeliveryStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessActiveAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessContextDelivery
import io.aequicor.heartbeat.feature.harness.impl.domain.harness
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `production hook forwards receipts and sends removals after acknowledged context`() = runTest {
        val clock = RunDeliveryClock()
        val storage = KeyValueHarnessDeliveryStorage(RunDeliveryTestStore(clock), clock)
        val delivery = HarnessContextDelivery(storage, backgroundScope) { it.encodeUtf8().sha256().hex() }
        var active = listOf(harness)
        val hook = HarnessContentHook(
            { true },
            { false },
            lazy { HarnessActiveAccess { _, _ -> active } },
            lazy { delivery },
        )
        val first = assertNotNull(hook.preparePrompt(context, "hello", "retained"))
        assertNull(storage.snapshot(context.session).activeSetSha)
        first.receipt!!.accepted(context, "retained")
        assertNull(hook.preparePrompt(context, "next", "retained"))
        assertNotNull(hook.preparePrompt(context, "next", "compacted"))
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
}
