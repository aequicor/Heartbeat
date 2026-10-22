package io.aequicor.heartbeat.feature.aiengine.connections.impl

import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthLocationId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthOwnerId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSecretId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceDraft
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceInfo
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelection
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.BindingCheck
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionMethodId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindings
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCommand
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineEnablement
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFamily
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineJob
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagement
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagementState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostAppSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostAppState
import io.aequicor.heartbeat.feature.aiengine.facade.api.JobPhase
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchState
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginState
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalogSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.Observation
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Instant

internal val OpenAi = ProviderInfo(ProviderId("openai"), "OpenAI", "https://platform.openai.com/api-keys")
internal val ApiKeyMethod =
    ConnectionMethod.ApiKey(ConnectionMethodId("openai.key"), OpenAi, EndpointOrigin("https://api.openai.com"))
internal val OllamaMethod = ConnectionMethod.NoAuth(
    ConnectionMethodId("ollama"),
    ProviderInfo(ProviderId("ollama"), "Ollama"),
    EndpointOrigin("http://localhost:11434"),
)
internal val KoogId = EngineId("koog")

internal fun engineInfo(
    bindings: List<EngineBinding> = emptyList(),
    availability: EngineAvailability = EngineAvailability.Available,
) = EngineInfo(
    EngineDescriptor(
        KoogId,
        "Koog",
        EngineFamily.MultiProvider,
        setOf(EnginePlatform.DesktopWindows),
        AiEngines,
        connectionMethods = listOf(ApiKeyMethod, OllamaMethod),
    ),
    availability,
    bindings,
)

internal fun modelInfo(binding: EngineBindingId, id: String) = ModelInfo(EngineTarget(KoogId, binding, ModelId(id)), id)

/** In-memory facade: bindings live in [bindingsState]; failures are injected per operation. */
internal class FakeEngineFacade : EngineFacade {
    override val providerUsage = object : ProviderUsageCatalog {
        private val snapshot = MutableStateFlow(ProviderUsageSnapshot())
        override fun observe(engine: EngineId, binding: EngineBindingId) = snapshot
        override suspend fun refresh(engine: EngineId, binding: EngineBindingId) = snapshot.value
    }

    val bindingsState = MutableStateFlow(emptyList<EngineBinding>())
    val catalog = MutableStateFlow(listOf(engineInfo()))
    private val cachedModels = mutableMapOf<EngineBindingId, MutableStateFlow<ModelCatalogSnapshot>>()
    var discovered: List<String> = listOf("gpt-a", "gpt-b")
    var availability: EngineAvailability = EngineAvailability.Available
    var connectFailure: EngineFailure? = null
    var discoverFailure: EngineFailure? = null
    var refreshFailure: EngineFailure? = null
    var connectGate: CompletableDeferred<Unit>? = null
    var discoverGate: CompletableDeferred<Unit>? = null
    var disconnectFailure: Exception? = null
    var disconnectCleanupFailure: Exception? = null
    val calls = mutableListOf<String>()

    override val engines: EngineCatalog = object : EngineCatalog {
        override val state: StateFlow<List<EngineInfo>> = catalog

        override suspend fun refresh(engine: EngineId): EngineInfo {
            calls += "refresh"
            refreshFailure?.let { throw EngineException(it) }
            return engineInfo(bindingsState.value, availability)
        }

        override fun features(engine: EngineId): EngineFeatures = error("unused")
    }

    override val bindings: EngineBindings = object : EngineBindings {
        override val state: StateFlow<List<EngineBinding>> = bindingsState

        override suspend fun connect(engine: EngineId, source: AuthSourceId, priority: Int): EngineBinding {
            calls += "connect"
            connectGate?.await()
            connectFailure?.let { throw EngineException(it) }
            val binding = EngineBinding(EngineBindingId("binding-${bindingsState.value.size + 1}"), engine, source)
            bindingsState.update { it + binding }
            return binding
        }

        override suspend fun setEnabled(binding: EngineBindingId, enabled: Boolean) {
            bindingsState.update { all -> all.map { if (it.id == binding) it.copy(isEnabled = enabled) else it } }
        }

        override suspend fun disconnect(binding: EngineBindingId) {
            calls += "disconnect"
            disconnectFailure?.let { throw it }
            bindingsState.update { all -> all.filterNot { it.id == binding } }
            disconnectCleanupFailure?.let { throw it }
        }

        override suspend fun check(target: EngineTarget, workspace: WorkspaceRef?): BindingCheck = error("unused")
    }

    override val models: ModelCatalog = object : ModelCatalog {
        override fun observe(engine: EngineId, binding: EngineBindingId): StateFlow<ModelCatalogSnapshot> =
            snapshotOf(binding)

        override suspend fun refresh(engine: EngineId, binding: EngineBindingId): ModelCatalogSnapshot {
            calls += "discover"
            discoverGate?.await()
            discoverFailure?.let { throw EngineException(it) }
            val snapshot = ModelCatalogSnapshot(discovered.map { modelInfo(binding, it) }, Observation(isStale = false))
            snapshotOf(binding).value = snapshot
            return snapshot
        }
    }

    override val sessions: SessionCatalog get() = error("unused")

    private fun snapshotOf(binding: EngineBindingId) =
        cachedModels.getOrPut(binding) { MutableStateFlow(ModelCatalogSnapshot(emptyList(), Observation())) }
}

/** In-memory registry; records whether managed keys were still open when copied. */
internal class FakeAuthSources : AuthSources {
    override val state = MutableStateFlow(emptyList<AuthSource>())
    val forgotten = mutableListOf<AuthSourceId>()

    override suspend fun get(id: AuthSourceId): AuthSource? = state.value.firstOrNull { it.info.id == id }

    override suspend fun addManagedKey(label: String, scope: AuthScope, key: Secret): AuthSource.ManagedKey {
        key.reveal { check(it.isNotEmpty()) }
        val info = nextInfo(label)
        return AuthSource.ManagedKey(info, scope, AuthSecretId("secret-${info.id.value}")).also(::add)
    }

    override suspend fun replaceManagedKey(id: AuthSourceId, key: Secret): AuthSource.ManagedKey = error("unused")

    override suspend fun register(draft: AuthSourceDraft): AuthSource {
        val info = nextInfo(draft.label)
        val source = when (draft) {
            is AuthSourceDraft.CliLogin -> AuthSource.CliLogin(info, draft.scope, draft.owner, draft.location)
            is AuthSourceDraft.NoAuth -> AuthSource.NoAuth(info, draft.scope)
            else -> error("unused draft")
        }
        return source.also(::add)
    }

    override suspend fun updateRevision(id: AuthSourceId, revision: AuthRevision): AuthSource = error("unused")

    private fun nextInfo(label: String) =
        AuthSourceInfo(AuthSourceId("source-${state.value.size + 1}"), label, AuthRevision.Unknown)

    private fun add(source: AuthSource) = state.update { it + source }

    var forgetFailure: Exception? = null

    override suspend fun forget(id: AuthSourceId) {
        forgetFailure?.let { throw it }
        forgotten += id
        state.update { all -> all.filterNot { it.info.id == id } }
    }
}

internal class FakeModelSelections : ModelSelections {
    val state = MutableStateFlow(ModelSelection())
    var updateFailure: Exception? = null

    override fun observe(): Flow<ModelSelection> = state

    override suspend fun update(change: (ModelSelection) -> ModelSelection): ModelSelection {
        updateFailure?.let { throw it }
        state.update(change)
        return state.value
    }
}

/** Collects intents sent by an effect. */
internal class RecordingScope<I : MachineIntent> : EffectScope<I> {
    val intents = mutableListOf<I>()
    var result: SendResult = SendResult.Accepted

    override suspend fun send(intent: I): SendResult {
        intents += intent
        return result
    }
}

/**
 * Publishes the state a test sets and records commands; a configuration replaces the engine's launch settings, an
 * inspection shows [inspectedHostApp].
 */
internal class FakeEngineManagement : EngineManagement {
    override val state = MutableStateFlow(EngineManagementState.Off)
    val commands = mutableListOf<EngineCommand>()
    var inspectedHostApp: HostAppState? = null
    var configureFailure: EngineException? = null

    /** The phase a started sign-in reaches; AwaitingCode stays until cancelled. */
    var loginPhase: JobPhase = JobPhase.Succeeded
    var startFailure: EngineException? = null
    private var logins = 0L

    /** Publishes inspections later in this scope, like the real service's state flow; null publishes at once. */
    var publisher: CoroutineScope? = null
    private var inspections = 0L

    override suspend fun execute(engine: EngineId, command: EngineCommand) {
        commands += command
        when (command) {
            EngineCommand.Inspect -> {
                val checkedAt = Instant.fromEpochSeconds(++inspections)
                val publish = {
                    change(engine) {
                        it.copy(
                            hostApp = inspectedHostApp,
                            installation = it.installation.copy(checkedAt = checkedAt),
                        )
                    }
                }
                publisher?.launch {
                    delay(PUBLISH_DELAY_MILLIS)
                    publish()
                } ?: publish()
            }

            is EngineCommand.Configure -> {
                configureFailure?.let { throw it }
                change(engine) { it.copy(launch = LaunchState(command.settings)) }
            }

            is EngineCommand.Start -> {
                startFailure?.let { throw it }
                check(command.action is EngineAction.Login) { "unexpected action ${command.action}" }
                val job = EngineJob(command.action, loginPhase, Instant.fromEpochSeconds(LOGIN_EPOCH + ++logins))
                change(engine) { it.copy(job = job) }
            }

            EngineCommand.Dismiss -> change(engine) { it.copy(job = null) }

            EngineCommand.Cancel -> change(engine) { it.copy(job = it.job?.copy(phase = JobPhase.Cancelled)) }

            else -> error("unexpected command $command")
        }
    }

    private fun change(engine: EngineId, block: (ManagedEngine) -> ManagedEngine) = state.update { current ->
        current.copy(engines = current.engines.map { if (it.descriptor.id == engine) block(it) else it })
    }
}

internal val ClaudeId = EngineId("claude")
internal val ClaudeApp = HostAppSpec("Claude")
internal val DesktopMethod = ConnectionMethod.CliLogin(
    ConnectionMethodId("desktop"),
    ProviderInfo(ProviderId("anthropic"), "Anthropic"),
    EndpointOrigin("https://api.anthropic.com"),
    AuthOwnerId("claude.code"),
    AuthLocationId("claude.local"),
    ClaudeApp,
)

/** Management that knows the Claude engine with [settings] and a host application. */
internal fun claudeManagement(
    settings: LaunchSettings = LaunchSettings(),
    login: LoginState = LoginState.NotApplicable,
) = EngineManagementState(
    isEnabled = true,
    platform = EnginePlatform.DesktopMacOs,
    engines = listOf(
        ManagedEngine(
            EngineDescriptor(
                ClaudeId,
                "Claude Code",
                EngineFamily.Vendor,
                setOf(EnginePlatform.DesktopMacOs),
                AiEngines,
                connectionMethods = listOf(DesktopMethod),
            ),
            ManagementSpec(
                login = if (login == LoginState.NotApplicable) LoginSupport.None else LoginSupport.Cli,
                hostApp = ClaudeApp,
            ),
            EngineEnablement(),
            EngineAvailability.Available,
            login = login,
            launch = LaunchState(settings),
        ),
    ),
)

private const val PUBLISH_DELAY_MILLIS = 50L
private const val LOGIN_EPOCH = 1_000L
