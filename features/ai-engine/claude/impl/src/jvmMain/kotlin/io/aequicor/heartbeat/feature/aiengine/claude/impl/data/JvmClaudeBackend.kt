package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthVerdict
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeAuthentication
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeEngine
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeLogin
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.runtime.ClaudeCandidates
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.runtime.ClaudeCompatibility
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.runtime.UnsupportedClaudeCandidates
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.runtime.UnsupportedClaudeCompatibility
import io.aequicor.heartbeat.feature.aiengine.claude.impl.domain.ClaudeBackend
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridge
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityRequirements
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityStamp
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptResourceHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.UnavailableAgentToolBridge
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidate
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntimeCandidateRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.TimeMark

@ContributesBinding(ProfileScope::class, binding = binding<ClaudeBackend>())
@ContributesBinding(ProfileScope::class, binding = binding<ClaudeAuthentication>())
@SingleIn(ProfileScope::class)
@Inject
internal class JvmClaudeBackend(
    private val transport: ClaudeTransport,
    private val account: ClaudeAccount,
    private val toggles: FeatureToggles,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val catalog: ClaudeCatalog,
    private val tools: ProfileAgentTools = NoAgentTools,
    private val bridge: AgentToolBridge = UnavailableAgentToolBridge,
    private val native: ClaudeNativeSupport = MissingClaudeNativeSupport,
    private val resources: ResourceResolver = ResourceResolver { null },
    private val resourceHistory: PromptResourceHistory = PromptResourceHistory.None,
    private val compatibility: ClaudeCompatibility = UnsupportedClaudeCompatibility,
    private val candidates: ClaudeCandidates = UnsupportedClaudeCandidates,
) : ClaudeBackend {
    /** Sources of configured bindings; the CLI resolves the login itself, so only ids are kept. */
    private val routes = mutableMapOf<EngineBindingId, AuthSourceId>()

    private val log = Log.tag("ClaudeBackend")

    // Written by discovery and by restoring the saved catalog, read by turns on other threads.
    private val inputSupports = ConcurrentHashMap<Pair<AuthRevision, ModelId>, PromptInputSupport>()
    private val efforts = ConcurrentHashMap<Pair<AuthRevision, ModelId>, List<String>>()
    private val mutex = Mutex()
    private var runtime: ClaudeRuntime? = null

    /**
     * Shows the history of stored sessions of another identity while [runtime] is live; it never runs turns. It is
     * closed when a profile runtime is created in place of [runtime], when another identity is read, and with the
     * profile; views it handed out then answer with an unavailable history.
     */
    private var reader: ClaudeRuntime? = null

    /** The login whose saved model capabilities were already restored in this process. */
    private var restoredRevision: AuthRevision? = null

    override suspend fun inspect(): ClaudeLogin {
        enabled()
        return account.inspect()
    }

    override suspend fun sourceRevision(source: AuthSource): AuthRevision {
        enabled()
        val login = account.inspect()
        if (login.check.verdict != AuthVerdict.Authenticated) authFailure(AuthFailureReason.NotAuthenticated)
        return login.source.info.revision
    }

    /** Researched policy of this connector build; a target without generated facts has no admission. */
    override val runtimeRequirements: EngineCompatibilityRequirements? get() = compatibility.requirements()

    override suspend fun openRuntimeCandidate(
        request: EngineRuntimeCandidateRequest,
        deadline: TimeMark,
    ): EngineRuntimeCandidate? = candidates.open(request, deadline) { executable, stamp ->
        obtainRuntime(request.identity, stamp, executable)
    }

    /** An installation probe reports every adapter failure as availability; only cancellation escapes. */
    override suspend fun checkRequirements(): EngineAvailability = try {
        enabled()
        withProbeTimeout {
            var isVersion = false
            log.i { "Probing Claude installation" }
            val exit = transport.run(listOf("--version")) {
                isVersion = isVersion || VERSION.containsMatchIn(it)
                false
            }
            if (exit == 0 && isVersion) {
                EngineAvailability.Available
            } else {
                EngineAvailability.Unavailable(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: EngineException) {
        log.w(e.redacted()) { "Claude installation probe failed" }
        EngineAvailability.Unavailable(e.failure)
    } catch (e: Exception) {
        log.w(e.redacted()) { "Claude installation probe failed unexpectedly" }
        EngineAvailability.Unavailable(EngineFailure.Engine(EngineFailureReason.Unavailable))
    }

    override fun accepts(source: AuthSource, context: EngineContext): Boolean =
        context.engine == ClaudeEngine.Id && source is AuthSource.CliLogin &&
            source.owner == ClaudeEngine.AuthOwner && source.location == ClaudeEngine.AuthLocation &&
            source.scope.provider.value == "anthropic" &&
            source.scope.origin.value == "https://api.anthropic.com"

    override fun authContext(context: EngineContext) = ClaudeEngine.AuthContext

    /** Allows runtimes for [source] of a user-configured binding; the CLI itself still resolves the login. */
    override suspend fun bind(binding: EngineBindingId, source: AuthSource): Unit = mutex.withLock {
        if (!accepts(source, EngineContext(ClaudeEngine.Id, binding))) {
            log.w { "Rejected Claude route: foreign source" }
            authFailure(AuthFailureReason.AuthMismatch)
        }
        // Checked before the no-op shortcut: an unchanged source id may now carry a foreign scope.
        if (routes[binding] == source.info.id) return@withLock
        log.i { "Binding Claude route" }
        routes[binding] = source.info.id
    }

    /** Forgets the route of [binding]; unknown bindings are ignored. */
    override suspend fun unbind(binding: EngineBindingId): Unit = mutex.withLock {
        if (routes.remove(binding) != null) log.i { "Unbinding Claude route" }
    }

    override suspend fun discoverModels(source: AuthSource, context: EngineContext): List<ModelInfo> {
        enabled()
        if (!accepts(source, context)) {
            log.w { "Claude model discovery requested for a foreign source" }
            authFailure(AuthFailureReason.AuthMismatch)
        }
        account.validate(source.info.revision)
        log.i { "Discovering Claude models" }
        val entries = mutableListOf<JsonObject>()
        val discovered = withProbeTimeout {
            var models: List<ModelInfo>? = null
            transport.run(
                claudeArguments() + listOf("--input-format", "stream-json"),
                CLAUDE_MODELS_INITIALIZE + "\n",
                context.workspace,
                closeInput = false,
            ) { line ->
                val message = parseClaudeObject(line)
                if (message.text("type") == "control_response") {
                    val response = message["response"] as? JsonObject ?: protocolFailure()
                    if (response.text("request_id") != "models" || response.text("subtype") != "success") {
                        protocolFailure()
                    }
                    val body = response["response"] as? JsonObject ?: protocolFailure()
                    val list = body["models"] as? JsonArray ?: protocolFailure()
                    models = list.map { modelInfo(it, context, source.info.revision) }
                    entries += list.filterIsInstance<JsonObject>()
                }
                models != null
            }
            (models ?: protocolFailure()).also { log.i { "Discovered Claude models count=${it.size}" } }
        }
        saveCapabilities(source.info.revision, entries)
        return discovered
    }

    /** What a restart needs to judge a model without discovery; a failed write only costs that restart. */
    private suspend fun saveCapabilities(revision: AuthRevision, models: List<JsonObject>) {
        if (revision !is AuthRevision.Known) return
        try {
            catalog.saveModels(revision, models.map { model -> JsonObject(model.filterKeys { it in CAPABILITY_KEYS }) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e.redacted()) { "Claude model capabilities were not saved" }
        }
    }

    /**
     * Loads the capabilities the last discovery of [revision] saved, once per process; a model discovered in this
     * process keeps its fresher entry. A saved catalog that cannot be read leaves the models without capabilities.
     */
    private suspend fun restoreCapabilities(revision: AuthRevision) {
        if (revision !is AuthRevision.Known || revision == restoredRevision) return
        val saved = try {
            catalog.findModels(revision)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e.redacted()) { "Saved Claude model capabilities could not be read" }
            null
        }
        // One attempt per process and login; a cancelled one is retried by the next open.
        restoredRevision = revision
        saved ?: return
        saved.forEach { model ->
            val id = model.text("value")?.takeIf(String::isNotBlank) ?: return@forEach
            val key = revision to ModelId(id)
            efforts.putIfAbsent(key, model.effortLevels())
            inputSupports.putIfAbsent(key, claudeInputSupport(model, isVendorOriginConfirmed = true))
        }
        log.d { "Restored Claude model capabilities count=${saved.size}" }
    }

    private fun modelInfo(
        entry: kotlinx.serialization.json.JsonElement,
        context: EngineContext,
        revision: AuthRevision,
    ): ModelInfo {
        val model = entry as? JsonObject ?: protocolFailure()
        val id = model.text("value")?.takeIf(String::isNotBlank) ?: protocolFailure()
        val levels = model.effortLevels().also { efforts[revision to ModelId(id)] = it }
        return ModelInfo(
            EngineTarget(ClaudeEngine.Id, context.binding, ModelId(id)),
            model.text("displayName") ?: id,
            reasoningEfforts = levels,
            inputSupport = claudeInputSupport(
                model,
                isVendorOriginConfirmed = true,
            ).also { inputSupports[revision to ModelId(id)] = it },
        )
    }

    override suspend fun createRuntime(identity: RuntimeIdentity): EngineRuntime = obtainRuntime(identity, null, null)

    /**
     * Runtime entry point: [stamp] is non-null only for a facade admission of exactly that contract, and
     * [admittedExecutable] is the executable its candidate verified. A runtime created outside admission keeps a
     * null stamp, so the first admission replaces it instead of reusing it.
     */
    private suspend fun obtainRuntime(
        identity: RuntimeIdentity,
        stamp: EngineCompatibilityStamp?,
        admittedExecutable: String?,
    ): ClaudeRuntime = open(identity, isRouteRequired = true, stamp = stamp, admittedExecutable = admittedExecutable)

    /**
     * The pooled runtime of [identity]. Only a stored session opens without a bound route ([isRouteRequired] is
     * false): its identity comes from this profile's own catalog, and routes live in memory, so after a restart
     * they are empty until the facade binds them again, which it does before it resumes or sends anything.
     * While a live runtime of another identity exists, such a session gets a history-only reader instead of
     * replacing it. An admission adds [stamp]: only a live runtime that already proves exactly that contract is
     * reused, so an unadmitted runtime is replaced instead of silently keeping the transfer.
     */
    private suspend fun open(
        identity: RuntimeIdentity,
        isRouteRequired: Boolean,
        stamp: EngineCompatibilityStamp? = null,
        admittedExecutable: String? = null,
    ): ClaudeRuntime = mutex.withLock {
        enabled()
        val isRouteKnown = identity.source == ClaudeEngine.AuthSource || identity.source in routes.values
        if (identity.engine != ClaudeEngine.Id || (isRouteRequired && !isRouteKnown)) {
            log.w { "Claude runtime requested for a foreign identity" }
            authFailure(AuthFailureReason.AuthMismatch)
        }
        val current = runtime
        when {
            current != null && current.identity == identity && !current.isClosed &&
                (stamp == null || current.compatibilityStamp == stamp) -> current

            // A live runtime belongs to its turns: reading a stored session of another identity must not end them.
            !isRouteRequired && current != null && !current.isClosed -> reading(identity, current.identity.revision)

            else -> replaceRuntime(current, identity, stamp, admittedExecutable)
        }
    }

    private suspend fun replaceRuntime(
        current: ClaudeRuntime?,
        identity: RuntimeIdentity,
        stamp: EngineCompatibilityStamp?,
        admittedExecutable: String?,
    ): ClaudeRuntime {
        restoreCapabilities(identity.revision)
        val pinnedTransport = transport.pinned()
        // Compare the admitted artifact with the transport this runtime is about to pin before any process,
        // account probe or user configuration access.
        if (stamp != null) verifyAdmittedTransport(pinnedTransport, admittedExecutable, stamp)
        val pinnedAccount = account.pinned(pinnedTransport)
        pinnedAccount.validate(identity.revision)
        // The ordinary process reports its version once; the candidate compares it with the assessed stamp.
        val admission = stamp?.let {
            ClaudeRuntimeAdmission(
                stamp = it,
                observedVersion = pinnedTransport.version(),
                selectedExecutable = pinnedTransport.executable,
            )
        }
        when {
            current == null -> Unit
            current.identity != identity && !current.isClosed -> current.retire()
            else -> current.close()
        }
        // Sessions the reader holds may be run by this runtime from now on, so its copies would go stale.
        reader?.close()
        reader = null
        log.i { "Creating Claude profile runtime" }
        return newRuntime(identity, pinnedTransport, pinnedAccount, isReadOnly = false, admission = admission)
            .also { runtime = it }
    }

    /** The pinned transport must start the admitted executable whose bytes still match the assessed stamp. */
    private suspend fun verifyAdmittedTransport(
        transport: ClaudeTransport,
        admittedExecutable: String?,
        stamp: EngineCompatibilityStamp,
    ) {
        val executable = transport.executable
        val isSelected = admittedExecutable != null && executable == admittedExecutable
        val isDigest = isSelected && transport.executableSha256() == stamp.runtimeFingerprint
        if (!isDigest) {
            log.w { "Claude transport no longer starts the admitted executable" }
            throw EngineException(EngineFailure.Engine(EngineFailureReason.CompatibilityUnknown))
        }
    }

    /**
     * A runtime that only serves the history of stored sessions of [identity]; callers hold the backend mutex.
     * The live runtime checked the login of [liveRevision] when it opened, so an equal revision is trusted and only
     * another login asks the CLI.
     */
    private suspend fun reading(identity: RuntimeIdentity, liveRevision: AuthRevision): ClaudeRuntime {
        val existing = reader
        if (existing != null && existing.identity == identity && !existing.isClosed) return existing
        restoreCapabilities(identity.revision)
        val pinnedTransport = transport.pinned()
        val pinnedAccount = account.pinned(pinnedTransport)
        if (identity.revision != liveRevision) pinnedAccount.validate(identity.revision)
        existing?.close()
        log.i { "Creating Claude reading runtime" }
        return newRuntime(identity, pinnedTransport, pinnedAccount, isReadOnly = true).also { reader = it }
    }

    private fun newRuntime(
        identity: RuntimeIdentity,
        pinnedTransport: ClaudeTransport,
        pinnedAccount: ClaudeAccount,
        isReadOnly: Boolean,
        admission: ClaudeRuntimeAdmission? = null,
    ) = ClaudeRuntime(
        identity,
        pinnedTransport,
        pinnedAccount,
        toggles,
        profile.coroutineScope,
        catalog = catalog,
        tools = tools,
        bridge = bridge,
        native = native,
        resources = resources,
        resourceHistory = resourceHistory,
        efforts = { model -> efforts[identity.revision to model] },
        inputSupport = { model ->
            inputSupports[identity.revision to model] ?: claudeInputSupport(
                JsonObject(mapOf("value" to JsonPrimitive(model.value))),
                isVendorOriginConfirmed = true,
            )
        },
        isReadOnly = isReadOnly,
        admission = admission,
    )

    override suspend fun session(ref: SessionRef): EngineSession {
        enabled()
        val record = catalog.find(ref)?.takeIf {
            ref.engine == ClaudeEngine.Id && ref.source == ClaudeEngine.SessionSource
        } ?: run {
            log.w { "Claude catalog has no requested session" }
            throw EngineException(EngineFailure.Session(SessionFailureReason.NotFound))
        }
        val identity = RuntimeIdentity(record.route.engine, record.route.authSource, record.route.revision)
        return open(identity, isRouteRequired = false).stored(ref)
    }

    private suspend fun enabled() {
        if (profile.isClosed) throw EngineException(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
        requireClaudeEnabled(toggles)
    }
}

/** The only model fields the saved capabilities keep, next to the fingerprint of the login they belong to. */
private val CAPABILITY_KEYS = setOf(
    "value",
    "resolvedModel",
    "supportsVision",
    "inputModalities",
    "supportedEffortLevels",
)

private val VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")

/** Effort levels a model advertises; unknown values are dropped so `--effort` never receives them. */
private fun JsonObject.effortLevels(): List<String> = (this["supportedEffortLevels"] as? JsonArray).orEmpty()
    .asSequence()
    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    .filter { it in ClaudeEffortLevels }
    .distinct()
    .toList()
