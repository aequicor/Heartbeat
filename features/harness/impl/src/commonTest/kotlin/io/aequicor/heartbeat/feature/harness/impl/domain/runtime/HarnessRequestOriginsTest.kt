package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOwner
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HarnessRequestOriginsTest {
    @Test
    fun `storage failures never become neutral ancestry`() = runTest {
        val storage = MemoryHarnessRequestAncestry().apply { failure = IllegalStateException("unavailable") }
        val origins = HarnessRequestOrigins(storage)
        val context = SessionHookContext(dispatchSession, null, RequestId("request"), null, SessionOwner("handle"))
        assertFailsWith<IllegalStateException> { origins.origin(context) }
        assertFailsWith<IllegalStateException> {
            origins.register(dispatchSession, RequestId("request"), HarnessCallOrigin(true))
        }
    }

    @Test
    fun `repeated registration can only strengthen ancestry for the exact session request`() = runTest {
        val origins = HarnessRequestOrigins(MemoryHarnessRequestAncestry())
        val harness = HarnessId("owner")
        val request = RequestId("request")
        val context = SessionHookContext(dispatchSession, null, request, null, SessionOwner("handle"))
        origins.register(dispatchSession, request, HarnessCallOrigin(true, mapOf(harness to 2)))
        origins.register(dispatchSession, request, HarnessCallOrigin(false, mapOf(harness to 1)))
        assertTrue(origins.origin(context).isHookRestricted)
        assertEquals(2, origins.origin(context).sendChain[harness])
        assertFalse(origins.origin(context.copy(request = RequestId("next"))).isHookRestricted)
        assertFalse(origins.origin(context.copy(session = dispatchSession.copy(nativeId = "other"))).isHookRestricted)
        assertFalse(origins.origin(context.copy(request = null)).isHookRestricted)
    }

    @Test
    fun `handle replacement cannot erase request ancestry`() = runTest {
        val origins = HarnessRequestOrigins(MemoryHarnessRequestAncestry())
        val request = RequestId("request")
        val expected = HarnessCallOrigin(sendChain = mapOf(HarnessId("owner") to 3))
        origins.register(dispatchSession, request, expected)
        val context = SessionHookContext(dispatchSession, null, request, null, SessionOwner("newowner"))
        assertEquals(expected, origins.origin(context))
    }
}
