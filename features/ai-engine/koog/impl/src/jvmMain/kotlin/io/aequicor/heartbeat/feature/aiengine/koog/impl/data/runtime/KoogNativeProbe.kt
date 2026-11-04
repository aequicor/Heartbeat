package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.network.OfflineHttpClients
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.secrets.SecretKey
import io.aequicor.heartbeat.core.secrets.SecretRemoval
import io.aequicor.heartbeat.core.secrets.SecretStore
import io.aequicor.heartbeat.core.secrets.SecretUsage
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSecretId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceInfo
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogConnection
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogConnections
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineEnabled
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.KoogRecord
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.KoogSessionRecords
import io.aequicor.heartbeat.feature.searchengine.api.ResourceContent
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine
import io.aequicor.heartbeat.feature.searchengine.api.SearchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.TimeMark

/** Partial native-session evidence, separate from SDK codecs, actual loaded artifacts and full baseline admission. */
internal data class KoogNativeReceipt(
    val isCreateAndSendConfirmed: Boolean,
    val isHistoryAndResumeConfirmed: Boolean,
    val isCancellationConfirmed: Boolean,
    val isRecoveryConfirmed: Boolean,
)

/** Exercises production session code against synthetic in-memory ports without a user profile or network. */
internal class KoogNativeProbe(private val dispatchers: DispatcherProvider) {
    suspend fun run(deadline: TimeMark, provider: KoogProvider): KoogNativeReceipt = withContext(dispatchers.io) {
        val environment = ProbeEnvironment(dispatchers, provider)
        try {
            withTimeout(-deadline.elapsedNow()) { environment.exercise() }
        } finally {
            withContext(NonCancellable) { environment.close() }
        }
    }

    /** Runs the real native session and selected provider SDK over a finite socket-free exchange. */
    suspend fun runProviderWire(
        deadline: TimeMark,
        offline: OfflineHttpClients,
        platform: PlatformInfo,
        provider: KoogProvider,
    ): Boolean = runWire(deadline, offline, platform, provider, KoogSdkCheck.StreamStop)

    suspend fun runIncompleteWire(
        deadline: TimeMark,
        offline: OfflineHttpClients,
        platform: PlatformInfo,
        provider: KoogProvider,
    ): Boolean = runWire(deadline, offline, platform, provider, KoogSdkCheck.UnterminatedStream)

    suspend fun runMalformedWire(
        deadline: TimeMark,
        offline: OfflineHttpClients,
        platform: PlatformInfo,
        provider: KoogProvider,
    ): Boolean = runWire(deadline, offline, platform, provider, KoogSdkCheck.MalformedStream)

    private suspend fun runWire(
        deadline: TimeMark,
        offline: OfflineHttpClients,
        platform: PlatformInfo,
        provider: KoogProvider,
        check: KoogSdkCheck,
    ): Boolean = withContext(dispatchers.io) {
        val exchange = nativeWireExchange(provider, check)
        val owner = offline.open(listOf(exchange))
        try {
            val environment = ProbeEnvironment(dispatchers, provider, isWire = true)
            try {
                withTimeout(-deadline.elapsedNow()) {
                    val isSessionConfirmed = environment.exerciseWire(
                        SdkKoogTransport(owner.client, platform),
                        check != KoogSdkCheck.StreamStop,
                    )
                    isSessionConfirmed && owner.progress().isComplete
                }
            } finally {
                withContext(NonCancellable) { environment.close() }
            }
        } finally {
            withContext(NonCancellable) { owner.closeAndAwait() }
        }
    }
}

private class ProbeEnvironment(dispatchers: DispatcherProvider, provider: KoogProvider, isWire: Boolean = false) {
    private val job = SupervisorJob()
    private val profile = ProbeProfile(CoroutineScope(job + dispatchers.main.limitedParallelism(1)))
    private val source = probeSource(provider, if (isWire) provider.sdkProbeOrigin else provider.origin)
    private val binding = EngineBinding(EngineBindingId("probe-binding"), KoogEngineId, source.info.id)
    private val target = EngineTarget(KoogEngineId, binding.id, ModelId("probe-model"))
    private val identity = RuntimeIdentity(KoogEngineId, source.info.id, source.info.revision)
    private val records = ProbeRecords()
    private val transport = ProbeTransport(provider)
    private val access = KoogAccess(
        ProbeConnections(KoogConnection(binding, source)),
        ProbeSecrets,
        ProbeToggles,
        profile,
        transport,
        KoogReasoningLevels(ProbeReasoning(), ProbeCatalog, ProbeToggles),
        KoogContextWindows(),
    )
    private var runtime = newRuntime()

    private fun newRuntime() = KoogRuntime(
        identity,
        access,
        records,
        profile.coroutineScope,
        KoogSessionCache(profile),
        ProbeSearch,
        KoogWorkspaces { null },
    )

    suspend fun exercise(): KoogNativeReceipt {
        val session = runtime.create(CreateSessionRequest(target))
        val first = session.features.require(SendsPrompts).send(request("first"))
        val ready = session.state.first { it is ActiveSessionState.Ready && it.lastTurn?.id == first }
        val isSent = (ready as ActiveSessionState.Ready).lastTurn?.outcome == TurnOutcome.Completed
        val firstPage = session.features.require(SessionHistory).page()
        val messages = firstPage.items.filterIsInstance<SessionItem.Message>()
        val isTranscript = messages.map { it.role to it.parts } == listOf(
            MessageRole.User to listOf(ContentPart.Text("synthetic")),
            MessageRole.Assistant to listOf(ContentPart.Text("synthetic answer")),
        )
        val ref = session.ref
        session.close()
        runtime.close()
        runtime = newRuntime()
        val isOtherModelRejected = try {
            runtime.attach(ref, ResumeSessionRequest(target.copy(model = ModelId("other-model")))).close()
            false
        } catch (error: EngineException) {
            if (error.failure != EngineFailure.Session(SessionFailureReason.NotResumable)) throw error
            true
        }
        val resumed = runtime.attach(ref, ResumeSessionRequest(target))
        val resumedPage = resumed.features.require(SessionHistory).page()
        val resumedTurn = (resumed.state.value as? ActiveSessionState.Ready)?.lastTurn
        val isSameHistory = resumedPage.items == firstPage.items && records.values.containsKey(ref)
        val isSameTurn = resumedTurn?.id == first && resumedTurn.outcome == TurnOutcome.Completed
        val isResumed = isOtherModelRejected && isSameHistory && isSameTurn
        transport.isWaiting = true
        val cancelled = resumed.features.require(SendsPrompts).send(request("cancel"))
        transport.started.await()
        resumed.features.require(CancelsTurns).cancel(cancelled)
        val state = resumed.state.first { it is ActiveSessionState.Ready && it.lastTurn?.id == cancelled }
        val isCancelled = (state as ActiveSessionState.Ready).lastTurn?.outcome == TurnOutcome.Unknown &&
            transport.openExecutors.get() == 0
        resumed.close()
        runtime.close()
        val interrupted = requireNotNull(records.values[ref])
        records.values[ref] = interrupted.copy(lastTurn = requireNotNull(interrupted.lastTurn).copy(outcome = null))
        runtime = newRuntime()
        val recovered = runtime.attach(ref, ResumeSessionRequest(target))
        val recoveredPage = recovered.features.require(SessionHistory).page()
        val recoveredTurn = (recovered.state.value as? ActiveSessionState.Ready)?.lastTurn
        val isRecovered = recoveredTurn?.outcome == TurnOutcome.Unknown &&
            recoveredPage.coverage == HistoryCoverage.Partial &&
            recoveredPage.items == interrupted.items &&
            records.values[ref]?.lastTurn?.outcome == TurnOutcome.Unknown
        recovered.close()
        return KoogNativeReceipt(isSent && isTranscript, isResumed, isCancelled, isRecovered)
    }

    suspend fun exerciseWire(wire: KoogTransport, isFailureExpected: Boolean): Boolean {
        val wireAccess = KoogAccess(
            ProbeConnections(KoogConnection(binding, source)),
            ProbeSecrets,
            ProbeToggles,
            profile,
            wire,
            KoogReasoningLevels(ProbeReasoning(), ProbeCatalog, ProbeToggles),
            KoogContextWindows(),
        )
        val wireRuntime = KoogRuntime(
            identity,
            wireAccess,
            records,
            profile.coroutineScope,
            KoogSessionCache(profile),
            ProbeSearch,
            KoogWorkspaces { null },
        )
        try {
            val wireTarget = target.copy(model = ModelId(SDK_PROBE_MODEL))
            val session = wireRuntime.create(CreateSessionRequest(wireTarget))
            val request = PromptRequest(RequestId("wire"), listOf(ContentPart.Text(SDK_PROBE_TEXT)))
            val turn = session.features.require(SendsPrompts).send(request)
            val state = session.state.first { it is ActiveSessionState.Ready && it.lastTurn?.id == turn }
            if (isFailureExpected) return (state as ActiveSessionState.Ready).lastTurn?.outcome is TurnOutcome.Failed
            val messages = session.features.require(SessionHistory).page().items.filterIsInstance<SessionItem.Message>()
            return (state as ActiveSessionState.Ready).lastTurn?.outcome == TurnOutcome.Completed &&
                messages.map { it.role to it.parts } == listOf(
                    MessageRole.User to listOf(ContentPart.Text(SDK_PROBE_TEXT)),
                    MessageRole.Assistant to listOf(ContentPart.Text(SDK_PROBE_TEXT)),
                )
        } finally {
            withContext(NonCancellable) { wireRuntime.close() }
        }
    }

    suspend fun close() {
        try {
            runtime.close()
        } finally {
            try {
                profile.close()
            } finally {
                withContext(NonCancellable) { job.cancelAndJoin() }
            }
        }
    }

    private fun request(id: String) = PromptRequest(RequestId(id), listOf(ContentPart.Text("synthetic")))
}

private class ProbeProfile(override val coroutineScope: CoroutineScope) : ScopeHandle {
    private val lock = Any()
    private val callbacks = mutableListOf<() -> Unit>()
    override val name = "koog-offline-probe"
    override val savedState: ScopeSavedState get() = error("Probe must not persist scope state")

    @Volatile
    override var isClosed = false
        private set
    override fun onClose(action: () -> Unit): DisposableHandle {
        val isRegistered = synchronized(lock) {
            if (isClosed) {
                false
            } else {
                callbacks += action
                true
            }
        }
        if (!isRegistered) action()
        return DisposableHandle { synchronized(lock) { callbacks.remove(action) } }
    }
    fun close() {
        val pending = synchronized(lock) {
            if (isClosed) {
                emptyList()
            } else {
                isClosed = true
                callbacks.toList().asReversed().also { callbacks.clear() }
            }
        }
        pending.forEach { it() }
    }
}

private class ProbeConnections(private val connection: KoogConnection) : KoogConnections {
    override suspend fun list() = listOf(connection)
    override suspend fun put(connection: KoogConnection): Unit = error("Probe must not modify profile routes")
    override suspend fun remove(binding: EngineBindingId): Unit = error("Probe must not modify profile routes")
}

private class ProbeRecords : KoogSessionRecords {
    val values = mutableMapOf<SessionRef, KoogRecord>()
    override suspend fun list() = values.values.toList()
    override suspend fun get(ref: SessionRef) = values[ref]
    override suspend fun save(record: KoogRecord) {
        values[record.summary.ref] = record
    }
}

private object ProbeSecrets : SecretStore {
    override suspend fun read(key: SecretKey): Secret {
        check(key.value == "probe-secret") { "Probe must not read profile credentials" }
        return Secret("synthetic-key".toCharArray())
    }
    override suspend fun write(key: SecretKey, value: Secret): Unit = error("Probe must not write credentials")
    override suspend fun keys(): List<SecretKey> = error("Probe must not inspect credentials")
    override suspend fun bind(usage: SecretUsage, key: SecretKey?): Unit = error("Probe must not bind credentials")
    override suspend fun readFor(usage: SecretUsage): Secret? = error("Probe must not read credentials")
    override suspend fun usages(key: SecretKey): List<SecretUsage> = error("Probe must not inspect credentials")
    override suspend fun remove(key: SecretKey): SecretRemoval = error("Probe must not remove credentials")
}

private object ProbeToggles : FeatureToggles {
    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = when (toggle) {
        is FeatureToggle.Flag -> (toggle == KoogEngineEnabled) as T
        is FeatureToggle.Choice -> toggle.default
    }
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flow { emit(get(toggle)) }
}

private class ProbeReasoning : KoogReasoningStore {
    private var value = KoogReasoningState()
    override suspend fun read() = value
    override suspend fun write(state: KoogReasoningState) {
        value = state
    }
}

private object ProbeCatalog : KoogReasoningCatalog {
    override suspend fun levels(provider: KoogProvider, origin: EndpointOrigin, model: String): List<String>? = null
}

private object ProbeSearch : SearchEngine {
    override suspend fun search(query: String, count: Int, native: EngineFeatures?): List<SearchResult> =
        error("Probe must not access search")
    override suspend fun fetch(url: String, native: EngineFeatures?): ResourceContent =
        error("Probe must not access network")
}

private class ProbeTransport(private val expected: KoogProvider) : KoogTransport {
    @Volatile
    var isWaiting = false
    val started = CompletableDeferred<Unit>()
    val openExecutors = AtomicInteger()
    override suspend fun open(
        provider: KoogProvider,
        key: String?,
        model: String?,
        origin: EndpointOrigin,
        basePath: String?,
    ): KoogClient {
        val expectedKey = if (expected == KoogProvider.Ollama) null else "synthetic-key"
        check(provider == expected && key == expectedKey && origin == expected.origin && basePath == null)
        val isWaiting = this.isWaiting
        openExecutors.incrementAndGet()
        val executor = ProbeExecutor(isWaiting, started, openExecutors)
        return KoogClient(executor) { listOf(provider.textModel("probe-model")) }
    }
}

private class ProbeExecutor(
    private val isWaiting: Boolean,
    private val started: CompletableDeferred<Unit>,
    private val openExecutors: AtomicInteger,
) : PromptExecutor() {
    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
        flow {
            check(tools.isEmpty())
            if (isWaiting) {
                started.complete(Unit)
                awaitCancellation()
            }
            emit(StreamFrame.TextDelta("synthetic answer"))
            emit(StreamFrame.TextComplete("synthetic answer"))
            emit(StreamFrame.End("stop"))
        }
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
        error("Probe expects streaming")
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("Probe forbids moderation")
    override fun close() {
        check(openExecutors.decrementAndGet() >= 0) { "Probe executor closed more than once" }
    }
}

private fun <F : EngineFeature> EngineFeatures.require(key: EngineFeatureKey<F>): F =
    (resolve(key) as? FeatureAccess.Available)?.feature ?: error("Missing native probe capability")

/** Fixed synthetic route metadata; no user-controlled endpoint or credential enters the probe. */
private fun probeSource(provider: KoogProvider, origin: EndpointOrigin): AuthSource {
    val info = AuthSourceInfo(AuthSourceId("probe-source"), "", AuthRevision.Known("synthetic"))
    val scope = AuthScope(provider.id, origin)
    return if (provider == KoogProvider.Ollama) {
        AuthSource.NoAuth(info, scope)
    } else {
        AuthSource.ManagedKey(info, scope, AuthSecretId("probe-secret"))
    }
}
