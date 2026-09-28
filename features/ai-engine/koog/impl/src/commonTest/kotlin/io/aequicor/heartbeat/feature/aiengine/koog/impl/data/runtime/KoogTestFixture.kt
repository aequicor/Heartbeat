package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.secrets.SecretKey
import io.aequicor.heartbeat.core.secrets.SecretRemoval
import io.aequicor.heartbeat.core.secrets.SecretStore
import io.aequicor.heartbeat.core.secrets.SecretUsage
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceInfo
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogConnection
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogConnections
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.KoogRecord
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.KoogSessionRecords
import io.aequicor.heartbeat.feature.searchengine.api.ResourceContent
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngineTools
import io.aequicor.heartbeat.feature.searchengine.api.SearchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope

internal class KoogTestFixture(test: TestScope) {
    val source = AuthSource.NoAuth(
        AuthSourceInfo(AuthSourceId("local"), "Local", AuthRevision.Known("1")),
        AuthScope(KoogProvider.Ollama.id, KoogProvider.Ollama.origin),
    )
    val binding = EngineBinding(EngineBindingId("local"), KoogEngineId, source.info.id)
    val target = EngineTarget(KoogEngineId, binding.id, ModelId("test-model"))
    val connections = FakeConnections(mutableListOf(KoogConnection(binding, source)))
    val records = FakeRecords()
    val executor = FakeExecutor()
    val profile = FakeProfile(
        CoroutineScope(test.backgroundScope.coroutineContext + Job(test.backgroundScope.coroutineContext.job)),
    )
    var isEnabled = true
    var isSearchEnabled = true
    var modelSupportsTools = true
    var opens = 0
    var beforeModels: suspend () -> Unit = {}
    val secrets = FakeSecrets()
    private val toggles = object : FeatureToggles {
        @Suppress("UNCHECKED_CAST") // Fixture only supplies boolean switches.
        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T =
            (if (toggle == SearchEngineTools) isSearchEnabled else isEnabled) as T
        override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flow { emit(get(toggle)) }
    }
    val reasoningStore = MemoryReasoningStore()
    var catalogLevels: Map<String, List<String>> = emptyMap()
    val reasoning = KoogReasoningLevels(
        reasoningStore,
        object : KoogReasoningCatalog {
            override suspend fun levels(provider: KoogProvider, model: String) = catalogLevels[model]
        },
        toggles,
    )
    val access = KoogAccess(
        connections,
        secrets,
        toggles,
        profile,
        object : KoogTransport {
            override fun open(
                provider: KoogProvider,
                key: String?,
                model: String?,
                origin: EndpointOrigin,
                basePath: String?,
            ): KoogClient {
                opens++
                return KoogClient(executor) {
                    beforeModels()
                    val capabilities = if (modelSupportsTools) listOf(LLMCapability.Tools) else emptyList()
                    listOf(LLModel(provider.llmProvider, "test-model", capabilities))
                }
            }
        },
        reasoning,
    )
    var searchResults = emptyList<SearchResult>()
    var fetchedResource: ResourceContent? = null
    val search = object : SearchEngine {
        override suspend fun search(query: String, count: Int, native: EngineFeatures?): List<SearchResult> =
            searchResults
        override suspend fun fetch(url: String, native: EngineFeatures?): ResourceContent =
            fetchedResource ?: error("unavailable")
    }
    val adapter = DefaultKoogEngineAdapter(access, records, KoogSessionCache(profile), search, profile)
    val identity = RuntimeIdentity(KoogEngineId, source.info.id, source.info.revision)

    suspend fun runtime(): KoogRuntime = adapter.createRuntime(identity) as KoogRuntime
    suspend fun session(): ActiveSession = runtime().create(CreateSessionRequest(target))
    fun request(id: String = "request") = PromptRequest(RequestId(id), listOf(ContentPart.Text("hello")))
}

internal class MemoryReasoningStore : KoogReasoningStore {
    var state = KoogReasoningState()
    override suspend fun read() = state
    override suspend fun write(state: KoogReasoningState) {
        this.state = state
    }
}

internal class FakeConnections(val values: MutableList<KoogConnection>) : KoogConnections {
    override suspend fun list(): List<KoogConnection> = values.toList()
    var puts = 0
    var rejection: IllegalArgumentException? = null
    override suspend fun put(connection: KoogConnection) {
        rejection?.let { throw it }
        puts++
        values.removeAll { it.binding.id == connection.binding.id }
        values += connection
    }
    override suspend fun remove(binding: EngineBindingId) {
        values.removeAll { it.binding.id == binding }
    }
}

internal class FakeRecords : KoogSessionRecords {
    val values = mutableMapOf<SessionRef, KoogRecord>()
    var beforeSave: suspend () -> Unit = {}
    var saves = 0
    override suspend fun list(): List<KoogRecord> = values.values.toList()
    override suspend fun get(ref: SessionRef): KoogRecord? = values[ref]
    override suspend fun save(record: KoogRecord) {
        saves++
        beforeSave()
        values[record.summary.ref] = record
    }
}

internal class FakeExecutor : PromptExecutor() {
    val frames = Channel<StreamFrame>(Channel.UNLIMITED)
    val prompts = mutableListOf<Prompt>()
    val tools = mutableListOf<List<ToolDescriptor>>()
    var closed = 0
    var failure: Exception? = null
    var nextFailure: Exception? = null
    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
        flow {
            prompts += prompt
            this@FakeExecutor.tools += tools
            failure?.let { throw it }
            nextFailure?.let {
                nextFailure = null
                throw it
            }
            do {
                val frame = frames.receive()
                emit(frame)
            } while (frame !is StreamFrame.End)
        }
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
        error("Streaming expected")
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("Not supported")
    override fun close() {
        closed++
    }
    fun complete(text: String = "answer") {
        frames.trySend(StreamFrame.TextDelta(text)).getOrThrow()
        frames.trySend(StreamFrame.TextComplete(text)).getOrThrow()
        frames.trySend(StreamFrame.End("stop")).getOrThrow()
    }
}

internal class FakeProfile(override val coroutineScope: CoroutineScope) : ScopeHandle {
    private val callbacks = mutableListOf<() -> Unit>()
    override val name = "test-profile"
    override val savedState: ScopeSavedState get() = error("Unused")
    override var isClosed = false
    override fun onClose(action: () -> Unit): DisposableHandle {
        callbacks += action
        return DisposableHandle { callbacks.remove(action) }
    }
    fun close() {
        isClosed = true
        callbacks.toList().asReversed().forEach { it() }
        coroutineScope.cancel()
    }
}

internal class FakeSecrets : SecretStore {
    var reads = 0
    var beforeRead: suspend () -> Unit = {}
    override suspend fun read(key: SecretKey): Secret {
        reads++
        beforeRead()
        return Secret("mock-key".toCharArray())
    }
    override suspend fun write(key: SecretKey, value: Secret): Unit = error("Unused")
    override suspend fun keys(): List<SecretKey> = emptyList()
    override suspend fun bind(usage: SecretUsage, key: SecretKey?): Unit = error("Unused")
    override suspend fun readFor(usage: SecretUsage): Secret? = null
    override suspend fun usages(key: SecretKey): List<SecretUsage> = emptyList()
    override suspend fun remove(key: SecretKey): SecretRemoval = error("Unused")
}

internal fun <F : EngineFeature> EngineFeatures.require(key: EngineFeatureKey<F>): F =
    (resolve(key) as FeatureAccess.Available).feature
