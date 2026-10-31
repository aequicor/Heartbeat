@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package io.aequicor.heartbeat.bundles.engines

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpConnection
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpImplementation
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpInitialization
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPermissionOption
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPermissionOutcome
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPermissionRequest
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPromptResult
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpSession
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionAnalysis
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionSafetyEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSecuritySettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolDecisionMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeActionAnalysis
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.SecurityMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.ActionSafetyGate
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.DefaultAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.safety.ActionSafetyPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ToolPolicyResolver
import io.aequicor.heartbeat.feature.aiengine.kimi.api.KimiEngine
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.KimiRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class KimiSecurityIntegrationTest {
    @Test
    fun `automatic mode reviews an ordinary native command without asking the user`() = runBlocking {
        withTimeout(20_000) {
            val fixture = SecurityFixture(SecurityMode.Automatic)
            try {
                fixture.start()

                assertEquals(
                    AcpPermissionOutcome.Selected("allow"),
                    fixture.runtime.requestPermission(fixture.permission()),
                )
                assertEquals(1, fixture.reviews.size)
            } finally {
                fixture.finish()
            }
        }
    }

    @Test
    fun `full trust skips both review and manual approval for a native command`() = runBlocking {
        withTimeout(20_000) {
            val fixture = SecurityFixture(SecurityMode.Trust)
            try {
                fixture.start()

                assertEquals(
                    AcpPermissionOutcome.Selected("allow"),
                    fixture.runtime.requestPermission(fixture.permission()),
                )
                assertEquals(emptyList(), fixture.reviews)
            } finally {
                fixture.finish()
            }
        }
    }
}

private class SecurityFixture(private val mode: SecurityMode) {
    val reviews = mutableListOf<IsolatedPrompt>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connection = FakeConnection()
    private val target = EngineTarget(KimiEngine.Id, EngineBindingId("binding"), ModelId("kimi-for-coding"))
    private val settings = TestSecuritySettings(mode)
    private val tools = DefaultAgentTools(
        emptySet(),
        policies = ToolPolicyResolver { _, _, _ -> ResolvedToolPolicy(nativeOn = setOf("execute")) },
        settings = settings,
        actionSafety = ActionSafetyGate(
            NativeActionAnalysis { _, _ -> ActionAnalysis.Unknown("No structural shell contract") },
            ActionSafetyPolicy(
                lazyOf(
                    IsolatedPrompts {
                        reviews += it
                        IsolatedAnswer.Answered("ALLOW")
                    },
                ),
            ),
            enabled = { ActionSafetyEnabled.default },
        ),
    )
    val runtime = KimiRuntime(
        RuntimeIdentity(KimiEngine.Id, KimiEngine.AuthSource, AuthRevision.Known("test")),
        connection,
        scope,
        AcpInitialization(1),
        directory = { "/workspace" },
        toggles = DefaultToggles,
        tools = tools,
    )
    private lateinit var session: ActiveSession

    suspend fun start() {
        connection.runtime = runtime
        session = runtime.create(CreateSessionRequest(target, WorkspaceRef("project")))
        val request = PromptRequest(
            RequestId("request"),
            listOf(ContentPart.Text("Inspect the project status")),
            trust = if (mode == SecurityMode.Trust) TrustLevel.Full else TrustLevel.AutoEdits,
            decisionMode = AgentToolDecisionMode.AutomaticSafety,
        )
        tools.bindTurn(session.ref, request.id, TurnId("facade-turn"), target, request.decisionMode)
        tools.bindUserInstruction(session.ref, request.id, TurnId("facade-turn"), "Inspect the project status")
        session.sender().send(request)
    }

    fun permission() = AcpPermissionRequest(
        session.ref.nativeId,
        buildJsonObject {
            put("toolCallId", "call")
            put("kind", "execute")
            put("rawInput", buildJsonObject { put("command", "git status") })
            put("_meta", buildJsonObject { put("heartbeatTurn", connection.turnToken) })
        },
        listOf(AcpPermissionOption("allow", "Allow", "allow_once")),
    )

    suspend fun finish() {
        connection.prompt.complete(AcpPromptResult("end_turn"))
        runtime.close()
        scope.cancel()
    }
}

private class TestSecuritySettings(mode: SecurityMode) : AgentSecuritySettings {
    override val modes = MutableStateFlow(mode)
    override suspend fun load(): SecurityMode = modes.value
    override suspend fun save(mode: SecurityMode) {
        modes.value = mode
    }
}

private object DefaultToggles : FeatureToggles {
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = MutableStateFlow(value(toggle))
    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = value(toggle)

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> value(toggle: FeatureToggle<T>): T = when (toggle) {
        is FeatureToggle.Flag -> toggle.default as T
        is FeatureToggle.Choice -> toggle.default
    }
}

private class FakeConnection : AcpConnection {
    var prompt = CompletableDeferred<AcpPromptResult>()
    var turnToken = ""
    lateinit var runtime: KimiRuntime

    override suspend fun initialize(client: AcpImplementation) = AcpInitialization(1)
    override suspend fun authenticate(methodId: String) = Unit
    override suspend fun newSession(cwd: String) = AcpSession("native", JsonObject(emptyMap()))
    override suspend fun loadSession(sessionId: String, cwd: String) = AcpSession(sessionId, JsonObject(emptyMap()))
    override suspend fun prompt(sessionId: String, content: List<JsonObject>) = prompt.await()
    override suspend fun prompt(sessionId: String, content: List<JsonObject>, metadata: JsonObject): AcpPromptResult {
        turnToken = metadata["heartbeatTurn"]?.toString()?.trim('"').orEmpty()
        runtime.onPromptSubmitted(sessionId)
        return prompt.await()
    }

    override suspend fun cancel(sessionId: String) = Unit
    override suspend fun close() = Unit
}

private fun ActiveSession.sender() = (features.resolve(SendsPrompts) as FeatureAccess.Available).feature
