package io.aequicor.heartbeat.platform.dibundle

import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.instancekeeper.InstanceKeeperOwner
import com.arkivanov.essenty.statekeeper.StateKeeper
import com.arkivanov.essenty.statekeeper.StateKeeperOwner
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.StringKey
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.StorageMaintenance
import io.aequicor.heartbeat.core.datastore.StorageRoot
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeFactory
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.SharedFactory
import io.aequicor.heartbeat.core.di.SharedKey
import io.aequicor.heartbeat.core.profilefacade.ActiveProfileStorage
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.secrets.impl.SecretsConfig
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpClientFactory
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpStdioTransportFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.yield
import kotlinx.serialization.builtins.serializer
import java.nio.file.Files

/**
 * App graph for integration tests: the real contributions of all modules plus the test ones below.
 * [PersistedProfile] plays the role of disk — it outlives a graph, i.e. "survives process death".
 * Storages of core:datastore live in its temporary [PersistedProfile.storageRoot].
 */
@DependencyGraph(AppScope::class)
interface TestAppGraph : HeartbeatGraph {
    val acpClients: AcpClientFactory
    val acpStdio: AcpStdioTransportFactory
    val scopes: ScopeFactory

    @ForScope(AppScope::class)
    val appScope: ScopeHandle

    @ForScope(AppScope::class)
    val appStores: DataStores

    val storageMaintenance: StorageMaintenance
    val machines: MachineRegistry
    val httpClient: HttpClient

    val httpEngine: HttpClientEngine

    @DependencyGraph.Factory
    interface Factory {
        fun create(
            @Provides persisted: PersistedProfile,
            @Provides secretsConfig: SecretsConfig = persisted.secretsConfig,
        ): TestAppGraph
    }
}

class PersistedProfile(val isOperationSuspensionEnabled: Boolean = true) {
    var id: ProfileId? = null
    var beforeProfileWipe: suspend () -> Unit = {}
    val secretsConfig: SecretsConfig by lazy {
        SecretsConfig("heartbeat.test." + java.util.UUID.randomUUID(), storageRoot + "/secrets", isDevelopment = true)
    }

    /** Storage directory of the "device"; created lazily, deleted by the tests that use storages. */
    val storageRoot: String by lazy { Files.createTempDirectory("hb-storage").toString() }
}

/** Keeps the storages of core:datastore in the temporary directory of the "device". */
@ContributesBinding(AppScope::class, priority = 1)
@Inject
class TestStorageRoot(private val persisted: PersistedProfile) : StorageRoot {
    override fun path(): String = persisted.storageRoot
}

/** Accessor to profile-owned storages. */
@ContributesTo(ProfileScope::class)
interface TestStorageAccessors {
    @ForScope(ProfileScope::class)
    val stores: DataStores
}

/** Overrides the DataStore implementation of core:datastore:impl (priority 0) and the in-memory default. */
@ContributesBinding(AppScope::class, priority = 1)
@Inject
class FakeActiveProfileStorage(private val persisted: PersistedProfile) : ActiveProfileStorage {
    // Usually suspends like DataStore; tests can also exercise an in-memory implementation with no suspension.
    override suspend fun read(): ProfileId? {
        if (persisted.isOperationSuspensionEnabled) yield()
        return persisted.id
    }

    override suspend fun write(id: ProfileId?) {
        if (persisted.isOperationSuspensionEnabled) yield()
        persisted.id = id
    }
}

// ---- a feature: its own scope and graph extension, contributed to ProfileScope ----

interface TestFeatureScope

@GraphExtension(TestFeatureScope::class)
interface TestFeatureGraph {
    val draft: Draft
    val machine: Machine<CounterState, CounterIntent, CounterOutput>

    @ForScope(TestFeatureScope::class)
    val scope: ScopeHandle

    @ContributesTo(ProfileScope::class)
    @GraphExtension.Factory
    fun interface Factory {
        fun create(
            @Provides @ForScope(TestFeatureScope::class) scope: ScopeHandle,
        ): TestFeatureGraph
    }
}

/** Feature-scoped object with state that must survive process death. */
@SingleIn(TestFeatureScope::class)
@Inject
class Draft(
    @ForScope(TestFeatureScope::class) scope: ScopeHandle,
) {
    var text: String? = scope.savedState.consume(KEY, String.serializer())

    init {
        scope.savedState.register(KEY, String.serializer()) { text }
    }

    private companion object {
        const val KEY = "draft"
    }
}

/** Accessor pattern: platform code reaches profile-level entry points by casting the ProfileGraph. */
@ContributesTo(ProfileScope::class)
interface TestProfileAccessors {
    val featureGraphs: TestFeatureGraph.Factory
    val scopes: ScopeFactory
}

// ---- a shared object ----

object CounterKey : SharedKey<Counter> {
    override val name = "counter"
}

data class Counter(val scope: ScopeHandle)

@ContributesIntoMap(ProfileScope::class)
@StringKey("counter")
@Inject
class CounterFactory : SharedFactory<Counter> {
    override fun create(scope: ScopeHandle) = Counter(scope)
}

object BrokenKey : SharedKey<Counter> {
    override val name = "broken"
}

@SingleIn(ProfileScope::class) // the test inspects the same instance SharedScopes uses
@ContributesIntoMap(ProfileScope::class)
@StringKey("broken")
@Inject
class BrokenFactory : SharedFactory<Counter> {
    var lastScope: ScopeHandle? = null

    override fun create(scope: ScopeHandle): Counter {
        lastScope = scope
        error("factory failure")
    }
}

/** Accessor to reach the failing factory from the test. */
@ContributesTo(ProfileScope::class)
interface TestSharedAccessors {
    val sharedFactories: Map<String, SharedFactory<*>>
}

/** Minimal Decompose-like component: what `ComponentContext` provides to the extensions. */
class TestComponent(override val instanceKeeper: InstanceKeeper, override val stateKeeper: StateKeeper) :
    InstanceKeeperOwner,
    StateKeeperOwner
