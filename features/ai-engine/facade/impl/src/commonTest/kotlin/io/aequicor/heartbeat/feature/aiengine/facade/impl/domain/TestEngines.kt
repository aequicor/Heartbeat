package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthCheck
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthCheckBasis
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthChecks
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthContextKey
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
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthVerdict
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthenticatorId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFamily
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineFactory
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineSessionSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.time.Clock
import kotlin.time.Instant

internal val TestEngine = EngineId("test")
internal val TestOwner = AuthOwnerId("test-cli")
internal val TestAuthScope = AuthScope(ProviderId("openai"), EndpointOrigin("https://api.example.com"))

/** Context on the test scheduler with deterministic tokens. */
internal fun TestScope.facadeContext(clock: Clock = FixedClock()): FacadeContext {
    var counter = 0
    return FacadeContext(backgroundScope, clock, StandardTestDispatcher(testScheduler)) { "t${++counter}" }
}

internal class FixedClock(var now: Instant = Instant.fromEpochSeconds(1_000)) : Clock {
    override fun now(): Instant = now
}

/** Adapter factory whose answers are set by the test. */
internal class FakeEngineFactory : EngineFactory {
    var availability: EngineAvailability = EngineAvailability.Available
    var probeFailure: Exception? = null
    var probes = 0
    var accepts: (AuthSource, EngineContext) -> Boolean = { _, _ -> true }
    var models: List<ModelInfo> = emptyList()
    var modelsFailure: Exception? = null
    var runtime: (RuntimeIdentity) -> EngineRuntime = { error("no runtime in this test") }
    val createdRuntimes = mutableListOf<RuntimeIdentity>()
    val routes = mutableMapOf<EngineBindingId, AuthSource>()

    override suspend fun checkRequirements(): EngineAvailability {
        probes++
        probeFailure?.let { throw it }
        return availability
    }

    override fun accepts(source: AuthSource, context: EngineContext): Boolean = accepts.invoke(source, context)

    override fun authContext(context: EngineContext): AuthContextKey = AuthContextKey("ctx.${context.engine.value}")

    override suspend fun bind(binding: EngineBindingId, source: AuthSource) {
        routes[binding] = source
    }

    override suspend fun unbind(binding: EngineBindingId) {
        routes.remove(binding)
    }

    override suspend fun discoverModels(source: AuthSource, context: EngineContext): List<ModelInfo> {
        modelsFailure?.let { throw it }
        return models
    }

    override suspend fun createRuntime(identity: RuntimeIdentity): EngineRuntime {
        createdRuntimes += identity
        return runtime(identity)
    }
}

internal fun registration(
    factory: EngineFactory = FakeEngineFactory(),
    id: EngineId = TestEngine,
    platforms: Set<EnginePlatform> = EnginePlatform.entries.toSet(),
    sources: List<EngineSessionSource> = emptyList(),
    features: Set<EngineFeatureId> = emptySet(),
    owner: AuthOwnerId = TestOwner,
) = EngineRegistration(
    EngineDescriptor(
        id,
        "Engine ${id.value}",
        EngineFamily.Vendor,
        platforms,
        FeatureToggle.Flag("engine.${id.value}", "Engine ${id.value}"),
        declaredFeatures = features,
    ),
    owner,
    lazyOf(factory),
    sources,
    setOf(AuthenticatorId("${id.value}.cli")),
)

internal class FakeEngineToggles : EngineToggles {
    val disabled = MutableStateFlow(emptySet<EngineId>())

    override fun observe(descriptor: EngineDescriptor): Flow<Boolean> = disabled.map { descriptor.id !in it }

    override suspend fun isEnabled(descriptor: EngineDescriptor): Boolean = descriptor.id !in disabled.value
}

internal class FakeBindingStore : BindingStore {
    val bindings = MutableStateFlow(emptyList<EngineBinding>())

    override fun observe(): Flow<List<EngineBinding>> = bindings

    override suspend fun load(): List<EngineBinding> = bindings.value

    override suspend fun save(bindings: List<EngineBinding>) {
        this.bindings.value = bindings
    }
}

/** Source registry holding metadata only. */
internal class FakeAuthSources : AuthSources {
    private val sources = MutableStateFlow(emptyList<AuthSource>())
    override val state: StateFlow<List<AuthSource>> = sources

    fun add(source: AuthSource): AuthSource = source.also { sources.value += it }

    fun remove(id: AuthSourceId) {
        sources.value = sources.value.filterNot { it.info.id == id }
    }

    override suspend fun get(id: AuthSourceId): AuthSource? = sources.value.firstOrNull { it.info.id == id }

    override suspend fun addManagedKey(label: String, scope: AuthScope, key: Secret) = error("unused")

    override suspend fun replaceManagedKey(id: AuthSourceId, key: Secret) = error("unused")

    override suspend fun register(draft: AuthSourceDraft) = error("unused")

    override suspend fun updateRevision(id: AuthSourceId, revision: AuthRevision) = error("unused")

    override suspend fun forget(id: AuthSourceId) = remove(id)
}

internal class FakeAuthChecks(private val clock: Clock) : AuthChecks {
    val calls = mutableListOf<Triple<AuthSourceId, AuthContextKey, Set<AuthenticatorId>>>()

    override fun last(source: AuthSourceId, context: AuthContextKey): AuthCheck? = null

    override suspend fun check(
        source: AuthSourceId,
        context: AuthContextKey,
        authenticators: Set<AuthenticatorId>,
    ): AuthCheck {
        calls += Triple(source, context, authenticators)
        return AuthCheck(
            source,
            context,
            AuthVerdict.Authenticated,
            AuthCheckBasis.Service,
            clock.now(),
            AuthRevision.Known("r1"),
        )
    }
}

internal fun managedKey(id: String = "src_key", revision: AuthRevision = AuthRevision.Known("r1")) =
    AuthSource.ManagedKey(AuthSourceInfo(AuthSourceId(id), "key", revision), TestAuthScope, AuthSecretId("vault_$id"))

internal fun cliLogin(id: String = "src_cli", owner: AuthOwnerId = TestOwner) = AuthSource.CliLogin(
    AuthSourceInfo(AuthSourceId(id), "cli", AuthRevision.Unknown),
    TestAuthScope,
    owner,
    AuthLocationId("default"),
)
