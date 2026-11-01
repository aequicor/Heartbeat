package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DeepSeekFailureTest {
    @Test
    fun `remote rejection is a rejected request, not a transport failure`() {
        val request = RequestId("request-1")

        val failure = dshEngineFailure(AcpException.Remote(-32602, "Invalid params"), request)

        assertEquals(EngineFailure.Request(RequestFailureReason.Invalid, request), failure)
    }

    @Test
    fun `remote rejection without a request keeps the classification`() {
        val failure = dshEngineFailure(AcpException.Remote(-32602, "Invalid params"), null)

        assertEquals(EngineFailure.Request(RequestFailureReason.Invalid, null), failure)
    }

    @Test
    fun `protocol violation stays a transport failure`() {
        val failure = dshEngineFailure(AcpException.Protocol(), null)

        assertIs<EngineFailure.Transport>(failure)
        assertEquals(TransportFailureReason.ProtocolViolation, failure.reason)
    }

    @Test
    fun `engine failure passes through unchanged`() {
        val original = EngineFailure.Session(SessionFailureReason.Busy)

        val failure = dshEngineFailure(EngineException(original), null)

        assertEquals(original, failure)
    }

    @Test
    fun `unexpected error is a crashed engine`() {
        val failure = dshEngineFailure(IllegalStateException("boom"), RequestId("request-2"))

        assertEquals(EngineFailure.Engine(EngineFailureReason.Crashed), failure)
    }
}
