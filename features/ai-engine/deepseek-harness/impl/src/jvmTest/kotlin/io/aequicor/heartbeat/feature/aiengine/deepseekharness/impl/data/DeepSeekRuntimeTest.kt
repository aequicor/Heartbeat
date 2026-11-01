package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpConnection
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpImplementation
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpInitialization
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPromptResult
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpSession
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.AcceptsImages
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeepSeekRuntimeTest {
    @Test
    fun `create applies the requested model through the config option`() = runTest {
        val connection = FakeAcpConnection()
        val runtime = runtime(connection)
        runtime.create(CreateSessionRequest(target(ModelId("""["deepseek-account","deepseek-flash"]"""))))
        assertEquals(listOf("model" to """["deepseek-account","deepseek-flash"]"""), connection.configured)
    }

    @Test
    fun `bare legacy model id resolves through the account route`() = runTest {
        val connection = FakeAcpConnection()
        val runtime = runtime(connection)
        runtime.create(CreateSessionRequest(target(ModelId("deepseek-flash"))))
        assertEquals(listOf("model" to """["deepseek-account","deepseek-flash"]"""), connection.configured)
    }

    @Test
    fun `create fails when the session does not advertise the model option`() = runTest {
        val connection = FakeAcpConnection(metadata = JsonObject(emptyMap()))
        val runtime = runtime(connection)
        val failure = assertFailsWith<EngineException> {
            runtime.create(CreateSessionRequest(target(ModelId("deepseek-flash"))))
        }
        assertIs<EngineFailure.Engine>(failure.failure)
        assertEquals(
            EngineFailureReason.UnsupportedCapability,
            (failure.failure as EngineFailure.Engine).reason,
        )
    }

    @Test
    fun `closing the last lease retires the idle session`() = runTest {
        val connection = FakeAcpConnection()
        val runtime = runtime(connection)
        val session = runtime.create(CreateSessionRequest(target(ModelId("deepseek-flash"))))
        session.close()
        assertEquals(listOf("session-1"), connection.closedSessions)
    }

    @Test
    fun `initialize image capability reaches the catalog and the active session`() = runTest {
        val runtime = runtime(
            FakeAcpConnection(),
            json("""{"promptCapabilities":{"image":true,"embeddedContext":false}}"""),
        )

        val catalog = runtime.discoverCatalog(EngineBindingId("binding"))
        assertTrue(catalog.inputSupport.accepts("image/png"))

        val session = runtime.create(CreateSessionRequest(target(ModelId("deepseek-flash"))))
        val access = assertIs<FeatureAccess.Available<AcceptsImages>>(session.features.resolve(AcceptsImages))
        assertEquals(DeepSeekImageMediaTypes, access.feature.mediaTypes)
    }

    @Test
    fun `image support follows the multimodal model set and ignores routes DSH rejects`() = runTest {
        val runtime = runtime(
            FakeAcpConnection(),
            json("""{"promptCapabilities":{"image":true}}"""),
        )
        val accountPro = ModelId("""["deepseek-account","deepseek-v4-pro"]""")
        val officialFlash = ModelId("""["deepseek-official","deepseek-v4-flash"]""")

        assertTrue(runtime.inputSupportFor(ModelId("deepseek-flash")).accepts("image/png"))
        assertTrue(runtime.inputSupportFor(ModelId("""["deepseek-account","deepseek-flash"]""")).accepts("image/png"))
        // DSH answers -32602 for an image on these routes, so they must not advertise image input.
        assertFalse(runtime.inputSupportFor(ModelId("deepseek-v4-pro")).accepts("image/png"))
        assertFalse(runtime.inputSupportFor(accountPro).accepts("image/png"))
        assertFalse(runtime.inputSupportFor(ModelId("deepseek-v4-flash")).accepts("image/png"))
        assertFalse(runtime.inputSupportFor(officialFlash).accepts("image/png"))
    }

    private fun target(model: ModelId) = EngineTarget(
        DeepSeekEngine.Id,
        EngineBindingId("binding"),
        model,
    )

    private fun runtime(connection: AcpConnection, capabilities: JsonObject = JsonObject(emptyMap())) = DeepSeekRuntime(
        RuntimeIdentity(DeepSeekEngine.Id, AuthSourceId("auth"), AuthRevision.Unknown),
        connection,
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
        directory = { "C:\\workspace" },
        capabilities = capabilities,
    )

    private fun json(value: String): JsonObject = Json.parseToJsonElement(value) as JsonObject

    private class FakeAcpConnection(
        private val metadata: JsonObject = Json.parseToJsonElement(
            """{"configOptions":[{"id":"model","category":"model","options":[]}]}""",
        ) as JsonObject,
    ) : AcpConnection {
        val configured = mutableListOf<Pair<String, String>>()
        val closedSessions = mutableListOf<String>()
        private var nextId = 0

        override suspend fun initialize(client: AcpImplementation) =
            AcpInitialization(1, JsonObject(emptyMap()), emptyList())

        override suspend fun authenticate(methodId: String) = Unit

        override suspend fun newSession(cwd: String) = AcpSession("session-${++nextId}", metadata)

        override suspend fun loadSession(sessionId: String, cwd: String) = AcpSession(sessionId, metadata)

        override suspend fun resumeSession(sessionId: String, cwd: String) = AcpSession(sessionId, metadata)

        override suspend fun setConfigOption(sessionId: String, optionId: String, value: String) {
            configured += optionId to value
        }

        override suspend fun closeSession(sessionId: String) {
            closedSessions += sessionId
        }

        override suspend fun prompt(sessionId: String, content: List<JsonObject>) = AcpPromptResult("end_turn")

        override suspend fun cancel(sessionId: String) = Unit

        override suspend fun close() = Unit
    }
}
