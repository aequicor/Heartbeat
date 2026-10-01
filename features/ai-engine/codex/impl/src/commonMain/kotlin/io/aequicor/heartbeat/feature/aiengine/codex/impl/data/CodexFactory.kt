package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthContextKey
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailure
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import io.aequicor.heartbeat.feature.aiengine.codex.api.CodexEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngineTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class CodexFactory(private val environment: CodexRuntimeEnvironment, private val transport: CodexTransport) :
    CodexEngineFactory {
    private val config get() = environment.config
    private val toggles get() = environment.toggles
    private val dispatchers get() = environment.dispatchers
    private val profile get() = environment.profile

    /** Sources of configured bindings; the CLI resolves the login itself, so only ids are kept. */
    private val routes = mutableMapOf<EngineBindingId, AuthSourceId>()

    private val log = Log.tag("CodexFactory")
    private val lock = Mutex()
    private var runtime: CodexRuntime? = null

    override suspend fun checkRequirements(): EngineAvailability = withContext(dispatchers.main) {
        // The probe starts a CLI process, so a disabled integration must never reach it.
        if (!toggles.get(CodexEngine.Enabled)) {
            log.i { "Codex requirements skipped: integration disabled" }
            return@withContext EngineAvailability.Unavailable(
                EngineFailure.Access(AccessFailureReason.OperationNotAllowed),
            )
        }
        lock.withLock {
            if (runtime?.isClosed == false) EngineAvailability.Available else transport.available()
        }
    }

    override fun accepts(source: AuthSource, context: EngineContext): Boolean =
        context.engine == CodexEngine.Id && source is AuthSource.CliLogin &&
            source.owner == CodexEngine.AuthOwner && source.location == config.location &&
            source.scope.provider == ProviderId("openai") &&
            source.scope.origin == EndpointOrigin("https://api.openai.com")

    override fun authContext(context: EngineContext): AuthContextKey = AuthContextKey("codex.openai.cli")

    /** Allows runtimes for [source] of a user-configured binding; the CLI itself still resolves the login. */
    override suspend fun bind(binding: EngineBindingId, source: AuthSource): Unit = lock.withLock {
        if (!accepts(source, EngineContext(CodexEngine.Id, binding))) {
            log.w { "Rejected Codex route: foreign source" }
            fail(EngineFailure.Authentication(AuthFailure(AuthFailureReason.AuthMismatch)))
        }
        // Checked before the no-op shortcut: an unchanged source id may now carry a foreign scope.
        if (routes[binding] == source.info.id) return@withLock
        log.i { "Binding Codex route" }
        routes[binding] = source.info.id
    }

    /** Forgets the route of [binding]; unknown bindings are ignored. */
    override suspend fun unbind(binding: EngineBindingId): Unit = lock.withLock {
        if (routes.remove(binding) != null) log.i { "Unbinding Codex route" }
    }

    override suspend fun discoverModels(source: AuthSource, context: EngineContext): List<ModelInfo> {
        if (!accepts(source, context)) fail(EngineFailure.Authentication(AuthFailure(AuthFailureReason.AuthMismatch)))
        val owner = createRuntime(RuntimeIdentity(CodexEngine.Id, source.info.id, source.info.revision)) as CodexRuntime
        return owner.models(context.binding)
    }

    private var isSearchToolsChangeLogged = false

    override suspend fun createRuntime(identity: RuntimeIdentity): EngineRuntime = withContext(dispatchers.main) {
        lock.withLock {
            gate(identity)
            val isSearchToolsEnabled = toggles.get(SearchEngineTools)
            // The toggle never closes a live runtime: its value is captured at creation and applies to later ones.
            runtime?.takeIf { !it.isClosed && it.identity == identity }?.let { live ->
                if (live.isSearchToolsEnabled != isSearchToolsEnabled && !isSearchToolsChangeLogged) {
                    isSearchToolsChangeLogged = true
                    log.i { "Search tools toggle changed; applies after runtime restart" }
                }
                return@withLock live
            }
            isSearchToolsChangeLogged = false
            runtime?.close()
            runtime = null
            val rpc = CodexRpc(transport.open(), profile.coroutineScope)
            val startup = profile.onClose(rpc::close)
            try {
                // Hosted coding tools also require experimental negotiation, independently of web search.
                rpc.initialize(experimentalApi = true)
                val owner = CodexRuntime(identity, rpc, environment, isSearchToolsEnabled)
                owner.checkAccount()
                runtime = owner
                log.i { "Codex runtime ready" }
                owner
            } catch (e: CancellationException) {
                rpc.close()
                throw e
            } catch (e: EngineException) {
                rpc.close()
                throw e
            } finally {
                startup.dispose()
            }
        }
    }

    private suspend fun gate(identity: RuntimeIdentity) {
        if (profile.isClosed) fail(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
        if (!toggles.get(CodexEngine.Enabled)) fail(EngineFailure.Access(AccessFailureReason.OperationNotAllowed))
        if (identity.engine != CodexEngine.Id ||
            (identity.source != config.source && identity.source !in routes.values)
        ) {
            fail(EngineFailure.Authentication(AuthFailure(AuthFailureReason.AuthMismatch)))
        }
        // This adapter cannot verify an authenticator's opaque revision. Never pretend it matched.
        if (identity.revision != AuthRevision.Unknown) {
            fail(
                EngineFailure.Authentication(AuthFailure(AuthFailureReason.SourceChanged)),
            )
        }
    }
}
