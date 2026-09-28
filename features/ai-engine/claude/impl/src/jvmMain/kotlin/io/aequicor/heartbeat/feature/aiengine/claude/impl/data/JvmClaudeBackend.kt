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
import io.aequicor.heartbeat.feature.aiengine.claude.impl.domain.ClaudeBackend
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@ContributesBinding(ProfileScope::class, binding = binding<ClaudeBackend>())
@ContributesBinding(ProfileScope::class, binding = binding<ClaudeAuthentication>())
@SingleIn(ProfileScope::class)
@Inject
internal class JvmClaudeBackend(
    private val transport: ClaudeTransport,
    private val account: ClaudeAccount,
    private val toggles: FeatureToggles,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : ClaudeBackend {
    /** Sources of configured bindings; the CLI resolves the login itself, so only ids are kept. */
    private val routes = mutableMapOf<EngineBindingId, AuthSourceId>()

    private val log = Log.tag("ClaudeBackend")
    private val mutex = Mutex()
    private var runtime: ClaudeRuntime? = null

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
        return withProbeTimeout {
            var models: List<ModelInfo>? = null
            transport.run(
                claudeArguments() + listOf("--input-format", "stream-json"),
                INITIALIZE + "\n",
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
                    models = list.map { entry ->
                        val model = entry as? JsonObject ?: protocolFailure()
                        val id = model.text("value")?.takeIf(String::isNotBlank) ?: protocolFailure()
                        ModelInfo(
                            EngineTarget(ClaudeEngine.Id, context.binding, ModelId(id)),
                            model.text("displayName") ?: id,
                            reasoningEfforts = model.effortLevels(),
                        )
                    }
                }
                models != null
            }
            (models ?: protocolFailure()).also { log.i { "Discovered Claude models count=${it.size}" } }
        }
    }

    override suspend fun createRuntime(identity: RuntimeIdentity): EngineRuntime = mutex.withLock {
        enabled()
        if (identity.engine != ClaudeEngine.Id ||
            (identity.source != ClaudeEngine.AuthSource && identity.source !in routes.values)
        ) {
            log.w { "Claude runtime requested for a foreign identity" }
            authFailure(AuthFailureReason.AuthMismatch)
        }
        account.validate(identity.revision)
        val current = runtime
        if (current != null && current.identity == identity && !current.isClosed) return@withLock current
        when {
            current == null -> Unit
            current.identity != identity && !current.isClosed -> current.retire()
            else -> current.close()
        }
        log.i { "Creating Claude profile runtime" }
        ClaudeRuntime(identity, transport, account, toggles, profile.coroutineScope).also { runtime = it }
    }

    override suspend fun session(ref: SessionRef): EngineSession = mutex.withLock {
        runtime?.stored(ref) ?: run {
            log.w { "No Claude runtime holds the requested session" }
            throw EngineException(EngineFailure.Session(SessionFailureReason.NotFound))
        }
    }

    private suspend fun enabled() {
        if (profile.isClosed) throw EngineException(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
        requireClaudeEnabled(toggles)
    }
}

private val VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
private const val INITIALIZE = """{"type":"control_request","request_id":"models","request":{"subtype":"initialize"}}"""

/** Effort levels a model advertises; unknown values are dropped so `--effort` never receives them. */
private fun JsonObject.effortLevels(): List<String> = (this["supportedEffortLevels"] as? JsonArray).orEmpty()
    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    .filter { it in ClaudeEffortLevels }
    .distinct()
