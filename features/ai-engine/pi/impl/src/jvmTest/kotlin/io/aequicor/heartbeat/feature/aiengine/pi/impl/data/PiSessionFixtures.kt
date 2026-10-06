package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.di.SavedBundle
import io.aequicor.heartbeat.core.di.ScopeFactory
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridge
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineUsageEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.UnavailableAgentToolBridge
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEnabled
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEngineId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path

internal suspend fun fixture(
    test: TestScope,
    validate: suspend () -> Unit = {},
    transcript: PiTranscript? = null,
    isUsageEnabled: Boolean = EngineUsageEnabled.default,
    acceptIntent: (MachineIntent) -> Boolean = { true },
    tools: ProfileAgentTools = NoAgentTools,
    bridge: AgentToolBridge = UnavailableAgentToolBridge,
    resources: ResourceResolver = ResourceResolver { null },
    targetModel: ModelId = ModelId("anthropic/test"),
    project: WorkspaceRef? = WorkspaceRef("hosted-workspace"),
    areDetachedToolsEnabled: Boolean = false,
    configure: (Int, FakeConnection) -> Unit = { _, _ -> },
): Fixture {
    val target = EngineTarget(PiEngineId, EngineBindingId("binding"), targetModel)
    val workspace = if (tools === NoAgentTools) null else project
    val route = ExecutionRoute(PiEngineId, target.binding, AuthSourceId("source"), AuthRevision.Known("1"), workspace)
    val released = mutableListOf<PiSession>()
    val session = PiSession(
        CreateSessionRequest(target, workspace, areDetachedToolsEnabled),
        route,
        piTestEnvironment(
            test,
            isUsageEnabled,
            acceptIntent = acceptIntent,
            tools = if (tools === NoAgentTools) TestNativeTools else tools,
            bridge = bridge,
            resources = resources,
        ),
        validate,
        { released += it },
    )
    val connections = mutableListOf<FakeConnection>()
    session.start(
        { plan, event, failed ->
            FakeConnection().also {
                it.plan = plan
                it.event = event
                it.failed = failed
                configure(connections.size, it)
                connections += it
            }
        },
        transcript,
        { connections.last().takeIf { it.isTranscriptPersisted }?.sessionFile },
    )
    return Fixture(session, connections, released)
}

internal fun piTestEnvironment(
    test: TestScope,
    isUsageEnabled: Boolean = EngineUsageEnabled.default,
    areEnginesEnabled: Boolean = false,
    acceptIntent: (MachineIntent) -> Boolean = { true },
    tools: ProfileAgentTools = NoAgentTools,
    bridge: AgentToolBridge = UnavailableAgentToolBridge,
    resources: ResourceResolver = ResourceResolver { null },
): PiSessionEnvironment {
    val dispatcher = StandardTestDispatcher(test.testScheduler)
    val dispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = dispatcher
        override val io: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
    }
    val scopes = object : ScopeFactory {
        override fun child(parent: ScopeHandle, name: String, restored: SavedBundle?): OwnedScope =
            FakeScope(test.backgroundScope)
    }
    return PiSessionEnvironment(
        ReducerLauncher(acceptIntent),
        scopes,
        FakeScope(test.backgroundScope),
        dispatchers,
        DefaultPiTestToggles(isUsageEnabled, areEnginesEnabled),
        TestNativeClassifier,
        tools = tools,
        bridge = bridge,
        resources = resources,
    )
}

private class DefaultPiTestToggles(private val isUsageEnabled: Boolean, private val areEnginesEnabled: Boolean) :
    FeatureToggles {
    override fun <T : Any> observe(toggle: FeatureToggle<T>) = flowOf(value(toggle))

    override suspend fun <T : Any> get(toggle: FeatureToggle<T>) = value(toggle)

    // The usage flag is Boolean; this generic test facade preserves the declaration's value type.
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> value(toggle: FeatureToggle<T>): T = when (toggle) {
        EngineUsageEnabled -> isUsageEnabled as T
        AiEngines, PiEnabled -> areEnginesEnabled as T
        is FeatureToggle.Flag, is FeatureToggle.Choice -> toggle.default
    }
}

internal suspend fun Fixture.runningTurn(trust: TrustLevel? = null): TurnId {
    connection.promptAck.complete(JsonObject(emptyMap()))
    val turn = session.send(prompt("tool").copy(trust = trust))
    connection.event(record("""{"type":"agent_start"}"""))
    return turn
}

internal fun approval(id: String, target: String = "ls -la", tool: String = "bash", path: String? = null): JsonObject {
    val message = JsonObject(
        mapOf(
            "toolCallId" to JsonPrimitive("c1"),
            "toolName" to JsonPrimitive(tool),
            "target" to JsonPrimitive(target),
        ) + listOfNotNull(path?.let { "path" to JsonPrimitive(it) }),
    )
    return JsonObject(
        mapOf(
            "type" to JsonPrimitive("extension_ui_request"),
            "id" to JsonPrimitive(id),
            "method" to JsonPrimitive("confirm"),
            "title" to JsonPrimitive("heartbeat.tool-approval"),
            "message" to JsonPrimitive(message.toString()),
        ),
    )
}

internal fun answer(id: String, field: String, value: Boolean) = JsonObject(
    mapOf(
        "type" to JsonPrimitive("extension_ui_response"),
        "id" to JsonPrimitive(id),
        field to JsonPrimitive(value),
    ),
)

internal fun valueAnswer(id: String, value: String) = JsonObject(
    mapOf(
        "type" to JsonPrimitive("extension_ui_response"),
        "id" to JsonPrimitive(id),
        "value" to JsonPrimitive(value),
    ),
)

internal fun prompt(id: String) = PromptRequest(RequestId(id), listOf(ContentPart.Text("Hello")))
internal fun record(json: String) = Json.parseToJsonElement(json).jsonObject
internal data class Fixture(
    val session: PiSession,
    val connections: List<FakeConnection>,
    val released: List<PiSession>,
) {
    val connection: FakeConnection get() = connections.first()
}

internal val TestWorkspace: Path = Files.createTempDirectory("pi-workspace").also { it.toFile().deleteOnExit() }

internal class FakeConnection : PiConnection {
    var plan: PiLaunchPlan? = null
    var event: suspend (JsonObject) -> Unit = {}
    var failed: suspend (EngineFailure) -> Unit = {}
    val promptAck = CompletableDeferred<JsonObject>()
    val abortAck = CompletableDeferred<JsonObject>()
    val modelAck = CompletableDeferred<JsonObject>()
    val commands = mutableListOf<String>()
    val fields = mutableListOf<JsonObject>()
    val sent = mutableListOf<JsonObject>()
    var isClosed = false
    var stopProof: suspend () -> Boolean = { true }
    var stopRequests = 0
    var sessionId = "native"
    var isTranscriptPersisted = true
    var sessionFile: String? = "native.jsonl"
    var model = "test"
    var modelMetadata = JsonObject(emptyMap())
    override var contextWindows: Map<String, Long> = emptyMap()
    var provider = "anthropic"
    var contextWindow = 1000L
    var isStreaming = false
    var stateFailure: EngineException? = null
    var onSetModel: suspend () -> Unit = {}
    var thinkingLevel = "medium"
    var thinkingClamp: String? = null
    var thinkingFailure: EngineException? = null
    var switchFailure: EngineException? = null
    var sessionIdAfterSwitch: String? = null
    var entries = """{"leafId":"later","entries":[
        {"type":"message","id":"stored","parentId":null,"message":{"role":"user","content":"Stored"}},
        {"type":"message","id":"reply","parentId":"stored",
            "message":{"role":"assistant","content":[{"type":"text","text":"Reply"}]}},
        {"type":"message","id":"abandoned","parentId":"stored","message":{"role":"user","content":"Abandoned"}},
        {"type":"compaction","id":"summary","parentId":"reply","summary":"Earlier"},
        {"type":"message","id":"later","parentId":"summary","message":{"role":"user","content":"Later"}}]}"""
    var sendFailure: EngineException? = null
    var beforeSend: suspend () -> Unit = {}
    override var isOpen = true
    override val workingDirectory: Path = TestWorkspace
    override suspend fun command(type: String, fields: JsonObject): JsonObject {
        commands += type
        this.fields += fields
        return when (type) {
            "get_state" -> state()
            "switch_session" -> switchSession()
            "get_entries" -> Json.parseToJsonElement(entries).jsonObject
            "prompt" -> promptAck.await()
            "abort" -> abortAck.await()
            "set_model" -> setModel(fields)
            "set_thinking_level" -> setThinking(fields)
            else -> JsonObject(emptyMap())
        }
    }
    override suspend fun send(record: JsonObject) {
        beforeSend()
        sendFailure?.let {
            sendFailure = null
            throw it
        }
        sent += record
    }
    override fun close() {
        isClosed = true
        isOpen = false
    }

    override suspend fun stopAndAwait(): Boolean {
        stopRequests++
        close()
        return stopProof()
    }

    private fun state(): JsonObject {
        stateFailure?.let { throw it }
        val selectedModel = JsonObject(
            modelMetadata + mapOf(
                "provider" to JsonPrimitive(provider),
                "id" to JsonPrimitive(model),
                "contextWindow" to JsonPrimitive(contextWindow),
            ),
        )
        return Json.parseToJsonElement(
            """{"sessionId":"$sessionId","sessionFile":${sessionFile?.let {
                JsonPrimitive(
                    it,
                )
            }},"isStreaming":$isStreaming,
               "thinkingLevel":"$thinkingLevel",
               "model":$selectedModel}""",
        ).jsonObject
    }

    private fun switchSession(): JsonObject {
        switchFailure?.let { throw it }
        sessionIdAfterSwitch?.let { sessionId = it }
        return JsonObject(emptyMap())
    }

    private suspend fun setModel(fields: JsonObject): JsonObject {
        val selected = fields.string("modelId")
        val result = when (selected) {
            "other" -> modelAck.await()
            "missing" -> throw EngineException(EngineFailure.Request(RequestFailureReason.Invalid))
            else -> JsonObject(emptyMap())
        }
        model = requireNotNull(selected)
        onSetModel()
        return result
    }

    private fun setThinking(fields: JsonObject): JsonObject {
        thinkingFailure?.let { throw it }
        thinkingLevel = thinkingClamp ?: requireNotNull(fields.string("level"))
        return JsonObject(emptyMap())
    }
}

private class ReducerLauncher(private val acceptIntent: (MachineIntent) -> Boolean) : MachineLauncher {
    override fun <S : MachineState, I : MachineIntent, E : MachineEffect, O : MachineOutput> launch(
        spec: MachineSpec<S, I, E, O>,
        scope: ScopeHandle,
        effects: EffectHandler<E, I>,
    ): Machine<S, I, O> = object : Machine<S, I, O> {
        override val name = spec.name
        override val state = MutableStateFlow(spec.initial)
        override val outputs = MutableSharedFlow<O>()
        override suspend fun send(intent: I): SendResult {
            if (scope.isClosed) return SendResult.NotRunning
            if (!acceptIntent(intent)) return SendResult.Ignored
            val resolved = spec.resolve(state.value, intent) ?: return SendResult.Ignored
            state.value = resolved.to
            resolved.effects.forEach { effect ->
                scope.coroutineScope.launch {
                    effects.handle(
                        effect,
                        object : EffectScope<I> {
                            override suspend fun send(input: I): SendResult = SendResult.Accepted
                        },
                    )
                }
            }
            return SendResult.Accepted
        }
    }
}

private class FakeScope(override val coroutineScope: CoroutineScope) : OwnedScope {
    override val name = "test"
    override var isClosed = false
    private val actions = mutableListOf<() -> Unit>()
    override fun close() {
        isClosed = true
        actions.asReversed().forEach { it() }
    }
    override fun onClose(action: () -> Unit): DisposableHandle {
        actions += action
        return DisposableHandle { actions -= action }
    }
    override val savedState = object : ScopeSavedState {
        override fun <T : Any> consume(key: String, serializer: KSerializer<T>): T? = null
        override fun <T : Any> register(key: String, serializer: KSerializer<T>, supplier: () -> T?) = Unit
        override fun unregister(key: String) = Unit
        override fun snapshot() = SavedBundle(emptyMap())
    }
}
