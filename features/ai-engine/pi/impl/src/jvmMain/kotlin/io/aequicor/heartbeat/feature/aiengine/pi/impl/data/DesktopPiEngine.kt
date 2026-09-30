package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthContextKey
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEnabled
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEngineId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

@Inject
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class, binding = binding<PiAdapter>())
internal class DesktopPiEngine(
    private val settings: PiSettings,
    private val processes: PiProcessLauncher,
    private val dispatchers: DispatcherProvider,
    private val toggles: FeatureToggles,
    private val environment: PiSessionEnvironment,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val workspaces: LocalWorkspaces,
    private val nativeWeb: PiNativeWeb,
) : PiAdapter {
    private val log = Log.tag("DesktopPiEngine")
    private val mutex = Mutex()
    private val runtimes = mutableMapOf<AuthSourceId, PiRuntime>()

    override suspend fun checkRequirements(): EngineAvailability = withContext(dispatchers.io) {
        log.d { "Checking bundled Pi installation" }
        val os = System.getProperty("os.name")
        if (!os.startsWith("Windows") && !os.startsWith("Mac")) {
            EngineAvailability.UnsupportedPlatform
        } else if (processes.executable()?.let { Files.isRegularFile(it) && Files.isExecutable(it) } == true) {
            EngineAvailability.Available
        } else {
            EngineAvailability.Unavailable(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        }
    }

    override fun accepts(source: AuthSource, context: EngineContext): Boolean = acceptsPi(source, context)

    override fun authContext(context: EngineContext): AuthContextKey =
        AuthContextKey("pi." + fingerprint(context.engine.value + ":" + context.binding.value))

    override suspend fun bind(binding: EngineBindingId, source: AuthSource): Unit = mutex.withLock {
        if (source !is AuthSource.ManagedKey || !accepts(source, EngineContext(PiEngineId, binding))) {
            log.w { "Rejected Pi credential route: unsupported source" }
            authenticationFailure(AuthFailureReason.AuthMismatch, source.info.id)
        }
        if (source.info.revision !is AuthRevision.Known) {
            log.w { "Rejected Pi credential route: unknown revision" }
            piFailure(EngineFailure.Request(RequestFailureReason.Invalid))
        }
        val before = settings.snapshot().bindings
        // Route resolution repeats bind before every turn; an unchanged route must not rewrite settings.
        if (before[binding.value] == source) return@withLock
        log.i { "Binding Pi credential route" }
        settings.bind(binding, source)
        retireUnused(retiredSources(before, settings.snapshot().bindings))
    }

    override suspend fun unbind(binding: EngineBindingId): Unit = mutex.withLock {
        val before = settings.snapshot().bindings
        settings.unbind(binding) ?: return@withLock
        log.i { "Unbinding Pi credential route" }
        retireUnused(retiredSources(before, settings.snapshot().bindings))
    }

    override suspend fun configureWorkspace(workspace: WorkspaceRef, directory: String) {
        val path = withContext(dispatchers.io) {
            try {
                Path.of(directory).toRealPath().takeIf { Files.isDirectory(it) }
            } catch (e: IOException) {
                log.w(e) { "Pi workspace directory is unavailable" }
                null
            } catch (e: InvalidPathException) {
                log.w(e) { "Pi workspace directory is invalid" }
                null
            } catch (e: SecurityException) {
                log.w(e) { "Pi workspace directory is not accessible" }
                null
            }
        } ?: piFailure(EngineFailure.Request(RequestFailureReason.Invalid))
        log.i { "Configuring Pi workspace" }
        settings.workspace(workspace, path.toString())
    }

    /** Closes pooled runtimes of [sources] whose stored route changed or disappeared; caller holds [mutex]. */
    private suspend fun retireUnused(sources: Set<AuthSourceId>) {
        sources.forEach { id -> runtimes.remove(id)?.close() }
    }

    override suspend fun discoverModels(source: AuthSource, context: EngineContext): List<ModelInfo> {
        ensureEnabled()
        if (!accepts(source, context)) authenticationFailure(AuthFailureReason.AuthMismatch, source.info.id)
        val configured = settings.snapshot().bindings[context.binding.value]
        if (configured != source) authenticationFailure(AuthFailureReason.SourceChanged, source.info.id)
        val connection = processes.start(configured, null, {}, { failure ->
            log.w(EngineException(failure)) { "Pi discovery connection failed" }
        })
        return try {
            val response = connection.command("get_available_models")
            (response["models"] as? JsonArray).orEmpty().mapNotNull { element ->
                val model = element.jsonObject
                if (model.string("provider") != source.scope.provider.value) return@mapNotNull null
                val id = model.string("id") ?: return@mapNotNull null
                ModelInfo(
                    EngineTarget(PiEngineId, context.binding, ModelId(source.scope.provider.value + "/" + id)),
                    model.string("name") ?: id,
                    contextLimitTokens = model["contextWindow"]?.jsonPrimitive?.longOrNull,
                    reasoningEfforts = model.piThinkingLevels(),
                )
            }
        } finally {
            connection.close()
        }
    }

    override suspend fun createRuntime(identity: RuntimeIdentity): EngineRuntime = mutex.withLock {
        ensureEnabled()
        require(identity.engine == PiEngineId) { "Foreign engine" }
        val source = settings.source(identity.source)
        if (source.info.revision != identity.revision || identity.revision is AuthRevision.Unknown) {
            authenticationFailure(AuthFailureReason.SourceChanged, identity.source)
        }
        val existing = runtimes[identity.source]
        if (existing != null && existing.identity == identity && !existing.isClosed) {
            existing.validate()
            return@withLock existing
        }
        existing?.close()
        // Runtime/session/transport objects carry request-specific state and are owned by this profile service.
        PiRuntime(
            PiRuntimeCredentials(identity, source, processes.credentialFingerprint(source)),
            environment,
            PiRuntimeServices(settings, processes, toggles, workspaces, nativeWeb),
        ).also { runtimes[identity.source] = it }
    }

    private suspend fun ensureEnabled() {
        if (profile.isClosed) piFailure(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed))
        if (!toggles.get(AiEngines) || !toggles.get(PiEnabled)) {
            piFailure(EngineFailure.Access(AccessFailureReason.OperationNotAllowed))
        }
        if (checkRequirements() != EngineAvailability.Available) {
            piFailure(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        }
    }
}

/**
 * Sources whose stored route changed or disappeared between two binding snapshots. A source still referenced
 * with identical metadata by any binding keeps its pooled runtime.
 */
internal fun retiredSources(
    before: Map<String, AuthSource.ManagedKey>,
    after: Map<String, AuthSource.ManagedKey>,
): Set<AuthSourceId> = (before.values.toSet() - after.values.toSet()).map { it.info.id }.toSet()
