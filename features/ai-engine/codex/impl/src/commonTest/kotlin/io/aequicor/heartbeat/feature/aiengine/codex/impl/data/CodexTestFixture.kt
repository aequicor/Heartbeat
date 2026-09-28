package io.aequicor.heartbeat.feature.aiengine.codex.impl.data
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
import io.aequicor.heartbeat.feature.aiengine.codex.api.CodexEngine
import io.aequicor.heartbeat.feature.aiengine.codex.api.CodexLocalConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.searchengine.api.ResourceContent
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngineTools
import io.aequicor.heartbeat.feature.searchengine.api.SearchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class FakeWire : CodexWire {
    val incoming = Channel<JsonObject>(Channel.UNLIMITED)
    val written = mutableListOf<JsonObject>()
    var handler: suspend (JsonObject) -> Unit = { message ->
        if (message["id"] != null && message["method"] != null) reply(message, JsonObject(emptyMap()))
    }
    var closed = false
    override val messages = incoming.receiveAsFlow()
    override suspend fun write(message: JsonObject) {
        written += message
        handler(message)
    }
    suspend fun reply(request: JsonObject, result: JsonObject) {
        incoming.send(
            json("id" to checkNotNull(request["id"]), "result" to result),
        )
    }
    suspend fun error(request: JsonObject) {
        incoming.send(
            json("id" to checkNotNull(request["id"]), "error" to json("code" to JsonPrimitive(INVALID_PARAMS))),
        )
    }
    suspend fun event(method: String, params: JsonObject, id: JsonElement? = null) {
        incoming.send(
            JsonObject(
                mapOf("method" to method.json(), "params" to params) +
                    if (id == null) emptyMap() else mapOf("id" to id),
            ),
        )
    }
    override fun close() {
        closed = true
        incoming.close()
    }

    private companion object {
        const val INVALID_PARAMS = -32602
    }
}

internal class Fixture(
    val test: TestScope,
    val search: SearchEngine = object : SearchEngine {
        override suspend fun search(query: String, count: Int, native: EngineFeatures?): List<SearchResult> =
            emptyList()
        override suspend fun fetch(url: String, native: EngineFeatures?): ResourceContent = error("unavailable")
    },
    searchTools: Boolean = true,
    configuration: CodexLocalConfiguration = CodexLocalConfiguration(),
) {
    val dispatcher = StandardTestDispatcher(test.testScheduler)
    val dispatchers = object : DispatcherProvider {
        override val main = dispatcher
        override val default = dispatcher
        override val io = dispatcher
    }
    val profile = FakeScope(test.backgroundScope)
    val wire = FakeWire()
    val rpc = CodexRpc(wire, test.backgroundScope)
    val target = EngineTarget(CodexEngine.Id, EngineBindingId("binding"), ModelId("model"))
    var account = json("type" to "chatgpt".json(), "email" to "local@example.invalid".json())
    var threadTurns: List<JsonObject> = emptyList()
    var onTurn: suspend (JsonObject) -> Unit = { message ->
        wire.reply(
            message,
            json("turn" to json("id" to "native-turn".json())),
        )
    }
    var isSearchEnabled = true
    val workspacePaths = mutableMapOf<WorkspaceRef, String>()
    val environment = CodexRuntimeEnvironment(
        configuration,
        object : FeatureToggles {
            override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flow { emit(get(toggle)) }

            @Suppress("UNCHECKED_CAST")
            override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T =
                (toggle != SearchEngineTools || isSearchEnabled) as T
        },
        dispatchers,
        FakeLauncher(),
        object : ScopeFactory {
            override fun child(parent: ScopeHandle, name: String, restored: SavedBundle?): OwnedScope = FakeScope(
                test.backgroundScope,
            )
        },
        search,
        profile,
        object : LocalWorkspaces {
            override val isAvailable = true
            override fun observe(): Flow<List<LocalWorkspace>> = flowOf(emptyList())
            override suspend fun register(directory: String): LocalWorkspace = error("Not used")
            override suspend fun resolve(ref: WorkspaceRef): String? = workspacePaths[ref]
        },
    )
    val runtime = CodexRuntime(
        RuntimeIdentity(CodexEngine.Id, AuthSourceId("codex.local"), AuthRevision.Unknown),
        rpc,
        environment,
        searchTools,
    )
    init {
        wire.handler = { message ->
            when (message.text("method")) {
                "account/read" -> wire.reply(message, json("account" to account))

                "thread/start", "thread/resume" -> wire.reply(
                    message,
                    json("thread" to json("id" to "thread".json(), "turns" to JsonArray(emptyList()))),
                )

                "thread/read" -> wire.reply(
                    message,
                    json("thread" to json("id" to "thread".json(), "turns" to JsonArray(threadTurns))),
                )

                "turn/start" -> onTurn(message)

                "turn/interrupt" -> wire.reply(message, JsonObject(emptyMap()))

                "initialize" -> wire.reply(message, JsonObject(emptyMap()))
            }
        }
    }
    suspend fun open(): ActiveSession = runtime.create(CreateSessionRequest(target))
    suspend fun event(method: String, vararg fields: Pair<String, JsonElement>, id: JsonElement? = null) =
        wire.event(method, json("threadId" to "thread".json(), *fields), id)
}

internal class FakeScope(override val coroutineScope: CoroutineScope) : OwnedScope {
    override val name = "test"
    override var isClosed = false
    private val actions = mutableListOf<() -> Unit>()
    override val savedState = object : ScopeSavedState {
        override fun <T : Any> consume(key: String, serializer: KSerializer<T>): T? = null
        override fun <T : Any> register(key: String, serializer: KSerializer<T>, supplier: () -> T?) = Unit
        override fun unregister(key: String) = Unit
        override fun snapshot() = SavedBundle(emptyMap())
    }
    override fun onClose(action: () -> Unit): DisposableHandle {
        actions += action
        return DisposableHandle { actions.remove(action) }
    }
    override fun close() {
        isClosed = true
        actions.reversed().forEach { it() }
    }
}

internal class FakeLauncher : MachineLauncher {
    override fun <S : MachineState, I : MachineIntent, E : MachineEffect, O : MachineOutput> launch(
        spec: MachineSpec<S, I, E, O>,
        scope: ScopeHandle,
        effects: EffectHandler<E, I>,
    ): Machine<S, I, O> = object : Machine<S, I, O> {
        override val name = spec.name
        override val state = MutableStateFlow(spec.initial)
        override val outputs = MutableSharedFlow<O>(extraBufferCapacity = 16)
        override suspend fun send(intent: I): SendResult {
            val resolution = spec.resolve(state.value, intent) ?: return SendResult.Ignored
            state.value = resolution.to
            resolution.outputs.forEach { outputs.emit(it) }
            resolution.effects.forEach { effect ->
                effects.handle(
                    effect,
                    object : EffectScope<I> {
                        override suspend fun send(intent: I): SendResult = SendResult.Ignored
                    },
                )
            }
            return SendResult.Accepted
        }
    }
}

internal fun <T : EngineFeature> ActiveSession.feature(key: EngineFeatureKey<T>): T =
    (features.resolve(key) as FeatureAccess.Available).feature
internal val Prompt = PromptRequest(RequestId("prompt"), listOf(ContentPart.Text("hello")))
