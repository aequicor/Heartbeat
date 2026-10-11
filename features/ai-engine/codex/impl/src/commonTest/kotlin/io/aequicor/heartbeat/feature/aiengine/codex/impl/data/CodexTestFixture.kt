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
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineUsageEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireEnabled
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

/** Shared journal keeps existing assertions readable while each execution process has an independent channel. */
internal class FakeWire private constructor(
    private val journal: WireJournal,
    val off: CodexNativeOff = CodexNativeOff(),
) : CodexWire {
    constructor() : this(WireJournal()) {
        journal.root = this
        journal.handler = { message ->
            if (message["id"] != null && message["method"] != null) reply(message, JsonObject(emptyMap()))
        }
    }
    val incoming = Channel<JsonObject>(Channel.UNLIMITED)
    val written get() = journal.written
    val peers get() = journal.peers.toList()
    var handler: suspend (JsonObject) -> Unit
        get() = journal.handler
        set(value) {
            journal.handler = value
        }
    var owner: CodexExecutionOwner? = null
    var captureOwner: suspend () -> CodexExecutionOwner? = { owner }
    override suspend fun processOwner(): CodexExecutionOwner? = captureOwner()
    var isClosed = false
    override val messages = incoming.receiveAsFlow()
    fun fork(off: CodexNativeOff = CodexNativeOff()): FakeWire = FakeWire(journal, off).also { journal.peers += it }
    fun origin(request: JsonObject): FakeWire = journal.origins.lastOrNull { it.first === request }?.second ?: this
    override suspend fun write(message: JsonObject) {
        written += message
        journal.origins += message to this
        handler(message)
    }
    suspend fun reply(request: JsonObject, result: JsonObject) {
        val source = origin(request)
        if (request.text("method") in setOf("thread/start", "thread/resume")) {
            (result["thread"] as? JsonObject)?.text("id")?.let { journal.threads[it] = source }
        }
        source.incoming.send(json("id" to checkNotNull(request["id"]), "result" to result))
    }
    suspend fun error(request: JsonObject) {
        origin(request).incoming.send(
            json("id" to checkNotNull(request["id"]), "error" to json("code" to JsonPrimitive(INVALID_PARAMS))),
        )
    }
    suspend fun event(method: String, params: JsonObject, id: JsonElement? = null) {
        val thread = params.text("threadId")
        val target = if (this === journal.root && thread != null) {
            journal.threads[thread] ?: journal.peers.lastOrNull() ?: this
        } else {
            this
        }
        target.incoming.send(
            JsonObject(
                mapOf("method" to method.json(), "params" to params) +
                    if (id == null) emptyMap() else mapOf("id" to id),
            ),
        )
    }
    override fun close() {
        isClosed = true
        incoming.close()
    }

    private class WireJournal {
        var root: FakeWire? = null
        val peers = mutableListOf<FakeWire>()
        val written = mutableListOf<JsonObject>()
        val origins = mutableListOf<Pair<JsonObject, FakeWire>>()
        val threads = mutableMapOf<String, FakeWire>()
        var handler: suspend (JsonObject) -> Unit = {}
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
    configuration: CodexLocalConfiguration = CodexLocalConfiguration(homeDirectory = "/test/codex"),
    tools: io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools =
        AllowedSearchTools,
    manifests: CodexToolManifests = MemoryCodexToolManifests(),
    turns: CodexTurnRecords = MemoryCodexTurnRecords(),
    launch: PreparedCodexLaunch? = null,
    hostedDrains: CodexHostedDrains = CodexHostedDrains(),
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
    var codexHome: String? = null
    var account = json("type" to "chatgpt".json(), "email" to "local@example.invalid".json())
    var threadTurns: List<JsonObject> = emptyList()

    /** Native turns of a resumed thread; null omits them from the response. */
    var resumedTurns: JsonArray? = JsonArray(emptyList())
    var resumedHistoryMode: String? = null
    var modelList: List<JsonObject> = emptyList()
    var resources: ResourceResolver = ResourceResolver { null }
    var nativeConfig = json("features" to JsonObject(CodexDisabledCapabilities.associateWith { JsonPrimitive(false) }))
    var onTurn: suspend (JsonObject) -> Unit = { message ->
        wire.reply(
            message,
            json("turn" to json("id" to "native-turn".json())),
        )
    }
    var beforeSearchLookup: suspend () -> Unit = {}
    val launcher = FakeLauncher()
    var isSearchEnabled = true
    var isQuestionnaireEnabled = true
    var isUsageEnabled = true
    val workspacePaths = mutableMapOf<WorkspaceRef, String>()
    val environment = CodexRuntimeEnvironment(
        configuration,
        object : FeatureToggles {
            override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flow { emit(get(toggle)) }

            @Suppress("UNCHECKED_CAST")
            override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = when (toggle) {
                is FeatureToggle.Flag -> when (toggle) {
                    SearchEngineTools -> {
                        beforeSearchLookup()
                        isSearchEnabled
                    }

                    QuestionnaireEnabled -> isQuestionnaireEnabled

                    EngineUsageEnabled -> isUsageEnabled

                    else -> true
                }

                is FeatureToggle.Choice -> toggle.default
            } as T
        },
        dispatchers,
        launcher,
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
        tools = tools,
        manifests = manifests,
        turns = turns,
        hostedDrains = hostedDrains,
        resources = ResourceResolver { resources.resolve(it) },
    )
    val runtime = CodexRuntime(
        RuntimeIdentity(CodexEngine.Id, AuthSourceId("codex.local"), AuthRevision.Unknown),
        rpc,
        environment,
        searchTools,
        launch ?: object : PreparedCodexLaunch {
            override suspend fun open(): CodexWire = wire.fork()
            override suspend fun open(off: CodexNativeOff): CodexWire = wire.fork(off)
            override suspend fun version(): String = "0.160.0"
        },
    )
    init {
        wire.handler = { message ->
            when (message.text("method")) {
                "account/read" -> wire.reply(message, json("account" to account))

                "model/list" -> wire.reply(message, json("data" to JsonArray(modelList)))

                "config/read" -> {
                    val off = wire.origin(message).off
                    wire.reply(
                        message,
                        json(
                            "config" to off.applyTo(nativeConfig),
                            "layers" to JsonArray(
                                listOf(
                                    json(
                                        "name" to json("type" to "sessionFlags".json()),
                                        "config" to off.applyTo(JsonObject(emptyMap())),
                                    ),
                                ),
                            ),
                        ),
                    )
                }

                "thread/start" -> wire.reply(
                    message,
                    threadResponse(json("id" to "thread".json(), "turns" to JsonArray(emptyList()))),
                )

                "thread/resume" -> wire.reply(
                    message,
                    threadResponse(
                        JsonObject(
                            listOfNotNull(
                                "id" to "thread".json(),
                                resumedTurns?.let { "turns" to it },
                                resumedHistoryMode?.let { "historyMode" to it.json() },
                            ).toMap(),
                        ),
                    ),
                )

                "thread/read" -> wire.reply(
                    message,
                    json("thread" to json("id" to "thread".json(), "turns" to JsonArray(threadTurns))),
                )

                "turn/start" -> onTurn(message)

                "turn/interrupt", "thread/name/set" -> wire.reply(message, JsonObject(emptyMap()))

                "initialize" -> wire.reply(
                    message,
                    JsonObject(codexHome?.let { mapOf("codexHome" to it.json()) }.orEmpty()),
                )
            }
        }
    }
    suspend fun open(workspace: WorkspaceRef? = null): ActiveSession = runtime.create(
        CreateSessionRequest(target, workspace),
    )
    suspend fun event(method: String, vararg fields: Pair<String, JsonElement>, id: JsonElement? = null) =
        wire.event(method, json("threadId" to "thread".json(), *fields), id)

    private fun threadResponse(thread: JsonObject): JsonObject = json(
        "thread" to thread,
        "approvalPolicy" to "never".json(),
        "sandbox" to json("type" to "readOnly".json(), "networkAccess" to JsonPrimitive(false)),
    )
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
    var beforeSend: suspend (MachineIntent) -> Unit = {}
    override fun <S : MachineState, I : MachineIntent, E : MachineEffect, O : MachineOutput> launch(
        spec: MachineSpec<S, I, E, O>,
        scope: ScopeHandle,
        effects: EffectHandler<E, I>,
    ): Machine<S, I, O> = object : Machine<S, I, O> {
        override val name = spec.name
        override val state = MutableStateFlow(spec.initial)
        override val outputs = MutableSharedFlow<O>(extraBufferCapacity = 16)
        override suspend fun send(intent: I): SendResult {
            beforeSend(intent)
            val resolution = spec.resolve(state.value, intent) ?: return SendResult.Ignored
            state.value = resolution.to
            resolution.outputs.forEach { outputs.emit(it) }
            resolution.effects.forEach { effect ->
                effects.handle(
                    effect,
                    object : EffectScope<I> {
                        override suspend fun send(input: I): SendResult = SendResult.Ignored
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

/** Explicit search authorization for transport fixtures; production NoAgentTools continues to deny. */
internal object AllowedSearchTools : io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools by
io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools {
    override suspend fun authorizeHosted(
        context: io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext,
        name: String,
        arguments: JsonObject,
    ): io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict =
        io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict.Allow
}
