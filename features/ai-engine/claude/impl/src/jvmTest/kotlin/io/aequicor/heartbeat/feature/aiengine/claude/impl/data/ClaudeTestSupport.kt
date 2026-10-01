package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridge
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.aiengine.facade.api.UnavailableAgentToolBridge
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Instant

internal val testTarget = EngineTarget(ClaudeEngine.Id, EngineBindingId("binding"), ModelId("sonnet"))
internal fun prompt(id: String = "request") = PromptRequest(RequestId(id), listOf(ContentPart.Text("Hello")))
internal fun <F : EngineFeature> EngineFeatures.available(key: EngineFeatureKey<F>): F =
    assertIs<FeatureAccess.Available<F>>(resolve(key)).feature

internal class FakeClaudeTransport : ClaudeTransport {
    var account = "owner@example.test"
    var isLoggedIn = true
    var method = "claude.ai"
    val calls = mutableListOf<List<String>>()
    val hostedCalls = mutableListOf<ClaudeHostedTools?>()
    val inputs = mutableListOf<String>()
    var beforeRun: suspend (List<String>) -> Unit = {}
    var generation: suspend (List<String>, suspend (String) -> Boolean) -> Int = { args, line ->
        val id = args.first { it.startsWith("--session-id=") || it.startsWith("--resume=") }.substringAfter('=')
        line(initFrame(id))
        line(assistantFrame(id))
        line(resultFrame(id))
        0
    }
    override suspend fun run(
        arguments: List<String>,
        input: String,
        workspace: WorkspaceRef?,
        closeInput: Boolean,
        hosted: ClaudeHostedTools?,
        line: suspend (String) -> Boolean,
    ): Int {
        calls += arguments
        hostedCalls += hosted
        inputs += input
        beforeRun(arguments)
        return if (arguments == listOf("auth", "status")) {
            line("""{"loggedIn":$isLoggedIn,"authMethod":"$method","email":"$account","orgId":"organization"}""")
            if (isLoggedIn) 0 else 1
        } else {
            generation(arguments, line)
        }
    }
}

internal class TestClaudeToggles : FeatureToggles {
    var isEnabled = true
    var isUsageEnabled = true

    // Tests read only ClaudeEngine.Enabled, a Boolean flag, so T is always Boolean here.
    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = (
        if (toggle ==
            EngineUsageEnabled
        ) {
            isUsageEnabled
        } else {
            isEnabled
        }
    ) as T

    // Same as get: the only observed toggle is the Boolean ClaudeEngine.Enabled flag.
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> observe(toggle: FeatureToggle<T>) = flowOf(isEnabled as T)
}

internal class TestProfileHandle(override val coroutineScope: CoroutineScope) : ScopeHandle {
    override val name = "profile"
    override val savedState: ScopeSavedState get() = error("Profile scope state is not persisted")
    override val isClosed get() = !coroutineScope.isActive
    override fun onClose(action: () -> Unit): DisposableHandle = DisposableHandle { }
}

internal class ClaudeFixture(val scope: CoroutineScope) {
    var resources: ResourceResolver = ResourceResolver { null }
    var inputSupport = PromptInputSupport.TextDocuments
    val transport = FakeClaudeTransport()
    val toggles = TestClaudeToggles()
    val account = ClaudeAccount(
        transport,
        object : Clock {
            override fun now(): Instant = Instant.fromEpochSeconds(1)
        },
    )
    val catalog = MemoryClaudeCatalog()
    suspend fun runtime(
        tools: ProfileAgentTools = NoAgentTools,
        bridge: AgentToolBridge = UnavailableAgentToolBridge,
    ): ClaudeRuntime {
        val revision = account.inspect().check.revision
        return ClaudeRuntime(
            RuntimeIdentity(ClaudeEngine.Id, ClaudeEngine.AuthSource, revision),
            transport,
            account,
            toggles,
            scope,
            catalog = catalog,
            tools = tools,
            bridge = bridge,
            resources = ResourceResolver { resources.resolve(it) },
            inputSupport = { inputSupport },
        )
    }
}

/** Serialize each write to model reopening storage, without retaining live collections by reference. */
internal class MemoryClaudeCatalog : ClaudeCatalog {
    private val records = mutableMapOf<String, String>()
    override suspend fun find(ref: io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef): ClaudeRecord? =
        records[ref.nativeId]?.let { Json.decodeFromString<ClaudeRecord>(it) }?.takeIf { it.ref == ref }
    override suspend fun save(record: ClaudeRecord) {
        records[record.ref.nativeId] = Json.encodeToString(ClaudeRecord.serializer(), record)
    }
}

internal class TestLocalWorkspaces : LocalWorkspaces {
    var directory: String? = null
    override val isAvailable = true
    override fun observe() = flowOf(emptyList<LocalWorkspace>())
    override suspend fun register(directory: String): LocalWorkspace = error("Not used")
    override suspend fun resolve(ref: WorkspaceRef) = directory
}

internal fun initFrame(id: String) = """{"type":"system","subtype":"init","session_id":"$id","model":"claude-actual"}"""
internal fun assistantFrame(id: String) = """{"type":"assistant","session_id":"$id",
        "message":{"model":"claude-actual","content":[{"type":"text","text":"Answer"}]}}"""
internal fun errorResultFrame(id: String) = """{"type":"result","session_id":"$id",
        "subtype":"error_during_execution","is_error":true}"""
internal fun resultFrame(id: String) = """{"type":"result","session_id":"$id",
        "subtype":"success","is_error":false,"result":"Answer"}"""
